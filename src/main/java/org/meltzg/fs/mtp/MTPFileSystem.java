package org.meltzg.fs.mtp;

import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MTPFileSystem extends FileSystem {
    private final MTPFileSystemProvider fileSystemProvider;
    private final MTPDeviceIdentifier deviceIdentifier;
    private volatile boolean open = true;

    public MTPFileSystem(MTPFileSystemProvider fileSystemProvider, MTPDeviceIdentifier deviceIdentifier, Map<String, ?> env) {
        this.fileSystemProvider = fileSystemProvider;
        this.deviceIdentifier = deviceIdentifier;
    }

    public MTPDeviceIdentifier getDeviceIdentifier() {
        return deviceIdentifier;
    }

    @Override
    public FileSystemProvider provider() {
        return fileSystemProvider;
    }

    @Override
    public void close() throws IOException {
        if (open) {
            open = false;
            synchronized (fileSystemProvider.fileSystems) {
                fileSystemProvider.fileSystems.remove(deviceIdentifier);
            }
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return List.of(getPath("/"));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        try {
            var bridge = MTPDeviceBridge.getInstance();
            return bridge.listChildren(deviceIdentifier, "/").length == 0
                ? List.of()
                : List.of(fileSystemProvider.getFileStore(getPath("/")));
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        // "mtp" is the track-metadata view (title/artist/album/genre/trackNumber/durationMillis)
        // read from the device's MTP object properties; see MTPFileSystemProvider.
        return Set.of("basic", "mtp");
    }

    @Override
    public Path getPath(String first, String... more) {
        var sb = new StringBuilder(first);
        for (var segment : more) {
            if (!segment.isEmpty()) {
                if (sb.length() > 0) sb.append("/");
                sb.append(segment);
            }
        }
        return new MTPPath(this, sb.toString());
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        int colon = syntaxAndPattern.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("Missing syntax in: " + syntaxAndPattern);
        String syntax = syntaxAndPattern.substring(0, colon);
        String pattern = syntaxAndPattern.substring(colon + 1);
        return switch (syntax.toLowerCase()) {
            case "glob" -> path -> path.getFileName() != null &&
                FileSystems.getDefault().getPathMatcher("glob:" + pattern)
                    .matches(Path.of(path.getFileName().toString()));
            case "regex" -> path -> path.toString().matches(pattern);
            default -> throw new UnsupportedOperationException("Unsupported syntax: " + syntax);
        };
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("MTP filesystem does not support UserPrincipalLookupService");
    }

    @Override
    public WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException("MTP filesystem does not support WatchService");
    }
}
