package org.meltzg.fs.mtp.audio;

import java.io.IOException;
import java.util.Arrays;

/**
 * A source of bytes readable in ranges without transferring the whole object. The contract mirrors
 * {@link org.meltzg.fs.mtp.MtpBackend#readPartial}, so a device file can back one directly (e.g.
 * {@code (off, n) -> bridge.readPartial(id, path, off, n)}).
 */
@FunctionalInterface
public interface RangedByteSource {

    /**
     * Reads up to {@code maxBytes} bytes starting at {@code offset}. Returns the bytes actually read:
     * possibly shorter than requested near end-of-object, and empty at or past it.
     */
    byte[] read(long offset, int maxBytes) throws IOException;

    /** An in-memory source over {@code data}, for tests and callers that already hold the bytes. */
    static RangedByteSource ofArray(byte[] data) {
        return (offset, maxBytes) -> {
            if (offset < 0 || maxBytes < 0) throw new IllegalArgumentException("negative offset/maxBytes");
            if (offset >= data.length || maxBytes == 0) return new byte[0];
            int from = (int) offset;
            int to = (int) Math.min((long) from + maxBytes, data.length);
            return Arrays.copyOfRange(data, from, to);
        };
    }
}
