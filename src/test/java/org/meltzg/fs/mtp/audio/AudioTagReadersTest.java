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
        assertTrue(AudioTagReaders.isSupported("song.mp3"));
        assertTrue(AudioTagReaders.isSupported("song.m4a"));
        assertTrue(AudioTagReaders.isSupported("song.ogg"));
        assertTrue(AudioTagReaders.isSupported("song.opus"));
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
    public void readsMp4TagsThroughDispatcher() throws IOException {
        var mp4 = SyntheticMp4.moovLast();
        var tags = AudioTagReaders.read("song.m4a", RangedByteSource.ofArray(mp4), mp4.length);
        assertNotNull(tags);
        assertEquals(SyntheticMp4.TITLE, tags.title());
        assertEquals(SyntheticMp4.DISC, tags.discNumber());
    }

    @Test
    public void returnsNullForUnsupportedExtension() throws IOException {
        var flac = SyntheticFlac.tagsOnly();
        assertNull(AudioTagReaders.read("song.wav", RangedByteSource.ofArray(flac), flac.length));
    }
}
