package org.meltzg.fs.mtp.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads a FLAC file's tags and duration using only ranged reads, never a whole-object transfer.
 *
 * <p>A fixed-size header read is unsafe: it can slice through a multi-megabyte embedded PICTURE
 * (album-art) block, and a downstream parser may choke on the truncated block. FLAC declares every
 * metadata block's length in its 4-byte header, so this walks the block chain, reads only STREAMINFO
 * (for duration) and VORBIS_COMMENT (the tags) in full, and skips PICTURE / PADDING / SEEKTABLE
 * payloads (paying only their 4 header bytes).
 *
 * <p>Two outputs are offered over the same walk:
 * <ul>
 *   <li>{@link #readTags} — the parsed {@link AudioTags}, using a small native VORBIS_COMMENT parser
 *       (no external dependency).</li>
 *   <li>{@link #minimalMetadataStream} — a minimal, self-contained FLAC stream ({@code fLaC} +
 *       STREAMINFO + VORBIS_COMMENT) for callers that prefer to run their own tag parser over bytes.</li>
 * </ul>
 *
 * @see <a href="https://xiph.org/flac/format.html">FLAC format specification</a>
 */
public final class FlacMetadataReader {

    /** FLAC stream marker: the four bytes {@code fLaC} at the start of every FLAC file. */
    static final byte[] MAGIC = {'f', 'L', 'a', 'C'};

    static final int TYPE_STREAMINFO = 0;
    static final int TYPE_PADDING = 1;
    static final int TYPE_APPLICATION = 2;
    static final int TYPE_SEEKTABLE = 3;
    static final int TYPE_VORBIS_COMMENT = 4;
    static final int TYPE_CUESHEET = 5;
    static final int TYPE_PICTURE = 6;

    /** Guards against walking a malformed/adversarial block chain forever. */
    private static final long MAX_METADATA_SPAN = 16L * 1024 * 1024;
    /** A STREAMINFO is 34 bytes and a VORBIS_COMMENT is small; anything past this is malformed. */
    private static final int MAX_KEPT_BLOCK_BYTES = 8 * 1024 * 1024;
    /**
     * Some tag parsers reject any file below a fixed minimum size regardless of structural validity.
     * A stitched STREAMINFO-only stream is ~42 bytes, so pad to comfortably clear that guard with a
     * spec-valid trailing PADDING block.
     */
    private static final int MIN_STITCHED_SIZE = 160;

    private FlacMetadataReader() {}

    /** One metadata block as seen while walking: its type, last-block flag, byte offset and data length. */
    public record BlockInfo(int type, boolean last, long offset, int length) {
        public String typeName() {
            return switch (type) {
                case TYPE_STREAMINFO -> "STREAMINFO";
                case TYPE_PADDING -> "PADDING";
                case TYPE_APPLICATION -> "APPLICATION";
                case TYPE_SEEKTABLE -> "SEEKTABLE";
                case TYPE_VORBIS_COMMENT -> "VORBIS_COMMENT";
                case TYPE_CUESHEET -> "CUESHEET";
                case TYPE_PICTURE -> "PICTURE";
                default -> "RESERVED(" + type + ")";
            };
        }
    }

    /**
     * Parses the embedded tags and duration from {@code source}, transferring only the STREAMINFO and
     * VORBIS_COMMENT blocks. Returns null when the source is not a FLAC stream.
     */
    public static AudioTags readTags(RangedByteSource source) throws IOException {
        var blocks = readMetadataBlocks(source);
        if (blocks == null) return null; // not a FLAC stream

        long duration = blocks.streamInfo == null ? 0 : durationMillis(blocks.streamInfo);
        var comments = blocks.vorbisComment == null ? Map.<String, String>of()
            : VorbisComment.parse(blocks.vorbisComment, 0);
        return new AudioTags(
            comments.get("title"),
            comments.get("artist"),
            comments.get("album"),
            comments.get("genre"),
            VorbisComment.leadingInt(comments.get("tracknumber")),
            VorbisComment.leadingInt(comments.get("discnumber")),
            duration);
    }

    /**
     * Builds a minimal, valid FLAC stream — {@code fLaC} + STREAMINFO + (VORBIS_COMMENT when present) —
     * from {@code source}, transferring only the blocks needed for tags and duration. Returns null when
     * the source is not a FLAC stream. The final metadata block is flagged as last so a parser stops
     * there instead of walking into the audio frames this stream omits.
     */
    public static byte[] minimalMetadataStream(RangedByteSource source) throws IOException {
        var blocks = readMetadataBlocks(source);
        if (blocks == null) return null;
        if (blocks.streamInfo == null) throw new IOException("FLAC stream has no STREAMINFO block");
        return stitch(blocks.streamInfo, blocks.vorbisComment);
    }

    /**
     * Walks and returns the metadata block layout without transferring any block payload. Diagnostic —
     * lets a caller see where VORBIS_COMMENT sits, how large the PICTURE is, and how few bytes the tag
     * read actually needs.
     */
    public static List<BlockInfo> describeBlocks(RangedByteSource source) throws IOException {
        var blocks = new ArrayList<BlockInfo>();
        boolean isFlac = walk(source, (type, last, dataStart, length) -> {
            blocks.add(new BlockInfo(type, last, dataStart - 4, length));
            return false; // keep going to the last block
        });
        if (!isFlac) throw new IOException("not a FLAC stream (missing 'fLaC' marker)");
        return blocks;
    }

    // ---- block walking ----

    /** The two metadata blocks a tag/duration read needs (either may be null if absent). */
    private static final class MetadataBlocks {
        byte[] streamInfo;    // 34-byte STREAMINFO data (no block header)
        byte[] vorbisComment; // VORBIS_COMMENT data (no block header)
    }

    /** Returns null when not a FLAC stream; otherwise the STREAMINFO and VORBIS_COMMENT block data. */
    private static MetadataBlocks readMetadataBlocks(RangedByteSource source) throws IOException {
        var out = new MetadataBlocks();
        boolean isFlac = walk(source, (type, last, dataStart, length) -> {
            if (type == TYPE_STREAMINFO || type == TYPE_VORBIS_COMMENT) {
                if (length > MAX_KEPT_BLOCK_BYTES) {
                    throw new IOException("implausible FLAC " + type + " block length: " + length);
                }
                var data = readFully(source, dataStart, length);
                if (type == TYPE_STREAMINFO) {
                    out.streamInfo = data;
                } else {
                    out.vorbisComment = data;
                }
            }
            // Any other block (PICTURE, PADDING, SEEKTABLE, ...) is skipped without transferring it.
            return out.streamInfo != null && out.vorbisComment != null; // stop once we have both
        });
        return isFlac ? out : null;
    }

    @FunctionalInterface
    private interface BlockVisitor {
        /** @return true to stop the walk early. */
        boolean visit(int type, boolean last, long dataStart, int length) throws IOException;
    }

    /** Verifies the {@code fLaC} marker and walks the metadata block chain. Returns false if not FLAC. */
    private static boolean walk(RangedByteSource source, BlockVisitor visitor) throws IOException {
        var magic = tryReadFully(source, 0, MAGIC.length);
        if (magic == null || !Arrays.equals(magic, MAGIC)) return false;
        long offset = MAGIC.length;
        while (offset + 4 <= MAX_METADATA_SPAN) {
            var header = tryReadFully(source, offset, 4);
            if (header == null) break; // chain ended/truncated before the last block
            boolean last = (header[0] & 0x80) != 0;
            int type = header[0] & 0x7F;
            int length = ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            long dataStart = offset + 4;
            boolean stop = visitor.visit(type, last, dataStart, length);
            offset = dataStart + length;
            if (last || stop) break;
        }
        return true;
    }

    // ---- STREAMINFO / VORBIS_COMMENT parsing ----

    /** Duration in milliseconds from a STREAMINFO block's sample rate (20 bits) and total samples (36 bits). */
    private static long durationMillis(byte[] streamInfo) {
        if (streamInfo.length < 18) return 0;
        long packed = 0;
        for (int i = 0; i < 8; i++) {
            packed = (packed << 8) | (streamInfo[10 + i] & 0xFFL);
        }
        long sampleRate = packed >>> 44;          // top 20 bits
        long totalSamples = packed & 0xFFFFFFFFFL; // low 36 bits
        return sampleRate == 0 ? 0 : totalSamples * 1000L / sampleRate;
    }

    // ---- minimal-stream stitching ----

    private static byte[] stitch(byte[] streamInfo, byte[] vorbisComment) {
        var blocks = new ArrayList<byte[]>();
        blocks.add(metadataBlock(TYPE_STREAMINFO, streamInfo));
        if (vorbisComment != null) blocks.add(metadataBlock(TYPE_VORBIS_COMMENT, vorbisComment));

        int size = MAGIC.length + blocks.stream().mapToInt(b -> b.length).sum();
        if (size < MIN_STITCHED_SIZE) {
            blocks.add(metadataBlock(TYPE_PADDING, new byte[MIN_STITCHED_SIZE - size - 4])); // -4 for header
        }

        var out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        for (int i = 0; i < blocks.size(); i++) {
            out.writeBytes(withLastFlag(blocks.get(i), i == blocks.size() - 1)); // only the final block is "last"
        }
        return out.toByteArray();
    }

    /** A metadata block: a 4-byte header (type + 24-bit length, last-flag clear) followed by {@code data}. */
    private static byte[] metadataBlock(int type, byte[] data) {
        int len = data.length;
        var block = new byte[4 + len];
        block[0] = (byte) (type & 0x7F);
        block[1] = (byte) (len >>> 16);
        block[2] = (byte) (len >>> 8);
        block[3] = (byte) len;
        System.arraycopy(data, 0, block, 4, len);
        return block;
    }

    /** A copy of {@code block} with its header's last-block flag set or cleared. */
    private static byte[] withLastFlag(byte[] block, boolean last) {
        var copy = block.clone();
        copy[0] = (byte) (last ? (copy[0] | 0x80) : (copy[0] & 0x7F));
        return copy;
    }

    // ---- ranged-read helpers ----

    /** Reads exactly {@code length} bytes or throws; loops because a ranged read may return short. */
    private static byte[] readFully(RangedByteSource source, long offset, int length) throws IOException {
        var full = tryReadFully(source, offset, length);
        if (full == null) {
            throw new IOException("truncated FLAC data at offset " + offset + " (wanted " + length + " bytes)");
        }
        return full;
    }

    /** Reads exactly {@code length} bytes, or null if the source ends before delivering them all. */
    private static byte[] tryReadFully(RangedByteSource source, long offset, int length) throws IOException {
        if (length == 0) return new byte[0];
        var out = new byte[length];
        int got = 0;
        while (got < length) {
            var chunk = source.read(offset + got, length - got);
            if (chunk.length == 0) return null; // end-of-object before the full request was met
            System.arraycopy(chunk, 0, out, got, chunk.length);
            got += chunk.length;
        }
        return out;
    }
}
