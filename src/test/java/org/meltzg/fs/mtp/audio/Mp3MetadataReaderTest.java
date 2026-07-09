package org.meltzg.fs.mtp.audio;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Proof for {@link Mp3MetadataReader} against a real ID3v2.3 fixture (a ~1s MP3 with a Xing/Info
 * header). Tag fields are cross-checked against jaudiotagger so the assertions can't drift from a
 * reference parser, and duration is checked against jaudiotagger's within a small tolerance.
 */
public class Mp3MetadataReaderTest {

    private static final String FIXTURE = "/fixtures/tagged-track.mp3";

    private static byte[] fixture;
    private static org.jaudiotagger.tag.Tag reference;
    private static int referenceSeconds;

    @BeforeClass
    public static void loadFixture() throws Exception {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
        try (var in = Mp3MetadataReaderTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull("fixture " + FIXTURE + " not on the test classpath", in);
            fixture = in.readAllBytes();
        }
        // jaudiotagger reads from a File; write the fixture out and read it back as the reference.
        Path tmp = Files.createTempFile("melt-jfs-mp3", ".mp3");
        try {
            Files.write(tmp, fixture);
            var audio = AudioFileIO.read(tmp.toFile());
            reference = audio.getTag();
            referenceSeconds = audio.getAudioHeader().getTrackLength();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void parsesTextTagsMatchingReferenceParser() throws IOException {
        var tags = Mp3MetadataReader.readTags(RangedByteSource.ofArray(fixture), fixture.length);
        assertNotNull(tags);
        assertEquals(reference.getFirst(FieldKey.TITLE), tags.title());
        assertEquals(reference.getFirst(FieldKey.ARTIST), tags.artist());
        assertEquals(reference.getFirst(FieldKey.ALBUM), tags.album());
        assertEquals(reference.getFirst(FieldKey.GENRE), tags.genre());
    }

    @Test
    public void parsesTrackNumber() throws IOException {
        var tags = Mp3MetadataReader.readTags(RangedByteSource.ofArray(fixture), fixture.length);
        assertEquals(Integer.parseInt(reference.getFirst(FieldKey.TRACK)), tags.trackNumber());
    }

    @Test
    public void derivesDurationCloseToReferenceParser() throws IOException {
        var tags = Mp3MetadataReader.readTags(RangedByteSource.ofArray(fixture), fixture.length);
        // jaudiotagger reports whole seconds; require our millis to round to the same second (±1s).
        long ourSeconds = Math.round(tags.durationMillis() / 1000.0);
        assertTrue("duration " + tags.durationMillis() + "ms vs reference " + referenceSeconds + "s",
            Math.abs(ourSeconds - referenceSeconds) <= 1 && tags.durationMillis() > 0);
    }

    @Test
    public void nonMp3BytesYieldEmptyTagsNotAnError() throws IOException {
        // No ID3v2 tag and no MPEG frame sync -> empty, never a crash.
        var junk = "not an mp3 at all............".getBytes(StandardCharsets.US_ASCII);
        var tags = Mp3MetadataReader.readTags(RangedByteSource.ofArray(junk), junk.length);
        assertEquals(AudioTags.EMPTY, tags);
    }
}
