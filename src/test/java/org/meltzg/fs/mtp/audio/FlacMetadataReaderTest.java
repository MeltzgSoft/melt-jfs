package org.meltzg.fs.mtp.audio;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Proof for {@link FlacMetadataReader}: it parses tags/duration natively from a header read, produces a
 * tiny stitched stream that jaudiotagger parses, and a fixed whole-header read that truncates an
 * embedded PICTURE block crashes jaudiotagger — the failure the block-walk avoids.
 */
public class FlacMetadataReaderTest {

    private static final int PICTURE_BYTES = 2_000_000;
    private static final int SIMULATED_FIXED_READ = 64 * 1024;

    @BeforeClass
    public static void quietJaudiotagger() {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
    }

    @Test
    public void readTagsParsesFieldsAndDuration() throws IOException {
        var tags = FlacMetadataReader.readTags(RangedByteSource.ofArray(SyntheticFlac.withPicture(PICTURE_BYTES)));
        assertNotNull(tags);
        assertEquals(SyntheticFlac.TITLE, tags.title());
        assertEquals(SyntheticFlac.ARTIST, tags.artist());
        assertEquals(SyntheticFlac.ALBUM, tags.album());
        assertEquals(SyntheticFlac.GENRE, tags.genre());
        assertEquals(SyntheticFlac.TRACK, tags.trackNumber());
        assertEquals(SyntheticFlac.DISC, tags.discNumber());
        assertEquals(SyntheticFlac.DURATION_MILLIS, tags.durationMillis());
    }

    @Test
    public void readTagsReturnsNullForNonFlac() throws IOException {
        assertNull(FlacMetadataReader.readTags(
            RangedByteSource.ofArray("OggS....".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    public void readTagsFromStreamInfoOnlyHasDurationButNoTags() throws IOException {
        var tags = FlacMetadataReader.readTags(RangedByteSource.ofArray(SyntheticFlac.streamInfoOnly()));
        assertNotNull(tags);
        assertNull(tags.title());
        assertEquals(SyntheticFlac.DURATION_MILLIS, tags.durationMillis());
    }

    @Test
    public void describeBlocksReportsLayoutWithoutTransferringPayloads() throws IOException {
        var blocks = FlacMetadataReader.describeBlocks(RangedByteSource.ofArray(SyntheticFlac.withPicture(PICTURE_BYTES)));

        assertEquals(List.of("STREAMINFO", "PICTURE", "VORBIS_COMMENT"),
            blocks.stream().map(FlacMetadataReader.BlockInfo::typeName).toList());
        assertEquals(PICTURE_BYTES, blocks.get(1).length());
        assertTrue("VORBIS_COMMENT must be the last metadata block", blocks.get(2).last());
    }

    @Test
    public void stitchedStreamIsTinyAndParsesTags() throws IOException {
        var minimal = FlacMetadataReader.minimalMetadataStream(
            RangedByteSource.ofArray(SyntheticFlac.withPicture(PICTURE_BYTES)));
        assertNotNull(minimal);
        // The 2 MB picture is gone; we kept only fLaC + STREAMINFO + VORBIS_COMMENT.
        assertTrue("stitched stream should be a few hundred bytes, was " + minimal.length,
            minimal.length < 4_096);

        var tag = readTag(minimal);
        assertEquals(SyntheticFlac.TITLE, tag.getFirst(FieldKey.TITLE));
        assertEquals(SyntheticFlac.ARTIST, tag.getFirst(FieldKey.ARTIST));
        assertEquals(SyntheticFlac.ALBUM, tag.getFirst(FieldKey.ALBUM));
    }

    @Test
    public void fixedPrefixReadTruncatesPictureAndCrashesJaudiotagger() {
        var flac = SyntheticFlac.withPicture(PICTURE_BYTES);
        var truncated = new byte[SIMULATED_FIXED_READ];
        System.arraycopy(flac, 0, truncated, 0, SIMULATED_FIXED_READ);

        // The PICTURE block header declares 2 MB but the bytes end at 64 KB, so the parser reads past
        // end-of-input. jaudiotagger can raise Errors, not just Exceptions — hence Throwable.
        assertThrows(Throwable.class, () -> readTag(truncated));
    }

    @Test
    public void nonFlacSourceReturnsNull() throws IOException {
        var notFlac = "OggS....".getBytes(StandardCharsets.US_ASCII);
        assertNull(FlacMetadataReader.minimalMetadataStream(RangedByteSource.ofArray(notFlac)));
    }

    @Test
    public void streamInfoOnlyFileStitchesValidStream() throws IOException {
        var minimal = FlacMetadataReader.minimalMetadataStream(
            RangedByteSource.ofArray(SyntheticFlac.streamInfoOnly()));
        assertNotNull(minimal);
        // Valid enough for jaudiotagger to read (no tags present -> empty title, not a crash).
        assertEquals("", readTag(minimal).getFirst(FieldKey.TITLE));
    }

    private static org.jaudiotagger.tag.Tag readTag(byte[] flac) throws IOException {
        Path tmp = Files.createTempFile("melt-jfs-flac", ".flac");
        try {
            Files.write(tmp, flac);
            return AudioFileIO.read(tmp.toFile()).getTag();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e); // jaudiotagger's checked read failures surface as an IOException
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
