package org.meltzg.fs.mtp.audio;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds in-memory FLAC byte streams for tests, with known tag values. STREAMINFO describes a 44100 Hz
 * stereo 16-bit stream of 88200 samples, so the derived duration is exactly {@link #DURATION_MILLIS}.
 */
public final class SyntheticFlac {

    public static final String TITLE = "Real Title";
    public static final String ARTIST = "Real Artist";
    public static final String ALBUM = "Real Album";
    public static final String GENRE = "Soundtrack";
    public static final int TRACK = 3;
    public static final int DISC = 1;
    public static final long DURATION_MILLIS = 2000; // 88200 samples / 44100 Hz

    private SyntheticFlac() {}

    /** {@code fLaC} + STREAMINFO + VORBIS_COMMENT(last): a small, valid tagged stream. */
    public static byte[] tagsOnly() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(FlacMetadataReader.MAGIC);
        out.writeBytes(blockHeader(FlacMetadataReader.TYPE_STREAMINFO, false, 34));
        out.writeBytes(streamInfo());
        var comment = vorbisComment();
        out.writeBytes(blockHeader(FlacMetadataReader.TYPE_VORBIS_COMMENT, true, comment.length));
        out.writeBytes(comment);
        return out.toByteArray();
    }

    /** {@code fLaC} + STREAMINFO + a large PICTURE + VORBIS_COMMENT(last): the worst-case block order. */
    public static byte[] withPicture(int pictureBytes) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(FlacMetadataReader.MAGIC);
        out.writeBytes(blockHeader(FlacMetadataReader.TYPE_STREAMINFO, false, 34));
        out.writeBytes(streamInfo());
        out.writeBytes(blockHeader(FlacMetadataReader.TYPE_PICTURE, false, pictureBytes));
        out.writeBytes(new byte[pictureBytes]); // opaque cover-art bytes; contents don't matter here
        var comment = vorbisComment();
        out.writeBytes(blockHeader(FlacMetadataReader.TYPE_VORBIS_COMMENT, true, comment.length));
        out.writeBytes(comment);
        return out.toByteArray();
    }

    /** {@code fLaC} + STREAMINFO(last): a valid but tagless stream (no VORBIS_COMMENT). */
    public static byte[] streamInfoOnly() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(FlacMetadataReader.MAGIC);
        out.writeBytes(blockHeader(FlacMetadataReader.TYPE_STREAMINFO, true, 34));
        out.writeBytes(streamInfo());
        return out.toByteArray();
    }

    private static byte[] blockHeader(int type, boolean last, int length) {
        return new byte[]{
            (byte) ((last ? 0x80 : 0) | (type & 0x7F)),
            (byte) (length >>> 16),
            (byte) (length >>> 8),
            (byte) length,
        };
    }

    /** A valid 34-byte STREAMINFO: 44100 Hz, stereo, 16-bit, 88200 samples. */
    private static byte[] streamInfo() {
        var si = new byte[34];
        putBE16(si, 0, 4096); // min block size
        putBE16(si, 2, 4096); // max block size
        // bytes 4..9 (min/max frame size) left 0 = unknown
        long packed = ((long) 44100 << 44) | ((long) 1 << 41) | ((long) 15 << 36) | 88_200L;
        for (int i = 0; i < 8; i++) {
            si[10 + i] = (byte) (packed >>> (56 - 8 * i));
        }
        // bytes 18..33 (MD5) left 0
        return si;
    }

    /** A VORBIS_COMMENT block body: vendor + the tag fields, with little-endian length prefixes. */
    private static byte[] vorbisComment() {
        var out = new ByteArrayOutputStream();
        writeLenPrefixed(out, "melt-jfs");             // vendor string
        putLE32(out, 6);                                // comment count
        writeLenPrefixed(out, "TITLE=" + TITLE);
        writeLenPrefixed(out, "ARTIST=" + ARTIST);
        writeLenPrefixed(out, "ALBUM=" + ALBUM);
        writeLenPrefixed(out, "GENRE=" + GENRE);
        writeLenPrefixed(out, "TRACKNUMBER=" + TRACK);
        writeLenPrefixed(out, "DISCNUMBER=" + DISC);
        return out.toByteArray();
    }

    private static void writeLenPrefixed(ByteArrayOutputStream out, String s) {
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        putLE32(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void putLE32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void putBE16(byte[] buf, int off, int v) {
        buf[off] = (byte) (v >>> 8);
        buf[off + 1] = (byte) v;
    }
}
