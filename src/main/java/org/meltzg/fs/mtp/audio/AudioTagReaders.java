package org.meltzg.fs.mtp.audio;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Dispatches embedded-tag reading to a per-format reader, selected by filename extension. Each reader
 * works over a {@link RangedByteSource}, so tags come from a small header read rather than a
 * whole-object transfer.
 *
 * <p>Implemented: FLAC ({@link FlacMetadataReader}), MP3 ({@link Mp3MetadataReader}), MP4/M4A
 * ({@link Mp4MetadataReader}). Further formats (Ogg/Opus, WAV) plug in as additional {@code switch}
 * arms as they land.
 */
public final class AudioTagReaders {

    /** Extensions this dispatcher can currently parse tags from. */
    private static final Set<String> SUPPORTED = Set.of("flac", "mp3", "m4a", "m4b", "m4p", "mp4");

    private AudioTagReaders() {}

    /** Whether embedded-tag reading is implemented for {@code filename}'s extension. */
    public static boolean isSupported(String filename) {
        return SUPPORTED.contains(extension(filename));
    }

    /**
     * Parses embedded tags from the file's header via ranged reads. Returns null when the format is
     * unsupported, or when the bytes are not a recognized container for that format. {@code fileSize}
     * is the object's total size, needed by some formats to derive duration (e.g. a CBR MP3 without a
     * Xing header); pass a non-positive value when unknown.
     */
    public static AudioTags read(String filename, RangedByteSource source, long fileSize) throws IOException {
        return switch (extension(filename)) {
            case "flac" -> FlacMetadataReader.readTags(source);
            case "mp3" -> Mp3MetadataReader.readTags(source, fileSize);
            case "m4a", "m4b", "m4p", "mp4" -> Mp4MetadataReader.readTags(source, fileSize);
            default -> null; // unsupported format
        };
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
