package org.meltzg.fs.mtp.audio;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads an MP3's tags and duration from a header read (no whole-object transfer).
 *
 * <p>Tags come from an ID3v2 tag at the start of the file: the 10-byte header declares a synchsafe
 * 28-bit tag size, so the exact tag region is read and its frames parsed — ID3v2.2 (3-char frame ids)
 * and v2.3/v2.4 (4-char ids, v2.4 synchsafe frame sizes) are supported, across the four text encodings.
 * Duration comes from the first MPEG audio frame after the tag: a Xing/Info header gives an exact frame
 * count, else a constant-bitrate estimate from the frame header and file size.
 *
 * <p><b>Known limitations (follow-ups):</b> whole-tag ID3v2 unsynchronisation and per-frame v2.4 unsync
 * are not de-applied; a v2.3/v2.4 extended header is skipped best-effort; numeric ID3v1 genre references
 * (e.g. {@code "(17)"}) are returned verbatim rather than mapped to names; an ID3v1 trailer is not read
 * when no ID3v2 tag is present.
 */
public final class Mp3MetadataReader {

    /** Enough to cover the first frame header, side info and a Xing/Info header. */
    private static final int FRAME_PROBE_BYTES = 1024;
    /** Cap on the ID3v2 tag region we will pull into memory to parse. */
    private static final int MAX_TAG_BYTES = 4 * 1024 * 1024;

    private Mp3MetadataReader() {}

    /**
     * Parses tags and duration from {@code source}. {@code fileSize} is the object's total size, used
     * for the constant-bitrate duration estimate; pass a non-positive value when unknown. Never returns
     * null — an MP3 with neither an ID3v2 tag nor a readable frame yields {@link AudioTags#EMPTY}.
     */
    public static AudioTags readTags(RangedByteSource source, long fileSize) throws IOException {
        var header = readFully(source, 0, 10);
        long audioStart = 0;
        Map<String, String> frames = Map.of();

        if (header != null && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            int major = header[3] & 0xFF;
            int flags = header[5] & 0xFF;
            int tagSize = synchsafe(header, 6);
            boolean footer = (flags & 0x10) != 0; // v2.4 footer present
            audioStart = 10L + tagSize + (footer ? 10 : 0);
            if (tagSize > 0 && tagSize <= MAX_TAG_BYTES) {
                var body = readFully(source, 10, tagSize);
                if (body != null) frames = parseFrames(body, major, flags);
            }
        }

        long duration = durationMillis(source, audioStart, fileSize);
        return new AudioTags(
            frames.get("title"),
            frames.get("artist"),
            frames.get("album"),
            frames.get("genre"),
            parseLeadingInt(frames.get("track")),
            parseLeadingInt(frames.get("disc")),
            duration);
    }

    // ---- ID3v2 frame parsing ----

    /** Parses ID3v2 text frames into a normalized key ({@code title/artist/album/genre/track/disc}) map. */
    private static Map<String, String> parseFrames(byte[] body, int major, int tagFlags) {
        var out = new HashMap<String, String>();
        // Best-effort: skip a v2.3/v2.4 extended header when present (our normalized fields are text
        // frames that follow it). Unsynchronisation is not handled — see the class notes.
        int pos = 0;
        if ((tagFlags & 0x40) != 0) { // extended header present
            if (major == 4) {
                pos += synchsafe(body, 0);          // v2.4: size covers itself
            } else if (major == 3 && body.length >= 4) {
                pos += 4 + beInt(body, 0);          // v2.3: size excludes the 4 size bytes
            }
        }

        boolean v22 = major == 2;
        int idLen = v22 ? 3 : 4;
        int headerLen = v22 ? 6 : 10;

        while (pos + headerLen <= body.length && body[pos] != 0) {
            String id = new String(body, pos, idLen, StandardCharsets.US_ASCII);
            int size = v22 ? beInt24(body, pos + 3)
                : major == 4 ? synchsafe(body, pos + 4)
                : beInt(body, pos + 4);
            int dataStart = pos + headerLen;
            if (size <= 0 || dataStart + size > body.length) break;

            String field = fieldFor(id);
            if (field != null) {
                var value = decodeText(body, dataStart, size);
                if (value != null) out.putIfAbsent(field, value);
            }
            pos = dataStart + size;
        }
        return out;
    }

    /** Maps an ID3 text-frame id (v2.2 or v2.3/v2.4) to a normalized field name, or null if uninteresting. */
    private static String fieldFor(String id) {
        return switch (id) {
            case "TIT2", "TT2" -> "title";
            case "TPE1", "TP1" -> "artist";
            case "TALB", "TAL" -> "album";
            case "TCON", "TCO" -> "genre";
            case "TRCK", "TRK" -> "track";
            case "TPOS", "TPA" -> "disc";
            default -> null;
        };
    }

    /** Decodes an ID3v2 text frame body: a 1-byte encoding selector followed by the encoded string. */
    private static String decodeText(byte[] body, int offset, int size) {
        if (size < 1) return null;
        int encoding = body[offset] & 0xFF;
        Charset charset = switch (encoding) {
            case 1 -> StandardCharsets.UTF_16;   // UTF-16 with BOM
            case 2 -> StandardCharsets.UTF_16BE; // UTF-16BE, no BOM (v2.4)
            case 3 -> StandardCharsets.UTF_8;    // v2.4
            default -> StandardCharsets.ISO_8859_1;
        };
        var text = new String(body, offset + 1, size - 1, charset);
        int nul = text.indexOf('\0'); // first value; strip any terminator/extra values
        if (nul >= 0) text = text.substring(0, nul);
        text = text.strip();
        return text.isEmpty() ? null : text;
    }

