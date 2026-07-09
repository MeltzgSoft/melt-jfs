package org.meltzg.fs.mtp.audio;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds in-memory MP4/M4A byte streams for tests, with known tag values and a {@code mvhd} whose
 * duration ÷ timescale is {@link #DURATION_MILLIS}. The atom layout is authored to the spec (not to the
 * reader), so it independently validates {@link Mp4MetadataReader}.
 */
public final class SyntheticMp4 {

    public static final String TITLE = "M4A Title";
    public static final String ARTIST = "M4A Artist";
    public static final String ALBUM = "M4A Album";
    public static final String GENRE = "Electronic";
    public static final int TRACK = 5;
    public static final int DISC = 2;
    public static final long DURATION_MILLIS = 2000; // timescale 1000, duration 2000

    private SyntheticMp4() {}

    /** A file with {@code moov} after the media data ({@code mdat}) — the reader must skip mdat by size. */
    public static byte[] moovLast() {
        return build(false);
    }

    /** A streaming-optimized file with {@code moov} before {@code mdat}. */
    public static byte[] moovFirst() {
        return build(true);
    }

    private static byte[] build(boolean moovFirst) {
        var ftyp = atom("ftyp", concat(ascii("M4A "), be32(0), ascii("M4A "), ascii("mp42"), ascii("isom")));
        var mdat = atom("mdat", new byte[5000]); // opaque audio; skipped by size
        var moov = moov();
        return moovFirst ? concat(ftyp, moov, mdat) : concat(ftyp, mdat, moov);
    }

    private static byte[] moov() {
        return atom("moov", concat(mvhd(), udta()));
    }

    private static byte[] mvhd() {
        var body = new ByteArrayOutputStream();
        body.writeBytes(be32(0));               // version(1)=0 + flags(3)=0
        body.writeBytes(be32(0));               // creation time
        body.writeBytes(be32(0));               // modification time
        body.writeBytes(be32(1000));            // timescale
        body.writeBytes(be32((int) DURATION_MILLIS)); // duration
        body.writeBytes(new byte[80]);          // rate/volume/matrix/... (ignored by the reader)
        return atom("mvhd", body.toByteArray());
    }

    private static byte[] udta() {
        // meta is a FullBox: 4 version/flags bytes precede its child atoms (hdlr, ilst).
        var hdlr = atom("hdlr", new byte[25]); // filler, to exercise child-skipping before ilst
        var meta = atom("meta", concat(be32(0), hdlr, ilst()));
        return atom("udta", meta);
    }

    private static byte[] ilst() {
        return atom("ilst", concat(
            atom("©nam", textData(TITLE)),
            atom("©ART", textData(ARTIST)),
            atom("©alb", textData(ALBUM)),
            atom("©gen", textData(GENRE)),
            atom("trkn", binaryData(new byte[]{0, 0, (byte) (TRACK >> 8), (byte) TRACK, 0, 10, 0, 0})),
            atom("disk", binaryData(new byte[]{0, 0, (byte) (DISC >> 8), (byte) DISC, 0, 3}))));
    }

    /** A {@code data} sub-atom carrying a UTF-8 text value (type indicator 1). */
    private static byte[] textData(String value) {
        return dataAtom(1, value.getBytes(StandardCharsets.UTF_8));
    }

    /** A {@code data} sub-atom carrying a binary value (type indicator 0). */
    private static byte[] binaryData(byte[] value) {
        return dataAtom(0, value);
    }

    private static byte[] dataAtom(int typeIndicator, byte[] value) {
        return atom("data", concat(be32(typeIndicator), be32(0) /* locale */, value));
    }

    /** {@code [size:4][type:4][payload]}; the type is written Latin-1 so {@code ©} becomes byte 0xA9. */
    private static byte[] atom(String type, byte[] payload) {
        var typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        return concat(be32(8 + payload.length), typeBytes, payload);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] be32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
