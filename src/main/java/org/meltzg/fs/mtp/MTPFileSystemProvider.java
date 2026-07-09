package org.meltzg.fs.mtp;

import org.meltzg.fs.mtp.audio.AudioTagReaders;
import org.meltzg.fs.mtp.audio.AudioTags;
import org.meltzg.fs.mtp.audio.RangedByteSource;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPItemInfo;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.regex.Pattern;

public class MTPFileSystemProvider extends FileSystemProvider {
    final Map<MTPDeviceIdentifier, MTPFileSystem> fileSystems = new HashMap<>();

    @Override
    public String getScheme() {
        return "mtp";
    }

    @Override
    public MTPFileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        validateURI(uri);
        synchronized (fileSystems) {
            var deviceIdentifier = getDeviceIdentifier(uri);
            var fileSystem = fileSystems.get(deviceIdentifier);
            if (fileSystem != null) {
                throw new FileSystemAlreadyExistsException(deviceIdentifier.toString());
            }
            fileSystem = new MTPFileSystem(this, deviceIdentifier, env);
            fileSystems.put(deviceIdentifier, fileSystem);
            return fileSystem;
        }
    }

    @Override
    public MTPFileSystem getFileSystem(URI uri) {
        return getFileSystem(uri, false);
    }

    public MTPFileSystem getFileSystem(URI uri, boolean create) {
        validateURI(uri);
        synchronized (fileSystems) {
            var deviceIdentifier = getDeviceIdentifier(uri);
            var fileSystem = fileSystems.get(deviceIdentifier);
            if (fileSystem == null) {
                if (create) {
                    try {
                        fileSystem = newFileSystem(uri, null);
                    } catch (IOException e) {
                        throw (FileSystemNotFoundException) new FileSystemNotFoundException().initCause(e);
                    }
                } else {
                    throw new FileSystemNotFoundException(deviceIdentifier.toString());
                }
            }
            return fileSystem;
        }
    }

    @Override
    public Path getPath(URI uri) {
        validateURI(uri);
        var deviceId = getDeviceIdentifier(uri);
        var schemaSpecificPart = uri.getSchemeSpecificPart();
        var pathPart = schemaSpecificPart.substring(schemaSpecificPart.indexOf(deviceId.toString()) + deviceId.toString().length());
        return getFileSystem(uri, true).getPath(pathPart);
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        validatePathProvider(path);
        for (var opt : options) {
            // A stream is read-only; reject the write-ish options the JDK default also refuses.
            if (opt == StandardOpenOption.WRITE || opt == StandardOpenOption.APPEND) {
                throw new UnsupportedOperationException(opt + " not allowed");
            }
        }
        var deviceId = ((MTPPath) path).getFileSystem().getDeviceIdentifier();
        var absPath = path.toAbsolutePath().toString();

        // A stream is sequential, whole-object consumption (Files.copy/export, transferTo), so pull the
        // object in a single bulk transfer rather than many ranged reads. Random-access callers that
        // want to read only part of a file (e.g. audio tags) use newByteChannel, which is lazy.
        var tempFile = Files.createTempFile("mtp-in-", ".tmp");
        try {
            MTPDeviceBridge.getInstance().getFile(deviceId, absPath, tempFile);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
        return new FilterInputStream(Files.newInputStream(tempFile, StandardOpenOption.READ)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
        };
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        validatePathProvider(path);
        var deviceId = ((MTPPath) path).getFileSystem().getDeviceIdentifier();
        var absPath = path.toAbsolutePath().toString();
        if (options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND)) {
            return newWritableByteChannel(deviceId, absPath, options);
        }
        return newReadableByteChannel(deviceId, absPath);
    }

    private SeekableByteChannel newReadableByteChannel(MTPDeviceIdentifier deviceId, String absPath) throws IOException {
        var bridge = MTPDeviceBridge.getInstance();

        // Preferred path: a lazy, ranged-read channel that fetches only the bytes actually read, so a
        // consumer can pull a file header (e.g. audio tags) without transferring the whole object.
        if (bridge.supportsPartialReads()) {
            var item = bridge.resolveItem(deviceId, absPath); // throws NoSuchFileException when absent
            if (!item.isFile()) throw new IOException(absPath + " is not a file");
            return new MTPLazyReadChannel(bridge, deviceId, absPath, item.filesize(),
                MTPLazyReadChannel.DEFAULT_READ_AHEAD);
        }

        // Fallback for backends without ranged reads (e.g. WPD today): stream the whole device object
        // into a local temp file (no whole-file heap buffering) and serve reads from that.
        var tempFile = Files.createTempFile("mtp-read-", ".tmp");
        try {
            bridge.getFile(deviceId, absPath, tempFile);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
        var surrogate = Files.newByteChannel(tempFile, Set.of(StandardOpenOption.READ));

        return new SeekableByteChannel() {
            @Override public int read(ByteBuffer dst) throws IOException { return surrogate.read(dst); }
            @Override public int write(ByteBuffer src) { throw new NonWritableChannelException(); }
            @Override public long position() throws IOException { return surrogate.position(); }
            @Override public SeekableByteChannel position(long pos) throws IOException { surrogate.position(pos); return this; }
            @Override public long size() throws IOException { return surrogate.size(); }
            @Override public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }
            @Override public boolean isOpen() { return surrogate.isOpen(); }
            @Override public void close() throws IOException {
                try { surrogate.close(); } finally { Files.deleteIfExists(tempFile); }
            }
        };
    }

    private SeekableByteChannel newWritableByteChannel(MTPDeviceIdentifier deviceId, String absPath,
                                                       Set<? extends OpenOption> options) throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        // resolveItem throws NoSuchFileException when the target (or an ancestor) is absent; for a
        // create we only care whether the target itself already exists, so treat absence as null.
        MTPItemInfo existing;
        try {
            existing = bridge.resolveItem(deviceId, absPath);
        } catch (NoSuchFileException e) {
            existing = null;
        }
        if (options.contains(StandardOpenOption.CREATE_NEW) && existing != null) {
            throw new FileAlreadyExistsException(absPath);
        }
        if (existing != null && !existing.isFile()) {
            throw new FileAlreadyExistsException(absPath);
        }

        // Writes are buffered to a local temp file and uploaded to the device on close().
        boolean append = options.contains(StandardOpenOption.APPEND);
        boolean readable = options.contains(StandardOpenOption.READ);
        var tempFile = Files.createTempFile("mtp-write-", ".tmp");
        SeekableByteChannel surrogate;
        try {
            if (append && existing != null) {
                bridge.getFile(deviceId, absPath, tempFile); // seed the buffer with current content
            }
            surrogate = Files.newByteChannel(tempFile, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
            if (append) surrogate.position(surrogate.size());
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        return new SeekableByteChannel() {
            private boolean closed = false;
            @Override public int read(ByteBuffer dst) throws IOException {
                if (!readable) throw new NonReadableChannelException();
                return surrogate.read(dst);
            }
            @Override public int write(ByteBuffer src) throws IOException { return surrogate.write(src); }
            @Override public long position() throws IOException { return surrogate.position(); }
            @Override public SeekableByteChannel position(long pos) throws IOException { surrogate.position(pos); return this; }
            @Override public long size() throws IOException { return surrogate.size(); }
            @Override public SeekableByteChannel truncate(long size) throws IOException { surrogate.truncate(size); return this; }
            @Override public boolean isOpen() { return !closed; }
            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                try {
                    surrogate.close();
                    bridge.writeFile(deviceId, absPath, tempFile);
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
        };
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        validatePathProvider(dir);
        var mtpPath = (MTPPath) dir;
        var deviceId = mtpPath.getFileSystem().getDeviceIdentifier();
        var items = MTPDeviceBridge.getInstance().listChildren(deviceId, dir.toAbsolutePath().toString());
        var fs = mtpPath.getFileSystem();

        var paths = new ArrayList<Path>();
        for (var item : items) {
            var child = (MTPPath) dir.toAbsolutePath().resolve(item.filename());
            if (filter.accept(child)) paths.add(child);
        }

        return new DirectoryStream<>() {
            private boolean iteratorReturned = false;
            private boolean closed = false;
            @Override public Iterator<Path> iterator() {
                if (closed) throw new IllegalStateException("Directory stream is closed");
                if (iteratorReturned) throw new IllegalStateException("Iterator already obtained");
                iteratorReturned = true;
                return paths.iterator();
            }
            @Override public void close() { closed = true; }
        };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        validatePathProvider(dir);
        var mtpPath = (MTPPath) dir;
        var deviceId = mtpPath.getFileSystem().getDeviceIdentifier();
        MTPDeviceBridge.getInstance().createDirectory(deviceId, dir.toAbsolutePath().toString());
    }

    @Override
    public void delete(Path path) throws IOException {
        validatePathProvider(path);
        var mtpPath = (MTPPath) path;
        var deviceId = mtpPath.getFileSystem().getDeviceIdentifier();
        MTPDeviceBridge.getInstance().delete(deviceId, path.toAbsolutePath().toString());
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        validatePathProvider(target);
        boolean replace = Set.of(options).contains(StandardCopyOption.REPLACE_EXISTING);
        if (source instanceof MTPPath src
                && src.getFileSystem().getDeviceIdentifier().equals(((MTPPath) target).getFileSystem().getDeviceIdentifier())
                && src.toAbsolutePath().equals(target.toAbsolutePath())) {
            return; // copying a file to itself has no effect
        }
        if (Files.isDirectory(source)) {
            // Per the NIO contract, copying a directory is non-recursive: it creates the target
            // directory only. Recursive copies are driven by the caller via Files.walkFileTree.
            ensureTargetDirectory(target, replace);
            return;
        }
        copyFile(source, target, replace);
    }

    private void copyFile(Path source, Path target, boolean replace) throws IOException {
        var openOpts = replace
            ? Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            : Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (var in = Files.newInputStream(source);
             var out = Channels.newOutputStream(newByteChannel(target, openOpts))) {
            in.transferTo(out);
        }
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        validatePathProvider(source);
        validatePathProvider(target);
        var opts = Set.of(options);
        boolean replace = opts.contains(StandardCopyOption.REPLACE_EXISTING);
        var srcDevice = ((MTPPath) source).getFileSystem().getDeviceIdentifier();
        var tgtDevice = ((MTPPath) target).getFileSystem().getDeviceIdentifier();
        if (srcDevice.equals(tgtDevice)) {
            try {
                MTPDeviceBridge.getInstance().move(srcDevice,
                    source.toAbsolutePath().toString(), target.toAbsolutePath().toString(), replace);
            } catch (MTPOperationUnsupportedException e) {
                // Device lacks native MoveObject/SetObjectName; emulate move with copy + delete.
                if (opts.contains(StandardCopyOption.ATOMIC_MOVE)) {
                    throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                        "device does not support atomic move");
                }
                emulateMove(source, target, replace);
            }
        } else {
            // Cross-device move cannot be atomic; fall back to copy + delete.
            if (opts.contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                    "atomic move across devices is not supported");
            }
            emulateMove(source, target, replace);
        }
    }

    /**
     * Emulates a move with copy + delete when the device cannot perform it natively. Per the
     * {@link java.nio.file.Files#move} contract this is non-recursive: moving a non-empty directory
     * would require relocating its entries, which this method refuses with
     * {@link DirectoryNotEmptyException}. Callers that want to move a tree should use
     * {@link java.nio.file.Files#walkFileTree} together with {@link #copy}.
     */
    private void emulateMove(Path source, Path target, boolean replace) throws IOException {
        if (Files.isDirectory(source)) {
            if (!isEmptyDirectory(source)) {
                throw new DirectoryNotEmptyException(source.toString());
            }
            ensureTargetDirectory(target, replace);
            delete(source);
        } else {
            copyFile(source, target, replace);
            delete(source);
        }
    }

    private boolean isEmptyDirectory(Path dir) throws IOException {
        try (var children = newDirectoryStream(dir, p -> true)) {
            return !children.iterator().hasNext();
        }
    }

    /** Creates {@code dir}, applying NIO target-replacement rules to anything already present. */
    private void ensureTargetDirectory(Path dir, boolean replace) throws IOException {
        var deviceId = ((MTPPath) dir).getFileSystem().getDeviceIdentifier();
        MTPItemInfo existing;
        try {
            existing = MTPDeviceBridge.getInstance().resolveItem(deviceId, dir.toAbsolutePath().toString());
        } catch (NoSuchFileException e) {
            existing = null;
        }
        if (existing == null) {
            createDirectory(dir);
            return;
        }
        if (!replace) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        if (!existing.isFile() && !isEmptyDirectory(dir)) {
            throw new DirectoryNotEmptyException(dir.toString());
        }
        delete(dir);
        createDirectory(dir);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        validatePathProvider(path);
        validatePathProvider(path2);
        return path.toAbsolutePath().equals(path2.toAbsolutePath());
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        validateURI(path.toUri());
        return MTPDeviceBridge.getInstance().getFileStore(
            ((MTPPath) path).getFileSystem().getDeviceIdentifier(),
            path.toAbsolutePath().toString());
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        validatePathProvider(path);
        var mtpPath = (MTPPath) path;
        var deviceId = mtpPath.getFileSystem().getDeviceIdentifier();
        var absPath = path.toAbsolutePath().toString();
        if (!absPath.equals("/")) {
            var item = MTPDeviceBridge.getInstance().resolveItem(deviceId, absPath);
            if (item == null && !absPath.equals("/")) {
                throw new NoSuchFileException(absPath);
            }
        }
        for (var mode : modes) {
            if (mode == AccessMode.EXECUTE) {
                throw new AccessDeniedException(absPath);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        validatePathProvider(path);
        if (type == null) throw new NullPointerException();
        if (type.isAssignableFrom(BasicFileAttributeView.class)) {
            return (V) new BasicFileAttributeView() {
                @Override public String name() { return "basic"; }
                @Override public BasicFileAttributes readAttributes() throws IOException {
                    return MTPFileSystemProvider.this.readAttributes(path, BasicFileAttributes.class, options);
                }
                @Override public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) {
                    throw new UnsupportedOperationException("MTP filesystem does not support setting file times");
                }
            };
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        validatePathProvider(path);
        if (!type.isAssignableFrom(MTPBasicFileAttributes.class)) {
            throw new UnsupportedOperationException("Attribute type not supported: " + type);
        }
        var mtpPath = (MTPPath) path;
        var deviceId = mtpPath.getFileSystem().getDeviceIdentifier();
        var absPath = path.toAbsolutePath().toString();
        if (absPath.equals("/")) {
            // Device root: synthesise a directory entry
            var rootItem = new org.meltzg.fs.mtp.types.MTPItemInfo(
                MtpBackend.ROOT_PARENT, "/", "/", false, 0, 0, "/");
            return (A) new MTPBasicFileAttributes(rootItem);
        }
        var item = MTPDeviceBridge.getInstance().resolveItem(deviceId, absPath);
        if (item == null) throw new NoSuchFileException(absPath);
        return (A) new MTPBasicFileAttributes(item);
    }

    private static final Set<String> BASIC_ATTRIBUTE_NAMES = Set.of(
        "lastModifiedTime", "lastAccessTime", "creationTime", "isRegularFile",
        "isDirectory", "isSymbolicLink", "isOther", "size", "fileKey");

    private static final Set<String> MTP_ATTRIBUTE_NAMES = Set.of(
        "title", "artist", "album", "genre", "trackNumber", "durationMillis");

    // The "audio" view reads tags from the file's own bytes. It carries the "mtp" fields plus
    // discNumber, which embedded tags expose but the device's MTP track index does not.
    private static final Set<String> AUDIO_ATTRIBUTE_NAMES = Set.of(
        "title", "artist", "album", "genre", "trackNumber", "discNumber", "durationMillis");

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        if (attributes == null) throw new NullPointerException();
        String view = "basic";
        String keys = attributes;
        int colon = attributes.indexOf(':');
        if (colon >= 0) {
            view = attributes.substring(0, colon);
            keys = attributes.substring(colon + 1);
        }
        if (keys.isEmpty()) throw new IllegalArgumentException("No attributes specified");
        return switch (view) {
            case "basic" -> readBasicAttributeMap(path, keys, options);
            case "mtp" -> readTrackMetadataMap(path, keys);
            case "audio" -> readFileTagsMap(path, keys);
            default -> throw new UnsupportedOperationException("View not supported: " + view);
        };
    }

    private Map<String, Object> readBasicAttributeMap(Path path, String keys, LinkOption... options) throws IOException {
        var requested = requestedNames(keys, BASIC_ATTRIBUTE_NAMES);
        var attrs = readAttributes(path, BasicFileAttributes.class, options);
        Map<String, Object> all = new HashMap<>();
        all.put("lastModifiedTime", attrs.lastModifiedTime());
        all.put("lastAccessTime",   attrs.lastAccessTime());
        all.put("creationTime",     attrs.creationTime());
        all.put("isRegularFile",    attrs.isRegularFile());
        all.put("isDirectory",      attrs.isDirectory());
        all.put("isSymbolicLink",   attrs.isSymbolicLink());
        all.put("isOther",          attrs.isOther());
        all.put("size",             attrs.size());
        all.put("fileKey",          attrs.fileKey());
        return selectAttributes(all, requested);
    }

    /**
     * The "mtp" attribute view: audio metadata the device itself reports for a track object (see
     * {@link MTPDeviceBridge#getTrackMetadata}), e.g. {@code Files.readAttributes(path,
     * "mtp:title,artist,album")}. Reading it is a metadata-only exchange — no file content is
     * transferred. Every requested attribute maps to null when the object is not an audio track
     * or the device reports no metadata for it.
     */
    private Map<String, Object> readTrackMetadataMap(Path path, String keys) throws IOException {
        validatePathProvider(path);
        var requested = requestedNames(keys, MTP_ATTRIBUTE_NAMES);
        var deviceId = ((MTPPath) path).getFileSystem().getDeviceIdentifier();
        var meta = MTPDeviceBridge.getInstance().getTrackMetadata(deviceId, path.toAbsolutePath().toString());
        Map<String, Object> all = new HashMap<>();
        all.put("title",          meta == null ? null : meta.title());
        all.put("artist",         meta == null ? null : meta.artist());
        all.put("album",          meta == null ? null : meta.album());
        all.put("genre",          meta == null ? null : meta.genre());
        all.put("trackNumber",    meta == null ? null : meta.trackNumber());
        all.put("durationMillis", meta == null ? null : meta.durationMillis());
        return selectAttributes(all, requested);
    }

    /**
     * The "audio" attribute view: tags parsed from the file's own embedded metadata (e.g. a FLAC
     * VORBIS_COMMENT block) via {@link org.meltzg.fs.mtp.audio.AudioTagReaders}, e.g.
     * {@code Files.readAttributes(path, "audio:title,artist,album")}. Unlike the "mtp" view — which
     * queries the device's index — this reads a small slice of the file's header over ranged reads,
     * so it recovers the real embedded title even on devices that report the filename as the title.
     * Every requested attribute maps to null when the object is not a file, its format is unsupported
     * (see {@code AudioTagReaders}), the backend cannot do ranged reads, or the header is unreadable.
     */
    private Map<String, Object> readFileTagsMap(Path path, String keys) throws IOException {
        validatePathProvider(path);
        var requested = requestedNames(keys, AUDIO_ATTRIBUTE_NAMES);
        var deviceId = ((MTPPath) path).getFileSystem().getDeviceIdentifier();
        var absPath = path.toAbsolutePath().toString();
        var bridge = MTPDeviceBridge.getInstance();

        var item = bridge.resolveItem(deviceId, absPath); // throws NoSuchFileException when absent
        var filename = path.getFileName() == null ? "" : path.getFileName().toString();
        AudioTags tags = null;
        if (item.isFile() && bridge.supportsPartialReads() && AudioTagReaders.isSupported(filename)) {
            RangedByteSource source = (off, n) -> bridge.readPartial(deviceId, absPath, off, n);
            try {
                tags = AudioTagReaders.read(filename, source, item.filesize());
            } catch (IOException unreadable) {
                tags = null; // a corrupt/truncated header yields no tags, not an error
            }
        }
        Map<String, Object> all = new HashMap<>();
        all.put("title",          tags == null ? null : tags.title());
        all.put("artist",         tags == null ? null : tags.artist());
        all.put("album",          tags == null ? null : tags.album());
        all.put("genre",          tags == null ? null : tags.genre());
        all.put("trackNumber",    tags == null ? null : tags.trackNumber());
        all.put("discNumber",     tags == null ? null : tags.discNumber());
        all.put("durationMillis", tags == null ? null : tags.durationMillis());
        return selectAttributes(all, requested);
    }

    private static List<String> requestedNames(String keys, Set<String> valid) {
        if (keys.equals("*")) {
            return new ArrayList<>(valid);
        }
        var requested = Arrays.asList(keys.split(","));
        for (var name : requested) {
            if (!valid.contains(name)) {
                throw new IllegalArgumentException("'" + name + "' not recognized");
            }
        }
        return requested;
    }

    private static Map<String, Object> selectAttributes(Map<String, Object> all, List<String> requested) {
        Map<String, Object> result = new HashMap<>();
        for (var name : requested) result.put(name, all.get(name));
        return result;
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        throw new UnsupportedOperationException("MTP filesystem does not support setAttribute");
    }

    void validateURI(URI uri) {
        var scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase(getScheme())) {
            throw new IllegalArgumentException(String.format("URI scheme is not %s", getScheme()));
        }
        var deviceIdentifier = getDeviceIdentifier(uri);
        try {
            if (!MTPDeviceBridge.getInstance().getDeviceConns().containsKey(deviceIdentifier)) {
                throw new FileSystemNotFoundException(String.format("Device %s could not be found", deviceIdentifier.toString()));
            }
        } catch (IOException e) {
            throw new FileSystemNotFoundException(e.getMessage());
        }
    }

    void validatePathProvider(Path path) {
        if (!(path instanceof MTPPath)) {
            throw new ProviderMismatchException();
        }
    }

    private MTPDeviceIdentifier getDeviceIdentifier(URI uri) {
        var schemeSpecificPart = uri.getSchemeSpecificPart();
        var deviceIdPattern = Pattern.compile(String.format("(?<=//)%s(?=(/|$))", MTPDeviceIdentifier.deviceIdStrPattern));
        var deviceIdMatcher = deviceIdPattern.matcher(schemeSpecificPart);
        if (!deviceIdMatcher.find()) {
            throw new IllegalArgumentException(String.format("Invalid device schema %s", schemeSpecificPart));
        }
        var deviceIdStr = deviceIdMatcher.group();
        return MTPDeviceIdentifier.fromString(deviceIdStr);
    }
}
