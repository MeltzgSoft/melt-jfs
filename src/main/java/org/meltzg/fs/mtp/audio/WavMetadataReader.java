package org.meltzg.fs.mtp.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a WAV (RIFF) file's tags and duration from a header read (no whole-object transfer).
 *
 * <p>A WAV file is {@code RIFF <size> WAVE} followed by length-prefixed chunks. This walks them,
 * skipping the (large) {@code data} chunk by its size — so only headers and small metadata chunks are
 * transferred. Duration is derived from the {@code fmt } byte-rate and the {@code data} chunk size.
 * Tags come from a {@code LIST}/{@code INFO} chunk ({@code INAM} title, {@code IART} artist,
 * {@code IPRD} album, {@code IGNR} genre, {@code IPRT}/{@code ITRK} track).
 *
 * <p><b>Known limitations:</b> RIFF {@code INFO} has no disc-number field, so {@code discNumber} is
 * always 0 for INFO-tagged WAVs. An alternative {@code id3 } chunk (a full ID3v2 tag, which some
 * taggers write and which would carry disc) is a follow-up — it would reuse the ID3 parsing in
 * {@link Mp3MetadataReader}.
 */
public final class WavMetadataReader {

    /** Safety cap on the chunk walk when the file size is unknown / RIFF size is implausible. */
    private static final long MAX_SPAN = 1L << 32;
    private static final int MAX_CHUNKS = 4096;
    private static final int MAX_INFO_BYTES = 1 << 20;

    private WavMetadataReader() {}

    /**
     * Parses tags and duration from {@code source}. {@code fileSize} bounds the chunk walk (the RIFF
     * header's own size is used when {@code fileSize} is non-positive). Returns null when the source is
     * not a RIFF/WAVE file.
     */
    public static AudioTags readTags(RangedByteSource source, long fileSize) throws IOException {
        var header = readFully(source, 0, 12);
        if (header == null || !matches(header, 0, "RIFF") || !matches(header, 8, "WAVE")) return null;

        long riffEnd = 8 + le32(header, 4);
        long end = fileSize > 0 ? Math.min(fileSize, riffEnd) : riffEnd;
        if (end <= 12 || end > MAX_SPAN) end = MAX_SPAN;

        long byteRate = 0;
        long dataSize = -1;
        Map<String, String> info = Map.of();

        long pos = 12;
        int guard = 0;
        while (pos + 8 <= end && guard++ < MAX_CHUNKS) {
            var ch = readFully(source, pos, 8);
            if (ch == null) break;
            String id = new String(ch, 0, 4, StandardCharsets.US_ASCII);
            long size = le32(ch, 4);
            long dataStart = pos + 8;

            switch (id) {
                case "fmt " -> {
                    var fmt = readFully(source, dataStart, (int) Math.min(size, 16));
                    if (fmt != null && fmt.length >= 12) byteRate = le32(fmt, 8);
                }
                case "data" -> dataSize = size;
                case "LIST" -> {
                    if (size <= MAX_INFO_BYTES) {
                        var list = readFully(source, dataStart, (int) size);
                        if (list != null && list.length >= 4 && matches(list, 0, "INFO")) {
                            info = parseInfo(list);
                        }
                    }
                }
                default -> { } // skip other chunks (incl. the audio in "data") by their size
            }

            pos = dataStart + size + (size & 1); // chunks are word-aligned: pad an odd size
        }

        long duration = (byteRate > 0 && dataSize > 0) ? dataSize * 1000L / byteRate : 0;
        return new AudioTags(
            info.get("title"),
            info.get("artist"),
            info.get("album"),
            info.get("genre"),
            VorbisComment.leadingInt(info.get("track")),
            0, // RIFF INFO carries no disc number
            duration);
    }

    /** Parses a {@code LIST}/{@code INFO} chunk body (after the {@code INFO} tag) into normalized fields. */
    private static Map<String, String> parseInfo(byte[] list) {
        var out = new HashMap<String, String>();
        int pos = 4; // skip the "INFO" list type
        while (pos + 8 <= list.length) {
            String id = new String(list, pos, 4, StandardCharsets.US_ASCII);
            int size = (int) le32(list, pos + 4);
            int dataStart = pos + 8;
            if (size < 0 || dataStart + size > list.length) break;
            String field = switch (id) {
                case "INAM" -> "title";
                case "IART" -> "artist";
                case "IPRD" -> "album";
                case "IGNR" -> "genre";
                case "IPRT", "ITRK" -> "track";
                default -> null;
            };
            if (field != null) {
                var value = new String(list, dataStart, size, StandardCharsets.ISO_8859_1);
                int nul = value.indexOf('\0');
                if (nul >= 0) value = value.substring(0, nul);
                value = value.strip();
                if (!value.isEmpty()) out.putIfAbsent(field, value);
            }
            pos = dataStart + size + (size & 1); // word-aligned
        }
        return out;
    }

    private static boolean matches(byte[] b, int off, String ascii) {
        if (off + ascii.length() > b.length) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if ((b[off + i] & 0xFF) != ascii.charAt(i)) return false;
        }
        return true;
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    /** Reads exactly {@code length} bytes, or null if the source ends before delivering them all. */
    private static byte[] readFully(RangedByteSource source, long offset, int length) throws IOException {
        if (length == 0) return new byte[0];
        var out = new byte[length];
        int got = 0;
        while (got < length) {
            var chunk = source.read(offset + got, length - got);
            if (chunk.length == 0) return null;
            System.arraycopy(chunk, 0, out, got, chunk.length);
            got += chunk.length;
        }
        return out;
    }
}
