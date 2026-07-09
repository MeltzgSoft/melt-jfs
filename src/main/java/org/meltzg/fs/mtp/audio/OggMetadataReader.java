package org.meltzg.fs.mtp.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads tags and duration from an Ogg-framed audio file (Ogg Vorbis or Opus) via a header read.
 *
 * <p>Ogg is a sequence of pages ({@code OggS} + a 27-byte header + a segment table). Logical-stream
 * packets are reconstructed from the segment lacing across pages. The first packet is the codec's
 * identification header (Vorbis {@code \1vorbis} or {@code OpusHead}); the second is the comment header
 * (Vorbis {@code \3vorbis} or {@code OpusTags}), whose body is a {@link VorbisComment} block — the same
 * tag format FLAC uses. Duration is the granule position of the last page (÷ sample rate for Vorbis;
 * for Opus, minus the pre-skip, over the fixed 48 kHz Opus granule clock).
 *
 * <p><b>Known limitations:</b> unlike FLAC, Ogg keeps cover art inside the comment packet, so a file
 * with large embedded art may carry tags beyond {@link #HEAD_BYTES} and have them truncated; duration
 * needs a known {@code fileSize} (to read the tail) and is 0 without it. FLAC-in-Ogg and Speex are not
 * handled (returns empty tags).
 */
public final class OggMetadataReader {

    /** How much of the head to read for the id + comment packets. */
    private static final int HEAD_BYTES = 128 * 1024;
    /** How much of the tail to read to find the final page's granule. */
    private static final int TAIL_BYTES = 128 * 1024;
    private static final long OPUS_GRANULE_RATE = 48_000; // Opus granule positions are always at 48 kHz

    private enum Codec { VORBIS, OPUS }

    private OggMetadataReader() {}

    /**
     * Parses tags and duration from {@code source}. {@code fileSize} is needed to read the tail for
     * duration (pass non-positive when unknown). Returns null when the source is not an Ogg stream of a
     * supported codec.
     */
    public static AudioTags readTags(RangedByteSource source, long fileSize) throws IOException {
        var head = readUpTo(source, 0, HEAD_BYTES);
        if (head == null || !isOggS(head, 0)) return null;

        var packets = firstPackets(head, 2);
        if (packets.isEmpty()) return null;
        byte[] id = packets.get(0);
        Codec codec = detect(id);
        if (codec == null) return null;

        Map<String, String> comments = Map.of();
        if (packets.size() >= 2) {
            byte[] comment = packets.get(1);
            int offset = codec == Codec.OPUS ? 8 : 7; // "OpusTags" (8) or "\3vorbis" (7)
            if (comment.length > offset) comments = VorbisComment.parse(comment, offset);
        }

        long duration = duration(source, fileSize, codec, id);
        return new AudioTags(
            comments.get("title"),
            comments.get("artist"),
            comments.get("album"),
            comments.get("genre"),
            VorbisComment.leadingInt(comments.get("tracknumber")),
            VorbisComment.leadingInt(comments.get("discnumber")),
            duration);
    }

    private static Codec detect(byte[] id) {
        if (id.length >= 8 && matches(id, 0, "OpusHead")) return Codec.OPUS;
        if (id.length >= 7 && id[0] == 1 && matches(id, 1, "vorbis")) return Codec.VORBIS;
        return null;
    }

    // ---- page / packet reconstruction ----

    /** Reconstructs up to {@code max} logical-stream packets from concatenated Ogg pages in {@code buf}. */
    private static List<byte[]> firstPackets(byte[] buf, int max) {
        var packets = new ArrayList<byte[]>();
        var current = new ByteArrayOutputStream();
        int pos = 0;
        while (pos + 27 <= buf.length && packets.size() < max) {
            if (!isOggS(buf, pos)) break;
            int pageSegments = buf[pos + 26] & 0xFF;
            int tableStart = pos + 27;
            int bodyStart = tableStart + pageSegments;
            if (bodyStart > buf.length) break;

            int body = bodyStart;
            for (int i = 0; i < pageSegments; i++) {
                int lacing = buf[tableStart + i] & 0xFF;
                if (body + lacing > buf.length) return packets; // page body truncated by the head window
                current.write(buf, body, lacing);
                body += lacing;
                if (lacing < 255) { // a segment < 255 ends the packet
                    packets.add(current.toByteArray());
                    current.reset();
                    if (packets.size() >= max) return packets;
                }
            }
            pos = body; // next page starts right after this page's body
        }
        return packets;
    }

    // ---- duration (last page granule) ----

    private static long duration(RangedByteSource source, long fileSize, Codec codec, byte[] id) throws IOException {
        if (fileSize <= 0) return 0;
        long tailStart = Math.max(0, fileSize - TAIL_BYTES);
        var tail = readUpTo(source, tailStart, (int) Math.min(TAIL_BYTES, fileSize));
        if (tail == null) return 0;
        long granule = lastGranule(tail);
        if (granule < 0) return 0;

        if (codec == Codec.OPUS) {
            int preSkip = id.length >= 12 ? le16(id, 10) : 0;
            long samples = granule - preSkip;
            return samples > 0 ? samples * 1000L / OPUS_GRANULE_RATE : 0;
        }
        long sampleRate = id.length >= 16 ? le32(id, 12) : 0; // Vorbis id header: sample rate at offset 12
        return sampleRate > 0 ? granule * 1000L / sampleRate : 0;
    }

    /** The granule position of the last valid page in {@code buf}, or -1 if none is found. */
    private static long lastGranule(byte[] buf) {
        for (int i = buf.length - 27; i >= 0; i--) {
            if (isOggS(buf, i) && buf[i + 4] == 0 /* stream structure version */) {
                return le64(buf, i + 6);
            }
        }
        return -1;
    }

    // ---- byte helpers ----

    private static boolean isOggS(byte[] b, int off) {
        return off + 4 <= b.length && b[off] == 'O' && b[off + 1] == 'g' && b[off + 2] == 'g' && b[off + 3] == 'S';
    }

    private static boolean matches(byte[] b, int off, String ascii) {
        if (off + ascii.length() > b.length) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if ((b[off + i] & 0xFF) != ascii.charAt(i)) return false;
        }
        return true;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    /** Reads up to {@code length} bytes (fewer near end-of-object), or null if nothing is available. */
    private static byte[] readUpTo(RangedByteSource source, long offset, int length) throws IOException {
        if (length <= 0) return null;
        var buf = new byte[length];
        int got = 0;
        while (got < length) {
            var chunk = source.read(offset + got, length - got);
            if (chunk.length == 0) break;
            System.arraycopy(chunk, 0, buf, got, chunk.length);
            got += chunk.length;
        }
        if (got == 0) return null;
        return got == length ? buf : java.util.Arrays.copyOf(buf, got);
    }
}