    // ---- MPEG-frame duration ----

    private static long durationMillis(RangedByteSource source, long audioStart, long fileSize) throws IOException {
        if (audioStart < 0) return 0;
        var probe = readUpTo(source, audioStart, FRAME_PROBE_BYTES);
        if (probe == null) return 0;

        int frame = findFrameSync(probe);
        if (frame < 0) return 0;
        var mpeg = MpegFrame.parse(probe, frame);
        if (mpeg == null) return 0;

        Integer xingFrames = xingFrameCount(probe, frame, mpeg);
        if (xingFrames != null) {
            return xingFrames * (long) mpeg.samplesPerFrame * 1000L / mpeg.sampleRate;
        }
        // Constant-bitrate estimate: audio bytes * 8 / bitrate.
        if (fileSize > 0 && mpeg.bitrateBps > 0) {
            long audioBytes = fileSize - (audioStart + frame);
            if (audioBytes > 0) return audioBytes * 8L * 1000L / mpeg.bitrateBps;
        }
        return 0;
    }

    /** Finds the first valid MPEG frame sync in {@code buf}, or -1. */
    private static int findFrameSync(byte[] buf) {
        for (int i = 0; i + 4 <= buf.length; i++) {
            if ((buf[i] & 0xFF) == 0xFF && (buf[i + 1] & 0xE0) == 0xE0 && MpegFrame.parse(buf, i) != null) {
                return i;
            }
        }
        return -1;
    }

    /** The Xing/Info total-frame count, or null when the header or its frames field is absent. */
    private static Integer xingFrameCount(byte[] buf, int frame, MpegFrame mpeg) {
        int tag = frame + 4 + mpeg.sideInfoBytes;
        if (tag + 8 > buf.length) return null;
        String marker = new String(buf, tag, 4, StandardCharsets.US_ASCII);
        if (!marker.equals("Xing") && !marker.equals("Info")) return null;
        int flags = beInt(buf, tag + 4);
        if ((flags & 0x1) == 0 || tag + 12 > buf.length) return null; // no frames field
        return beInt(buf, tag + 8);
    }

    /** Parsed MPEG audio frame header fields needed for duration. */
    private static final class MpegFrame {
        int sampleRate;
        int samplesPerFrame;
        int bitrateBps;
        int sideInfoBytes;

        // Layer III bitrate tables (kbps) by bitrate index 1..14.
        private static final int[] V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        private static final int[] V2_L3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
        private static final int[] SR_V1 = {44100, 48000, 32000};
        private static final int[] SR_V2 = {22050, 24000, 16000};
        private static final int[] SR_V25 = {11025, 12000, 8000};

        static MpegFrame parse(byte[] b, int i) {
            if (i + 4 > b.length) return null;
            int versionBits = (b[i + 1] >> 3) & 0x3; // 0=2.5, 2=2, 3=1
            int layerBits = (b[i + 1] >> 1) & 0x3;   // 1=III, 2=II, 3=I
            int bitrateIndex = (b[i + 2] >> 4) & 0xF;
            int sampleIndex = (b[i + 2] >> 2) & 0x3;
            int channelMode = (b[i + 3] >> 6) & 0x3; // 3=mono
            if (versionBits == 1 || layerBits == 0 || bitrateIndex == 0 || bitrateIndex == 15 || sampleIndex == 3) {
                return null; // reserved/invalid
            }
            if (layerBits != 1) return null; // Layer III only for now (covers the vast majority)

            boolean v1 = versionBits == 3;
            var f = new MpegFrame();
            f.sampleRate = (versionBits == 3 ? SR_V1 : versionBits == 2 ? SR_V2 : SR_V25)[sampleIndex];
            f.samplesPerFrame = v1 ? 1152 : 576;
            f.bitrateBps = (v1 ? V1_L3 : V2_L3)[bitrateIndex] * 1000;
            boolean mono = channelMode == 3;
            f.sideInfoBytes = v1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
            return f;
        }
    }

    // ---- small helpers ----

    private static int synchsafe(byte[] b, int off) {
        return ((b[off] & 0x7F) << 21) | ((b[off + 1] & 0x7F) << 14)
            | ((b[off + 2] & 0x7F) << 7) | (b[off + 3] & 0x7F);
    }

    private static int beInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
            | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int beInt24(byte[] b, int off) {
        return ((b[off] & 0xFF) << 16) | ((b[off + 1] & 0xFF) << 8) | (b[off + 2] & 0xFF);
    }

    private static int parseLeadingInt(String value) {
        if (value == null) return 0;
        int slash = value.indexOf('/');
        var digits = (slash >= 0 ? value.substring(0, slash) : value).strip();
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Reads exactly {@code length} bytes, or null if the source ends first. */
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

    /** Reads up to {@code length} bytes (fewer near end-of-object), or null if nothing is available. */
    private static byte[] readUpTo(RangedByteSource source, long offset, int length) throws IOException {
        var buf = new byte[length];
        int got = 0;
        while (got < length) {
            var chunk = source.read(offset + got, length - got);
            if (chunk.length == 0) break; // end-of-object
            System.arraycopy(chunk, 0, buf, got, chunk.length);
            got += chunk.length;
        }
        if (got == 0) return null;
        return got == length ? buf : java.util.Arrays.copyOf(buf, got);
    }
}
