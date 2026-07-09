package org.meltzg.fs.mtp.audio;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Unit tests for {@link Mp4MetadataReader} against a spec-authored synthetic MP4. */
public class Mp4MetadataReaderTest {

    @Test
    public void parsesTagsTrackDiscAndDuration() throws IOException {
        var mp4 = SyntheticMp4.moovLast();
        var tags = Mp4MetadataReader.readTags(RangedByteSource.ofArray(mp4), mp4.length);
        assertNotNull(tags);
        assertEquals(SyntheticMp4.TITLE, tags.title());
        assertEquals(SyntheticMp4.ARTIST, tags.artist());
        assertEquals(SyntheticMp4.ALBUM, tags.album());
        assertEquals(SyntheticMp4.GENRE, tags.genre());
        assertEquals(SyntheticMp4.TRACK, tags.trackNumber());
        assertEquals(SyntheticMp4.DISC, tags.discNumber());
        assertEquals(SyntheticMp4.DURATION_MILLIS, tags.durationMillis());
    }

    @Test
    public void findsMoovWhetherItIsBeforeOrAfterMdat() throws IOException {
        // moovLast forces the walk to skip the 5 KB mdat by its size to reach moov.
        var last = Mp4MetadataReader.readTags(RangedByteSource.ofArray(SyntheticMp4.moovLast()), -1);
        var first = Mp4MetadataReader.readTags(RangedByteSource.ofArray(SyntheticMp4.moovFirst()), -1);
        assertNotNull(last);
        assertNotNull(first);
        assertEquals(SyntheticMp4.TITLE, last.title());
        assertEquals(SyntheticMp4.TITLE, first.title());
    }

    @Test
    public void returnsNullWhenNoMoovAtom() throws IOException {
        var notMp4 = "fLaCnot an mp4 container..........".getBytes(StandardCharsets.US_ASCII);
        assertNull(Mp4MetadataReader.readTags(RangedByteSource.ofArray(notMp4), notMp4.length));
    }
}
