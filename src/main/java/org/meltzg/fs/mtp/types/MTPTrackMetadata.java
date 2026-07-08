package org.meltzg.fs.mtp.types;

/**
 * Audio metadata a device reports for one of its objects through MTP object properties (the PTP
 * Name / Artist / AlbumName / Genre / Track / Duration properties, or their WPD equivalents on
 * Windows). The values come from the device's own media index, so reading them is a metadata-only
 * exchange — no file content is transferred — and they reflect the file's embedded tags as the
 * device last scanned them.
 *
 * <p>String fields the device does not report are {@code null}; {@code trackNumber} and
 * {@code durationMillis} are {@code 0} when unreported.
 */
public record MTPTrackMetadata(
        String title,
        String artist,
        String album,
        String genre,
        int trackNumber,
        long durationMillis) {

    /** True when the device reported nothing at all (backends return null instead of this). */
    public boolean isEmpty() {
        return title == null && artist == null && album == null && genre == null
                && trackNumber == 0 && durationMillis == 0;
    }
}
