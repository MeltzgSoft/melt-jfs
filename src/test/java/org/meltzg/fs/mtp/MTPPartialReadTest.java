package org.meltzg.fs.mtp;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPItemInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Unit tests for the ranged-read path — {@link MtpBackend#readPartial} surfaced through
 * {@link MTPDeviceBridge#readPartial} — against the in-memory fake. This is the seam a tag reader
 * uses to pull just a file header (e.g. a FLAC {@code fLaC} marker + VORBIS_COMMENT block) instead
 * of transferring the whole object; the device-backed proof lives in the integration suite.
 */
public class MTPPartialReadTest {

    static final MTPDeviceIdentifier deviceIdentifier = new MTPDeviceIdentifier(
            FakeLibMTP.VENDOR_ID, FakeLibMTP.PRODUCT_ID, FakeLibMTP.SERIAL);

    static final String SONG_PATH = "/" + FakeLibMTP.STORAGE_NAME + "/song.flac";
    static final String DIR_PATH = "/" + FakeLibMTP.STORAGE_NAME + "/Music";

    // Stand-in for a FLAC header: the 4-byte magic followed by filler.
    static final byte[] CONTENT;
    static {
        var magic = "fLaC".getBytes(StandardCharsets.US_ASCII);
        CONTENT = new byte[64];
        System.arraycopy(magic, 0, CONTENT, 0, magic.length);
        for (int i = magic.length; i < CONTENT.length; i++) CONTENT[i] = (byte) i;
    }

    @BeforeClass
    public static void setUpFake() throws IOException {
        var fake = new FakeLibMTP();
        fake.childItems.put(MtpBackend.ROOT_PARENT, new MTPItemInfo[]{
                new MTPItemInfo(MtpBackend.ROOT_PARENT, "100", FakeLibMTP.STORAGE_ID, true, CONTENT.length, 0, "song.flac"),
                new MTPItemInfo(MtpBackend.ROOT_PARENT, "102", FakeLibMTP.STORAGE_ID, false, 0, 0, "Music"),
        });
        fake.content.put("100", CONTENT);
        MTPDeviceBridge.setBackend(fake);
        MTPDeviceBridge.INSTANCE.close();
    }

    @AfterClass
    public static void removeFake() throws IOException {
        MTPDeviceBridge.INSTANCE.close();
        MTPDeviceBridge.setBackend(null);
    }

    @Test
    public void readsHeaderSliceFromStart() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        var head = bridge.readPartial(deviceIdentifier, SONG_PATH, 0, 4);
        assertArrayEquals("fLaC".getBytes(StandardCharsets.US_ASCII), head);
    }

    @Test
    public void readsSliceAtOffset() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        var slice = bridge.readPartial(deviceIdentifier, SONG_PATH, 8, 4);
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 8, 12), slice);
    }

    @Test
    public void truncatesToObjectEndWhenMaxBytesOverruns() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        var tail = bridge.readPartial(deviceIdentifier, SONG_PATH, CONTENT.length - 4, 4_096);
        assertArrayEquals(Arrays.copyOfRange(CONTENT, CONTENT.length - 4, CONTENT.length), tail);
    }

    @Test
    public void returnsEmptyAtOrPastEnd() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        assertEquals(0, bridge.readPartial(deviceIdentifier, SONG_PATH, CONTENT.length, 16).length);
    }

    @Test
    public void throwsForMissingFile() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        assertThrows(NoSuchFileException.class,
                () -> bridge.readPartial(deviceIdentifier, "/" + FakeLibMTP.STORAGE_NAME + "/missing.flac", 0, 16));
    }

    @Test
    public void throwsForDirectory() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        var ex = assertThrows(IOException.class, () -> bridge.readPartial(deviceIdentifier, DIR_PATH, 0, 16));
        assertTrue(ex.getMessage().contains("not a file"));
    }
}
