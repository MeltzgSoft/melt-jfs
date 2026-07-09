package org.meltzg.fs.mtp.audio;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/** Unit tests for the format dispatcher {@link AudioTagReaders}. */
public class AudioTagReadersTest {

    @Test
    public void implementedFormatsAreSupportedCaseInsensitively() {
        assertTrue(AudioTagReaders.isSupported("song.flac"));
        assertTrue(AudioTagReaders.isSupported("SONG.FLAC"));
    }

    @Test
    public void unimplementedFormatsAreNotSupported() {
        assertFalse(AudioTagReaders.isSupported("song.wma"));
        assertFalse(AudioTagReaders.isSupported("song.aac"));
        assertFalse(AudioTagReaders.isSupported("noextension"));
    }

    @Test
    public void readsFlacTagsThroughDispatcher() throws IOException {
        var flac = SyntheticFlac.tagsOnly();
        var tags = AudioTagReaders.read("song.flac", RangedByteSource.ofArray(flac), flac.length);
        assertNotNull(tags);
        assertEquals(SyntheticFlac.TITLE, tags.title());
        assertEquals(SyntheticFlac.ALBUM, tags.album());
    }

    @Test
    public void returnsNullForUnsupportedExtension() throws IOException {
        var flac = SyntheticFlac.tagsOnly();
        assertNull(AudioTagReaders.read("song.wav", RangedByteSource.ofArray(flac), flac.length));
    }
}
