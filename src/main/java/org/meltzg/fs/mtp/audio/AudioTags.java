package org.meltzg.fs.mtp.audio;

/**
 * Tags parsed from an audio file's own embedded metadata (e.g. a FLAC VORBIS_COMMENT block), as
 * opposed to the {@code mtp} view's device-reported index. String fields are null when the file does
 * not carry them; {@code trackNumber} and {@code durationMillis} are 0 when unknown. The field set
 * matches the {@code mtp} attribute view so the two can be consumed uniformly.
 */
public record AudioTags(
        String title,
        String artist,
        String album,
        String genre,
        int trackNumber,
        int discNumber,
        long durationMillis) {

    public static final AudioTags EMPTY = new AudioTags(null, null, null, null, 0, 0, 0);

    public boolean isEmpty() {
        return title == null && artist == null && album == null && genre == null
            && trackNumber == 0 && discNumber == 0 && durationMillis == 0;
    }
}
