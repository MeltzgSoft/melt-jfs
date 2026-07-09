package org.meltzg.fs.mtp.audio;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a Vorbis comment block — the {@code KEY=value} tag format shared by FLAC (its VORBIS_COMMENT
 * metadata block), Ogg Vorbis and Opus (their comment-header packets). Lengths are little-endian.
 */
final class VorbisComment {

    private VorbisComment() {}

    /**
     * Parses the block starting at {@code offset} into a lower-cased key → value map (first value per
     * key wins). Returns whatever parsed cleanly before any malformation.
     */
    static Map<String, String> parse(byte[] data, int offset) {
        var map = new HashMap<String, String>();
        var bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        try {
            bb.position(offset);
            int vendorLength = bb.getInt();
            if (vendorLength < 0 || vendorLength > bb.remaining()) return map;
            bb.position(bb.position() + vendorLength); // skip the vendor string
            int count = bb.getInt();
            for (int i = 0; i < count && bb.remaining() >= 4; i++) {
                int length = bb.getInt();
                if (length < 0 || length > bb.remaining()) break;
                var bytes = new byte[length];
                bb.get(bytes);
                var comment = new String(bytes, StandardCharsets.UTF_8);
                int eq = comment.indexOf('=');
                if (eq > 0) {
                    map.putIfAbsent(comment.substring(0, eq).toLowerCase(Locale.ROOT), comment.substring(eq + 1));
                }
            }
        } catch (BufferUnderflowException | IllegalArgumentException malformed) {
            // Return whatever parsed cleanly before the block went bad.
        }
        return map;
    }

    /** The leading integer of an ordinal tag ("7" or "7/12"), or 0 when absent/non-numeric. */
    static int leadingInt(String value) {
        if (value == null) return 0;
        int slash = value.indexOf('/');
        var digits = (slash >= 0 ? value.substring(0, slash) : value).trim();
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
