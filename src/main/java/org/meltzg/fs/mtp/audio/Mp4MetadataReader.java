package org.meltzg.fs.mtp.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads an MP4/M4A file's tags and duration from a header read (no whole-object transfer).
 *
 * <p>MP4 is a tree of length-prefixed atoms (boxes): each is a 4-byte big-endian size and a 4-char
 * type, then payload (size 1 means a 64-bit size follows; size 0 means "to end of file"). Because every
 * atom declares its length, the walk is seek-driven and bounded — the audio ({@code mdat}) and cover art
 * ({@code covr}) are skipped by their size, never transferred, whether {@code moov} sits at the front
 * (streaming-optimized) or the end of the file.
 *
 * <p>Tags come from {@code moov/udta/meta/ilst} (iTunes-style): {@code ©nam}/{@code ©ART}/{@code ©alb}/
 * {@code ©gen} text atoms and the {@code trkn}/{@code disk} binary atoms, each wrapping a {@code data}
 * sub-atom. Duration comes from {@code moov/mvhd} (duration ÷ timescale).
 *
 * <p><b>Known limitations (follow-ups):</b> a numeric {@code gnre} genre atom is not mapped to a name;
 * only the standard iTunes {@code meta} FullBox layout is handled (a non-FullBox {@code meta} would need
 * a heuristic).
 */
public final class Mp4MetadataReader {

    /** Safety cap on the searched span when the file size is unknown. */
    private static final long MAX_SPAN = 1L << 40;
    /** Per-container iteration guard against a malformed/adversarial atom chain. */
    private static final int MAX_ATOMS = 4096;
    /** Cap on a single tag value we will pull into memory. */
    private static final int MAX_VALUE_BYTES = 1 << 20;

    private Mp4MetadataReader() {}

    /** One atom: its 4-char type, content range [start, end), and the offset of the next sibling. */
    private record Atom(String type, long start, long end, long next) {}

    /** A tag's {@code data} sub-atom value plus its type indicator (1 = UTF-8 text, 0 = binary). */
    private record DataValue(byte[] bytes, int typeIndicator) {}

    /**
     * Parses tags and duration from {@code source}. {@code fileSize} bounds the top-level walk (pass a
     * non-positive value when unknown). Returns null when the source is not an MP4 container (no
     * {@code moov} atom).
     */
    public static AudioTags readTags(RangedByteSource source, long fileSize) throws IOException {
        long end = fileSize > 0 ? fileSize : MAX_SPAN;
        var moov = findChild(source, 0, end, "moov");
        if (moov == null) return null; // not an MP4 container (or moov unreachable)

        var mvhd = findChild(source, moov.start, moov.end, "mvhd");
        long duration = mvhd == null ? 0 : parseDuration(source, mvhd);

        String title = null, artist = null, album = null, genre = null;
        int track = 0, disc = 0;

        var udta = findChild(source, moov.start, moov.end, "udta");
        var meta = udta == null ? null : findChild(source, udta.start, udta.end, "meta");
        // meta is a FullBox: its child atoms start 4 bytes (version + flags) into its content.
        var ilst = meta == null ? null : findChild(source, meta.start + 4, meta.end, "ilst");
        if (ilst != null) {
            long pos = ilst.start;
            int guard = 0;
            while (pos + 8 <= ilst.end && guard++ < MAX_ATOMS) {
                var atom = readAtom(source, pos, ilst.end);
                if (atom == null || atom.next <= pos) break;
                var value = readDataValue(source, atom);
                if (value != null) {
                    switch (atom.type) {
                        case "©nam" -> title = text(value);
                        case "©ART" -> artist = text(value);
                        case "©alb" -> album = text(value);
                        case "©gen" -> genre = text(value);
                        case "trkn" -> track = uint16(value.bytes, 2);
                        case "disk" -> disc = uint16(value.bytes, 2);
                        default -> { } // covr and other atoms are ignored
                    }
                }
                pos = atom.next;
            }
        }
        return new AudioTags(title, artist, album, genre, track, disc, duration);
    }

    // ---- atom walking ----

    /** The first child atom of {@code type} within [start, end), or null. */
    private static Atom findChild(RangedByteSource source, long start, long end, String type) throws IOException {
        long pos = start;
        int guard = 0;
        while (pos + 8 <= end && guard++ < MAX_ATOMS) {
            var atom = readAtom(source, pos, end);
            if (atom == null || atom.next <= pos) return null;
            if (atom.type.equals(type)) return atom;
            pos = atom.next;
        }
        return null;
    }

    /** Reads the atom header at {@code pos}, bounded by {@code limit}, or null if it doesn't fit/parse. */
    private static Atom readAtom(RangedByteSource source, long pos, long limit) throws IOException {
        if (pos + 8 > limit) return null;
        var header = readFully(source, pos, 8);
        if (header == null) return null;
        long size = uint32(header, 0);
        // The © in ©nam/©ART/… is 0xA9; ISO-8859-1 maps it to U+00A9, so type strings compare cleanly.
        String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
        long headerLen = 8;
        if (size == 1) { // 64-bit extended size follows the type
            var ext = readFully(source, pos + 8, 8);
            if (ext == null) return null;
            size = int64(ext, 0);
            headerLen = 16;
        } else if (size == 0) { // extends to the end of the container
            size = limit - pos;
        }
        if (size < headerLen) return null;
        long atomEnd = Math.min(pos + size, limit);
        return new Atom(type, pos + headerLen, atomEnd, atomEnd);
    }

    // ---- field parsing ----

    /** {@code mvhd} duration in milliseconds (duration ÷ timescale), handling version 0 and 1 layouts. */
    private static long parseDuration(RangedByteSource source, Atom mvhd) throws IOException {
        var versionFlags = readFully(source, mvhd.start, 4);
        if (versionFlags == null) return 0;
        int version = versionFlags[0] & 0xFF;
        if (version == 1) {
            var b = readFully(source, mvhd.start + 4, 28); // creation(8) modification(8) timescale(4) duration(8)
            if (b == null) return 0;
            long timescale = uint32(b, 16);
            long duration = int64(b, 20);
            return timescale == 0 ? 0 : duration * 1000L / timescale;
        }
        var b = readFully(source, mvhd.start + 4, 16); // creation(4) modification(4) timescale(4) duration(4)
        if (b == null) return 0;
        long timescale = uint32(b, 8);
        long duration = uint32(b, 12);
        return timescale == 0 ? 0 : duration * 1000L / timescale;
    }

    /** Reads the value of a tag atom's {@code data} sub-atom, or null when absent/oversized. */
    private static DataValue readDataValue(RangedByteSource source, Atom tag) throws IOException {
        var data = findChild(source, tag.start, tag.end, "data");
        if (data == null) return null;
        long valueStart = data.start + 8; // type indicator (4) + locale (4)
        long valueLen = data.end - valueStart;
        if (valueLen <= 0 || valueLen > MAX_VALUE_BYTES) return null;
        var indicator = readFully(source, data.start, 4);
        var bytes = readFully(source, valueStart, (int) valueLen);
        if (indicator == null || bytes == null) return null;
        return new DataValue(bytes, indicator[3] & 0xFF);
    }

    private static String text(DataValue value) {
        var s = new String(value.bytes, StandardCharsets.UTF_8).strip();
        return s.isEmpty() ? null : s;
    }

    // ---- byte helpers ----

    private static int uint16(byte[] b, int off) {
        return off + 2 <= b.length ? ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF) : 0;
    }

    private static long uint32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
            | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long int64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
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
