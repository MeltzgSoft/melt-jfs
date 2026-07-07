package org.meltzg.fs.mtp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the pure, native-free helpers in {@link NativeLibMTP}. These do not open a device
 * or load libmtp, so they run in the ordinary unit-test source set.
 */
public class NativeLibMTPTest {

    @Test
    public void mapsKnownAudioExtensionsToLibmtpFiletypes() {
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_MP3,  NativeLibMTP.filetypeForFilename("song.mp3"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_FLAC, NativeLibMTP.filetypeForFilename("song.flac"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_M4A,  NativeLibMTP.filetypeForFilename("song.m4a"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_AAC,  NativeLibMTP.filetypeForFilename("song.aac"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_OGG,  NativeLibMTP.filetypeForFilename("song.ogg"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_WAV,  NativeLibMTP.filetypeForFilename("song.wav"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_WMA,  NativeLibMTP.filetypeForFilename("song.wma"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_MP2,  NativeLibMTP.filetypeForFilename("song.mp2"));
    }

    @Test
    public void extensionMatchIsCaseInsensitive() {
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_MP3,  NativeLibMTP.filetypeForFilename("SONG.MP3"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_FLAC, NativeLibMTP.filetypeForFilename("Song.Flac"));
    }

    @Test
    public void usesTheLastExtensionOfMultiDotNames() {
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_MP3, NativeLibMTP.filetypeForFilename("my.album.track.mp3"));
    }

    @Test
    public void nonAudioAndExtensionlessNamesFallBackToUnknown() {
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_UNKNOWN, NativeLibMTP.filetypeForFilename("notes.txt"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_UNKNOWN, NativeLibMTP.filetypeForFilename("photo.jpg"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_UNKNOWN, NativeLibMTP.filetypeForFilename("README"));
        assertEquals(NativeLibMTP.LIBMTP_FILETYPE_UNKNOWN, NativeLibMTP.filetypeForFilename("trailingdot."));
    }
}
