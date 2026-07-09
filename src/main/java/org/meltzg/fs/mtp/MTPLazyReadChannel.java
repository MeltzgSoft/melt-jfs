package org.meltzg.fs.mtp;

import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

/**
 * A read-only {@link SeekableByteChannel} over an MTP object that fetches bytes lazily through
 * {@link MTPDeviceBridge#readPartial}, never downloading the whole object. This is the pure
 * {@code java.nio} seam for ranged reads: a consumer opens it via {@link java.nio.file.Files#newByteChannel}
 * (or {@link java.nio.file.Files#newInputStream}), seeks, and reads only the prefix it needs — no
 * melt-jfs types involved.
 *
 * <p>A bounded read-ahead buffer coalesces sequential reads into fewer USB transactions, while
 * {@link #position(long) seeking} is free (it fetches nothing). A consumer can therefore skip a large
 * region — e.g. an audio file's embedded album art — by positioning past it, paying only for the bytes
 * it actually reads. Not thread-safe beyond the coarse synchronization here; a channel is expected to
 * be used by one reader at a time.
 */
final class MTPLazyReadChannel implements SeekableByteChannel {

    /** Read-ahead chunk size: large enough to keep sequential reads from being chatty, small enough
     *  that a header-only read stays cheap. */
    static final int DEFAULT_READ_AHEAD = 64 * 1024;

    private final MTPDeviceBridge bridge;
    private final MTPDeviceIdentifier deviceId;
    private final String absPath;
    private final long size;
    private final int readAhead;

    private long position = 0;
    private long bufferStart = 0;
    private byte[] buffer = new byte[0];
    private boolean open = true;

    MTPLazyReadChannel(MTPDeviceBridge bridge, MTPDeviceIdentifier deviceId, String absPath,
                       long size, int readAhead) {
        this.bridge = bridge;
        this.deviceId = deviceId;
        this.absPath = absPath;
        this.size = size;
        this.readAhead = readAhead;
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
        if (!open) throw new ClosedChannelException();
        if (position >= size || dst.remaining() == 0) {
            return position >= size ? -1 : 0;
        }
        if (position < bufferStart || position >= bufferStart + buffer.length) {
            fillBufferAt(position);
            if (buffer.length == 0) return -1; // device returned nothing though we are below EOF
        }
        int offsetInBuffer = (int) (position - bufferStart);
        int n = Math.min(buffer.length - offsetInBuffer, dst.remaining());
        dst.put(buffer, offsetInBuffer, n);
        position += n;
        return n;
    }

    private void fillBufferAt(long pos) throws IOException {
        int want = (int) Math.min(readAhead, size - pos);
        buffer = want <= 0 ? new byte[0] : bridge.readPartial(deviceId, absPath, pos, want);
        bufferStart = pos;
    }

    @Override
    public synchronized long position() {
        return position;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) {
        if (newPosition < 0) throw new IllegalArgumentException("negative position: " + newPosition);
        position = newPosition; // no fetch — the next read() pulls from here
        return this;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public SeekableByteChannel truncate(long newSize) {
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() {
        open = false;
        buffer = new byte[0];
    }
}
