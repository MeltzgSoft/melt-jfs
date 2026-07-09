package org.meltzg.fs.mtp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPItemInfo;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Verifies the pure-{@code java.nio} ranged-read seam: {@link java.nio.file.Files#newByteChannel} /
 * {@link java.nio.file.Files#newInputStream} over an MTP file fetch only the bytes actually read via
 * the lazy {@link MTPLazyReadChannel}, never the whole object. A consumer needs no melt-jfs types —
 * only {@code position()} and {@code read()}.
 */
public class MTPLazyReadChannelTest {

    static final MTPDeviceIdentifier deviceIdentifier = new MTPDeviceIdentifier(
            FakeLibMTP.VENDOR_ID, FakeLibMTP.PRODUCT_ID, FakeLibMTP.SERIAL);

    static final String FILE_PATH = "/" + FakeLibMTP.STORAGE_NAME + "/big.bin";

    // Comfortably larger than the read-ahead chunk, so a prefix read can't accidentally pull it all.
    static final byte[] CONTENT = new byte[300_000];
    static {
        for (int i = 0; i < CONTENT.length; i++) CONTENT[i] = (byte) (i % 251);
    }

    private FakeLibMTP fake;
    private FileSystem fs;

    @Before
    public void setUp() throws IOException {
        fake = new FakeLibMTP();
        fake.childItems.put(MtpBackend.ROOT_PARENT, new MTPItemInfo[]{
                new MTPItemInfo(MtpBackend.ROOT_PARENT, "100", FakeLibMTP.STORAGE_ID, true, CONTENT.length, 0, "big.bin"),
        });
        fake.content.put("100", CONTENT);
        MTPDeviceBridge.setBackend(fake);
        MTPDeviceBridge.INSTANCE.close();
        fs = new MTPFileSystemProvider().newFileSystem(
                URI.create(String.format("mtp://%s/", deviceIdentifier)), null);
    }

    @After
    public void tearDown() throws IOException {
        fs.close();
        MTPDeviceBridge.INSTANCE.close();
        MTPDeviceBridge.setBackend(null);
    }

    @Test
    public void prefixReadFetchesOnlyOneReadAheadChunk() throws IOException {
        try (var ch = Files.newByteChannel(fs.getPath(FILE_PATH), StandardOpenOption.READ)) {
            assertEquals(CONTENT.length, ch.size());
            var buf = ByteBuffer.allocate(16);
            assertEquals(16, ch.read(buf));
            assertArrayEquals(Arrays.copyOf(CONTENT, 16), buf.array());
        }
        // A 16-byte read pulls at most one read-ahead chunk, never the whole object.
        assertTrue("prefix read fetched " + fake.partialBytesServed + " bytes",
            fake.partialBytesServed <= MTPLazyReadChannel.DEFAULT_READ_AHEAD);
        assertTrue(fake.partialBytesServed < CONTENT.length);
    }

    @Test
    public void seekingPastARegionDoesNotTransferIt() throws IOException {
        try (var ch = Files.newByteChannel(fs.getPath(FILE_PATH), StandardOpenOption.READ)) {
            readExactly(ch, 4);                 // pull the head
            ch.position(CONTENT.length - 4);    // skip the whole middle — fetches nothing
            var tail = readExactly(ch, 4);
            assertArrayEquals(Arrays.copyOfRange(CONTENT, CONTENT.length - 4, CONTENT.length), tail);
        }
        // Head chunk + a tiny tail chunk — the skipped middle was never transferred.
        assertTrue("skipping read fetched " + fake.partialBytesServed + " bytes",
            fake.partialBytesServed < CONTENT.length);
    }

    @Test
    public void fullReadReturnsExactContent() throws IOException {
        byte[] all = Files.readAllBytes(fs.getPath(FILE_PATH));
        assertArrayEquals(CONTENT, all);
    }

    @Test
    public void fullCopyUsesOneBulkTransferNotRangedReads() throws IOException {
        Path localTarget = Files.createTempFile("mtp-copy-", ".bin");
        try {
            Files.copy(fs.getPath(FILE_PATH), localTarget, StandardCopyOption.REPLACE_EXISTING);
            assertArrayEquals(CONTENT, Files.readAllBytes(localTarget));
        } finally {
            Files.deleteIfExists(localTarget);
        }
        // Export is a whole-object read: one bulk getFile, zero ranged reads.
        assertEquals("copy should use a single bulk transfer", 1, fake.getFileCalls);
        assertEquals("copy should not fall back to ranged reads", 0, fake.partialBytesServed);
    }

    @Test
    public void newInputStreamIsEager() throws IOException {
        try (var in = Files.newInputStream(fs.getPath(FILE_PATH))) {
            assertEquals(CONTENT[0], (byte) in.read());
        }
        // Even reading a single byte pulls the object once in bulk — streams are for whole-file reads.
        assertEquals(1, fake.getFileCalls);
        assertEquals(0, fake.partialBytesServed);
    }

    @Test
    public void readAtEofReturnsMinusOne() throws IOException {
        try (var ch = Files.newByteChannel(fs.getPath(FILE_PATH), StandardOpenOption.READ)) {
            ch.position(CONTENT.length);
            assertEquals(-1, ch.read(ByteBuffer.allocate(8)));
        }
    }

    private static byte[] readExactly(java.nio.channels.SeekableByteChannel ch, int n) throws IOException {
        var buf = ByteBuffer.allocate(n);
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) break;
        }
        return buf.array();
    }
}
