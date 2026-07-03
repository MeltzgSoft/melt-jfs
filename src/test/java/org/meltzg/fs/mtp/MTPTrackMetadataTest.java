package org.meltzg.fs.mtp;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPItemInfo;
import org.meltzg.fs.mtp.types.MTPTrackMetadata;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for the track-metadata path — {@link MtpBackend#getTrackMetadata} surfaced through
 * {@link MTPDeviceBridge#getTrackMetadata} and the provider's "mtp" attribute view — against the
 * in-memory fake seeded with one audio track, one plain file and one folder.
 */
public class MTPTrackMetadataTest {

    static final MTPDeviceIdentifier deviceIdentifier = new MTPDeviceIdentifier(
            FakeLibMTP.VENDOR_ID, FakeLibMTP.PRODUCT_ID, FakeLibMTP.SERIAL);

    static final MTPTrackMetadata SONG_METADATA =
            new MTPTrackMetadata("Song Title", "The Artist", "The Album", "Electronic", 7, 240_000);

    static final String SONG_PATH = "/" + FakeLibMTP.STORAGE_NAME + "/song.mp3";
    static final String PLAIN_FILE_PATH = "/" + FakeLibMTP.STORAGE_NAME + "/notes.txt";

    static FileSystem fs;

    @BeforeClass
    public static void setUpFake() throws IOException {
        var fake = new FakeLibMTP();
        fake.childItems.put(MtpBackend.ROOT_PARENT, new MTPItemInfo[]{
                new MTPItemInfo(MtpBackend.ROOT_PARENT, "100", FakeLibMTP.STORAGE_ID, true, 4_000_000, 0, "song.mp3"),
                new MTPItemInfo(MtpBackend.ROOT_PARENT, "101", FakeLibMTP.STORAGE_ID, true, 1_000, 0, "notes.txt"),
                new MTPItemInfo(MtpBackend.ROOT_PARENT, "102", FakeLibMTP.STORAGE_ID, false, 0, 0, "Music"),
        });
        fake.trackMetadata.put("100", SONG_METADATA);
        MTPDeviceBridge.setBackend(fake);
        MTPDeviceBridge.INSTANCE.close();
        fs = new MTPFileSystemProvider().newFileSystem(
                URI.create(String.format("mtp://%s/", deviceIdentifier)), null);
    }

    @AfterClass
    public static void removeFake() throws IOException {
        fs.close();
        MTPDeviceBridge.INSTANCE.close();
        MTPDeviceBridge.setBackend(null);
    }

    @Test
    public void bridgeReturnsMetadataForTrack() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        assertEquals(SONG_METADATA, bridge.getTrackMetadata(deviceIdentifier, SONG_PATH));
    }

    @Test
    public void bridgeReturnsNullForFileWithoutMetadata() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        assertNull(bridge.getTrackMetadata(deviceIdentifier, PLAIN_FILE_PATH));
    }

    @Test
    public void bridgeReturnsNullForDirectoriesAndStorages() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        assertNull(bridge.getTrackMetadata(deviceIdentifier, "/" + FakeLibMTP.STORAGE_NAME + "/Music"));
        assertNull(bridge.getTrackMetadata(deviceIdentifier, "/" + FakeLibMTP.STORAGE_NAME));
        assertNull(bridge.getTrackMetadata(deviceIdentifier, "/"));
    }

    @Test
    public void bridgeThrowsForMissingFile() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        assertThrows(NoSuchFileException.class,
                () -> bridge.getTrackMetadata(deviceIdentifier, "/" + FakeLibMTP.STORAGE_NAME + "/missing.mp3"));
    }

    @Test
    public void mtpViewReadsAllAttributes() throws IOException {
        var attrs = Files.readAttributes(fs.getPath(SONG_PATH), "mtp:*");
        assertEquals(Set.of("title", "artist", "album", "genre", "trackNumber", "durationMillis"),
                attrs.keySet());
        assertEquals(SONG_METADATA.title(), attrs.get("title"));
        assertEquals(SONG_METADATA.artist(), attrs.get("artist"));
        assertEquals(SONG_METADATA.album(), attrs.get("album"));
        assertEquals(SONG_METADATA.genre(), attrs.get("genre"));
        assertEquals(SONG_METADATA.trackNumber(), attrs.get("trackNumber"));
        assertEquals(SONG_METADATA.durationMillis(), attrs.get("durationMillis"));
    }

    @Test
    public void mtpViewSelectsRequestedAttributes() throws IOException {
        var attrs = Files.readAttributes(fs.getPath(SONG_PATH), "mtp:title,artist,album");
        assertEquals(Set.of("title", "artist", "album"), attrs.keySet());
    }

    @Test
    public void mtpViewReportsNullsWhenDeviceHasNoMetadata() throws IOException {
        var attrs = Files.readAttributes(fs.getPath(PLAIN_FILE_PATH), "mtp:*");
        assertEquals(Set.of("title", "artist", "album", "genre", "trackNumber", "durationMillis"),
                attrs.keySet());
        assertTrue(attrs.values().stream().allMatch(v -> v == null));
    }

    @Test
    public void mtpViewRejectsUnknownAttribute() {
        assertThrows(IllegalArgumentException.class,
                () -> Files.readAttributes(fs.getPath(SONG_PATH), "mtp:bogus"));
    }

    @Test
    public void supportedViewsIncludeMtp() {
        assertTrue(fs.supportedFileAttributeViews().contains("mtp"));
    }

    @Test
    public void basicViewStillWorksAfterViewDispatch() throws IOException {
        var attrs = Files.readAttributes(fs.getPath(SONG_PATH), "size,isRegularFile");
        assertEquals(4_000_000L, attrs.get("size"));
        assertEquals(true, attrs.get("isRegularFile"));
    }
}
