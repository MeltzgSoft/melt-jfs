package org.meltzg.fs.mtp;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.meltzg.fs.mtp.audio.SyntheticFlac;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPItemInfo;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for the "audio" attribute view — embedded tags parsed from the file's own bytes through
 * {@link org.meltzg.fs.mtp.audio.AudioTagReaders} and surfaced via {@code Files.readAttributes(path,
 * "audio:...")}. The fake device is seeded with a synthetic FLAC, a plain file and a folder.
 */
public class MTPFileTagsViewTest {

    static final MTPDeviceIdentifier deviceIdentifier = new MTPDeviceIdentifier(
            FakeLibMTP.VENDOR_ID, FakeLibMTP.PRODUCT_ID, FakeLibMTP.SERIAL);

    static final byte[] FLAC = SyntheticFlac.tagsOnly();
    static final String FLAC_PATH = "/" + FakeLibMTP.STORAGE_NAME + "/song.flac";
    static final String TEXT_PATH = "/" + FakeLibMTP.STORAGE_NAME + "/notes.txt";
    static final String DIR_PATH = "/" + FakeLibMTP.STORAGE_NAME + "/Music";

    static FileSystem fs;

    @BeforeClass
    public static void setUpFake() throws IOException {
        var fake = new FakeLibMTP();
        fake.childItems.put(MtpBackend.ROOT_PARENT, new MTPItemInfo[]{
                new MTPItemInfo(MtpBackend.ROOT_PARENT, "100", FakeLibMTP.STORAGE_ID, true, FLAC.length, 0, "song.flac"),
                new MTPItemInfo(MtpBackend.ROOT_PARENT, "101", FakeLibMTP.STORAGE_ID, true, 3, 0, "notes.txt"),
                new MTPItemInfo(MtpBackend.ROOT_PARENT, "102", FakeLibMTP.STORAGE_ID, false, 0, 0, "Music"),
        });
        fake.content.put("100", FLAC);
        fake.content.put("101", new byte[]{'h', 'i', '!'});
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
    public void audioViewReadsEmbeddedTags() throws IOException {
        var attrs = Files.readAttributes(fs.getPath(FLAC_PATH),
                "audio:title,artist,album,genre,trackNumber,discNumber,durationMillis");
        assertEquals(SyntheticFlac.TITLE, attrs.get("title"));
        assertEquals(SyntheticFlac.ARTIST, attrs.get("artist"));
        assertEquals(SyntheticFlac.ALBUM, attrs.get("album"));
        assertEquals(SyntheticFlac.GENRE, attrs.get("genre"));
        assertEquals(SyntheticFlac.TRACK, attrs.get("trackNumber"));
        assertEquals(SyntheticFlac.DISC, attrs.get("discNumber"));
        assertEquals(SyntheticFlac.DURATION_MILLIS, attrs.get("durationMillis"));
    }

    @Test
    public void audioWildcardReturnsAllKeys() throws IOException {
        var attrs = Files.readAttributes(fs.getPath(FLAC_PATH), "audio:*");
        assertEquals(Set.of("title", "artist", "album", "genre", "trackNumber", "discNumber", "durationMillis"),
                attrs.keySet());
    }

    @Test
    public void unsupportedFormatYieldsNullTags() throws IOException {
        var attrs = Files.readAttributes(fs.getPath(TEXT_PATH), "audio:title,album");
        assertNull(attrs.get("title"));
        assertNull(attrs.get("album"));
    }

    @Test
    public void directoryYieldsNullTags() throws IOException {
        var attrs = Files.readAttributes(fs.getPath(DIR_PATH), "audio:title");
        assertNull(attrs.get("title"));
    }

    @Test
    public void audioViewIsAdvertisedAsSupported() {
        assertTrue(fs.supportedFileAttributeViews().contains("audio"));
    }
}
