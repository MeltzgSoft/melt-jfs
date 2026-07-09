package org.meltzg.fs.mtp.audio;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Validates the audio tag readers against <b>real</b> encoder output — fixtures produced by ffmpeg from
 * a CC0 source (see {@code fixtures/README.md}) with known tags. Each reader's result is asserted against
 * those known values, and cross-checked against jaudiotagger (the reference parser) where that version
 * supports the format. Additional formats add their fixture + a case here as they land.
 */
public class RealFixtureTagsTest {

    // The tags baked into the fixtures at generation time.
    private static final String TITLE = "melt-jfs Fixture Title";
    private static final String ARTIST = "melt-jfs Fixture Artist";
    private static final String ALBUM = "melt-jfs Fixture Album";
    private static final String GENRE = "Jazz";
    private static final int TRACK = 7;
    private static final int DISC = 2;
    private static final long DURATION_SECONDS = 4;

    @BeforeClass
    public static void quietJaudiotagger() {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
    }

    @Test
    public void flacFixture() throws Exception {
        assertFixture("fixture.flac", true, DISC);
    }

    /** Reads {@code /fixtures/<name>} with our reader, asserting the known tags; optionally cross-checks jaudiotagger. */
    private void assertFixture(String name, boolean crossCheckReference, int expectedDisc) throws Exception {
        byte[] bytes = load(name);

        var tags = AudioTagReaders.read(name, RangedByteSource.ofArray(bytes), bytes.length);
        assertNotNull("no reader for " + name, tags);

        // Against the values baked into the fixture.
        assertEquals(name + " title", TITLE, tags.title());
        assertEquals(name + " artist", ARTIST, tags.artist());
        assertEquals(name + " album", ALBUM, tags.album());
        assertEquals(name + " genre", GENRE, tags.genre());
        assertEquals(name + " track", TRACK, tags.trackNumber());
        assertEquals(name + " disc", expectedDisc, tags.discNumber());
        assertEquals(name + " duration (s)", DURATION_SECONDS, Math.round(tags.durationMillis() / 1000.0));

        if (!crossCheckReference) return;

        // Against jaudiotagger, the reference parser, reading the same real file.
        Path tmp = Files.createTempFile("melt-jfs-fixture", name.substring(name.lastIndexOf('.')));
        try {
            Files.write(tmp, bytes);
            var audio = AudioFileIO.read(tmp.toFile());
            var ref = audio.getTag();
            // jaudiotagger's WAV INFO reader keeps a trailing NUL on values; trim it so the cross-check
            // compares the same text (a no-op for the other formats, which return clean strings).
            assertEquals(name + " title vs jaudiotagger", trimNul(ref.getFirst(FieldKey.TITLE)), tags.title());
            assertEquals(name + " artist vs jaudiotagger", trimNul(ref.getFirst(FieldKey.ARTIST)), tags.artist());
            assertEquals(name + " album vs jaudiotagger", trimNul(ref.getFirst(FieldKey.ALBUM)), tags.album());
            // jaudiotagger returns the raw field, which for FLAC is the whole "7/12" — take the leading int.
            // Only cross-check when jaudiotagger read a track: it doesn't map WAV's IPRT, though ours does.
            String refTrack = ref.getFirst(FieldKey.TRACK);
            if (refTrack != null && !refTrack.isBlank()) {
                assertEquals(name + " track vs jaudiotagger", leadingInt(refTrack), tags.trackNumber());
            }
            assertEquals(name + " duration vs jaudiotagger (s)",
                audio.getAudioHeader().getTrackLength(), Math.round(tags.durationMillis() / 1000.0));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static int leadingInt(String value) {
        var digits = value.split("/", 2)[0].trim();
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    /** Trims trailing NUL / whitespace (jaudiotagger's WAV INFO values carry a trailing NUL). */
    private static String trimNul(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\0' || Character.isWhitespace(s.charAt(end - 1)))) end--;
        return s.substring(0, end);
    }

    private static byte[] load(String name) throws IOException {
        try (var in = RealFixtureTagsTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull("fixture /fixtures/" + name + " not on the test classpath", in);
            return in.readAllBytes();
        }
    }
}
