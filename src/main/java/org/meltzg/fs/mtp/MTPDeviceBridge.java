package org.meltzg.fs.mtp;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.meltzg.fs.mtp.types.MTPDeviceConnection;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPDeviceInfo;
import org.meltzg.fs.mtp.types.MTPItemInfo;
import org.meltzg.fs.mtp.types.MTPTrackMetadata;

public enum MTPDeviceBridge implements Closeable {
    INSTANCE;

    // How long a device scan is trusted before getInstance() re-detects attached devices.
    private static final long DETECT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);

    // How long a cached directory listing is trusted before the device is re-queried. Keeps a
    // full walk fast (every path is re-resolved from the storage root, so the same parents are
    // listed repeatedly within milliseconds) while still letting out-of-band changes on the
    // device surface within a couple of seconds.
    private static final long LISTING_TTL_NANOS = TimeUnit.SECONDS.toNanos(2);

    private ReentrantReadWriteLock connectionLock;
    private Map<MTPDeviceIdentifier, MTPDeviceInfo> deviceInfo;
    private LinkedHashMap<MTPDeviceIdentifier, MTPDeviceConnection> deviceConns;
    // The scan whose opened devices are currently live; held so its native resources outlive the
    // connections and are released together in closeUnsafe(). Null when nothing is open.
    private MtpBackend.Scan currentScan;

    // Short-lived cache of directory listings, scoped to the currently-open connections. Without
    // it, walking a tree of N nodes at depth D issues O(N*D) USB listings because every path is
    // re-resolved from the storage root; with it, each directory is listed at most once per TTL
    // window. Cleared on any mutation and whenever the open connections are torn down.
    private final Map<ListingKey, ChildListing> listingCache = new ConcurrentHashMap<>();

    private record ListingKey(MTPDeviceIdentifier deviceId, String storageId, String parentId) {}

    private record ChildListing(MTPItemInfo[] items, long fetchedNanos) {}

    // Signatures of the devices seen at the last scan, used to detect hot-plug/unplug/reconnect
    // without reopening still-present devices.
    private Set<String> lastSignature = Set.of();
    private volatile boolean devicesDetected = false;
    private volatile long lastDetectNanos = 0L;

    // Allows tests to inject a fake backend without loading native libraries.
    private static volatile MtpBackend backendOverride = null;

    private MTPDeviceBridge() {
        this.connectionLock = new ReentrantReadWriteLock();
        this.deviceInfo = new HashMap<>();
        this.deviceConns = new LinkedHashMap<>();
        this.currentScan = null;
    }

    public Map<MTPDeviceIdentifier, MTPDeviceInfo> getDeviceInfo() {
        return deviceInfo;
    }

    public LinkedHashMap<MTPDeviceIdentifier, MTPDeviceConnection> getDeviceConns() {
        return deviceConns;
    }

    static void setBackend(MtpBackend impl) {
        backendOverride = impl;
    }

    private static MtpBackend backend() {
        var o = backendOverride;
        return o != null ? o : MtpBackend.defaultBackend();
    }

    public static MTPDeviceBridge getInstance() throws IOException {
        INSTANCE.ensureFresh();
        return INSTANCE;
    }

    /**
     * Refreshes the connected-device view, throttled so that the underlying USB scan runs at most
     * once per {@link #DETECT_INTERVAL_NANOS}. Picks up newly attached devices, drops disconnected
     * ones, and reopens devices that were unplugged and replugged.
     */
    public void refresh() throws IOException {
        connectionLock.writeLock().lock();
        try {
            reconcileDevicesUnsafe();
            lastDetectNanos = System.nanoTime();
            devicesDetected = true;
        } finally {
            connectionLock.writeLock().unlock();
        }
    }

    private void ensureFresh() throws IOException {
        if (devicesDetected && System.nanoTime() - lastDetectNanos < DETECT_INTERVAL_NANOS) {
            return; // recently scanned; avoid hammering the USB bus on every operation
        }
        refresh();
    }

    /** Returns the live connection for {@code deviceId}, or throws if it is no longer attached. */
    private MTPDeviceConnection requireConnection(MTPDeviceIdentifier deviceId) throws IOException {
        var conn = deviceConns.get(deviceId);
        if (conn == null) {
            throw new IOException("MTP device is not connected: " + deviceId);
        }
        return conn;
    }

    public MTPFileStore getFileStore(MTPDeviceIdentifier deviceId, String path) throws IOException {
        connectionLock.readLock().lock();
        try {
            var parts = pathParts(path);
            if (parts.length == 0) {
                throw new IllegalArgumentException("First path part required");
            }
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                return getFileStore(conn, parts[0]);
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    public long getCapacity(MTPDeviceIdentifier deviceId, String storageId) throws IOException {
        connectionLock.readLock().lock();
        try {
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                return backend().getCapacity(conn.handle(), storageId);
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    public long getFreeSpace(MTPDeviceIdentifier deviceId, String storageId) throws IOException {
        connectionLock.readLock().lock();
        try {
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                return backend().getFreeSpace(conn.handle(), storageId);
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    /**
     * Resolves a path to its MTPItemInfo.
     * Returns null if path is "/" (device root).
     * For storage-level paths like "/Storage Name", returns a pseudo-item with isFile=false.
     */
    public MTPItemInfo resolveItem(MTPDeviceIdentifier deviceId, String path) throws IOException {
        connectionLock.readLock().lock();
        try {
            var parts = pathParts(path);
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                return resolveItemUnsafe(conn, parts);
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    /** Lists the children of a path. At "/" returns storages as pseudo-directory items. */
    public MTPItemInfo[] listChildren(MTPDeviceIdentifier deviceId, String path) throws IOException {
        connectionLock.readLock().lock();
        try {
            var parts = pathParts(path);
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                return listChildrenUnsafe(conn, parts);
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    public void createDirectory(MTPDeviceIdentifier deviceId, String path) throws IOException {
        connectionLock.readLock().lock();
        try {
            var parts = pathParts(path);
            if (parts.length < 2) {
                throw new IOException("Cannot create directory at device root or storage level: " + path);
            }
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                try {
                    var backend = backend();
                    var storage = backend.findStorage(conn.handle(), parts[0]);
                    if (storage == null) throw new NoSuchFileException("/" + parts[0]);
                    String parentId = MtpBackend.ROOT_PARENT;
                    if (parts.length > 2) {
                        var parentParts = Arrays.copyOf(parts, parts.length - 1);
                        var parentItem = resolveItemUnsafe(conn, parentParts);
                        parentId = parentItem.itemId();
                    }
                    backend.createFolder(conn.handle(), parts[parts.length - 1], parentId, storage.storageId());
                } finally {
                    invalidateListings();
                }
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    public void delete(MTPDeviceIdentifier deviceId, String path) throws IOException {
        connectionLock.readLock().lock();
        try {
            var parts = pathParts(path);
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                try {
                    var item = resolveItemUnsafe(conn, parts);
                    if (item == null) throw new NoSuchFileException(path);
                    backend().deleteObject(conn.handle(), item.itemId());
                } finally {
                    invalidateListings();
                }
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    /**
     * Uploads {@code localFile} to {@code path} on the device, replacing any existing file with
     * the same name. The path must be at least {@code /Storage/file} (cannot write at the device
     * root or storage level). Returns the new item's id.
     */
    public String writeFile(MTPDeviceIdentifier deviceId, String path, java.nio.file.Path localFile) throws IOException {
        connectionLock.readLock().lock();
        try {
            var parts = pathParts(path);
            if (parts.length < 2) {
                throw new IOException("Cannot write a file at device root or storage level: " + path);
            }
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                try {
                    var backend = backend();
                    var storage = backend.findStorage(conn.handle(), parts[0]);
                    if (storage == null) throw new NoSuchFileException("/" + parts[0]);
                    String parentId = MtpBackend.ROOT_PARENT;
                    if (parts.length > 2) {
                        var parentParts = Arrays.copyOf(parts, parts.length - 1);
                        var parentItem = resolveItemUnsafe(conn, parentParts);
                        if (parentItem.isFile()) {
                            throw new NotDirectoryException("/" + String.join("/", parentParts));
                        }
                        parentId = parentItem.itemId();
                    }
                    var name = parts[parts.length - 1];
                    var existing = findChildUnsafe(conn, storage.storageId(), parentId, name);
                    if (existing != null) {
                        if (!existing.isFile()) {
                            throw new IOException("Target exists and is a directory: " + path);
                        }
                        backend.deleteObject(conn.handle(), existing.itemId());
                    }
                    long size = java.nio.file.Files.size(localFile);
                    return backend.sendFile(conn.handle(), localFile.toString(),
                        name, parentId, storage.storageId(), size);
                } finally {
                    invalidateListings();
                }
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    /**
     * Moves {@code sourcePath} to {@code targetPath} on the same device, combining a relocation
     * (when the parent folder changes) with a rename (when the filename changes). Both paths must
     * be at least {@code /Storage/name}. When {@code replace} is false and the target exists, a
     * {@link FileAlreadyExistsException} is thrown.
     */
    public void move(MTPDeviceIdentifier deviceId, String sourcePath, String targetPath, boolean replace) throws IOException {
        connectionLock.readLock().lock();
        try {
            var srcParts = pathParts(sourcePath);
            var tgtParts = pathParts(targetPath);
            if (srcParts.length < 2) {
                throw new IOException("Cannot move the device root or a storage: " + sourcePath);
            }
            if (tgtParts.length < 2) {
                throw new IOException("Cannot move to the device root or storage level: " + targetPath);
            }
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                var backend = backend();
                var source = resolveItemUnsafe(conn, srcParts);

                var tgtStorage = backend.findStorage(conn.handle(), tgtParts[0]);
                if (tgtStorage == null) throw new NoSuchFileException("/" + tgtParts[0]);
                String tgtParentId = MtpBackend.ROOT_PARENT;
                if (tgtParts.length > 2) {
                    var parentParts = Arrays.copyOf(tgtParts, tgtParts.length - 1);
                    var parentItem = resolveItemUnsafe(conn, parentParts);
                    if (parentItem.isFile()) {
                        throw new NotDirectoryException("/" + String.join("/", parentParts));
                    }
                    tgtParentId = parentItem.itemId();
                }

                var tgtName = tgtParts[tgtParts.length - 1];
                var existingTgt = findChildUnsafe(conn, tgtStorage.storageId(), tgtParentId, tgtName);
                if (existingTgt != null) {
                    if (existingTgt.itemId().equals(source.itemId())) {
                        return; // source and target are the same object
                    }
                    if (!replace) throw new FileAlreadyExistsException(targetPath);
                    if (!existingTgt.isFile() && hasChildrenUnsafe(conn, existingTgt)) {
                        throw new DirectoryNotEmptyException(targetPath);
                    }
                }

                // libmtp reports an inconsistent parent_id for storage-root items, so detect a pure
                // rename by comparing parent paths rather than the reported parent handle.
                var srcParentParts = Arrays.copyOf(srcParts, srcParts.length - 1);
                var tgtParentParts = Arrays.copyOf(tgtParts, tgtParts.length - 1);
                boolean sameDirectory = Arrays.equals(srcParentParts, tgtParentParts);

                try {
                    if (existingTgt != null) {
                        backend.deleteObject(conn.handle(), existingTgt.itemId());
                    }
                    if (!sameDirectory) {
                        backend.moveObject(conn.handle(), source.itemId(), tgtStorage.storageId(), tgtParentId);
                    }
                    if (!source.filename().equals(tgtName)) {
                        backend.setFileName(conn.handle(), source.itemId(), tgtName);
                    }
                } catch (IOException nativeError) {
                    // Many devices do not implement MoveObject/SetObjectName; let the caller emulate.
                    throw new MTPOperationUnsupportedException(
                        "Native move failed for " + sourcePath + " -> " + targetPath, nativeError);
                } finally {
                    invalidateListings();
                }
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    /**
     * Reads the audio metadata the device reports for the file at {@code path} through MTP object
     * properties (see {@link MtpBackend#getTrackMetadata}) — a metadata-only exchange that never
     * transfers file content. Returns null when the object is not a file, is not an audio track,
     * or the device reports no metadata for it. Throws {@link NoSuchFileException} when nothing
     * exists at {@code path}.
     */
    public MTPTrackMetadata getTrackMetadata(MTPDeviceIdentifier deviceId, String path) throws IOException {
        connectionLock.readLock().lock();
        try {
            var parts = pathParts(path);
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                var item = resolveItemUnsafe(conn, parts);
                if (item == null || !item.isFile()) return null;
                return backend().getTrackMetadata(conn.handle(), item.itemId());
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    /** Streams the file at {@code path} on the device directly into {@code localFile}. */
    public void getFile(MTPDeviceIdentifier deviceId, String path, java.nio.file.Path localFile) throws IOException {
        connectionLock.readLock().lock();
        try {
            var parts = pathParts(path);
            var conn = requireConnection(deviceId);
            synchronized (conn) {
                var item = resolveItemUnsafe(conn, parts);
                if (item == null) throw new NoSuchFileException(path);
                if (!item.isFile()) throw new IOException(path + " is not a file");
                backend().getFile(conn.handle(), item.itemId(), localFile.toString());
            }
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            connectionLock.writeLock().lock();
            closeUnsafe();
        } finally {
            connectionLock.writeLock().unlock();
        }
    }

    /**
     * Scans currently attached devices and, only if the set has changed since the last scan, tears
     * down the existing connections and reopens from scratch. When the device set is unchanged the
     * open connections are left intact (reopening would needlessly reclaim the USB interface). Caller
     * must hold the write lock.
     */
    private void reconcileDevicesUnsafe() throws IOException {
        var scan = backend().scan();
        boolean keepScan = false;
        try {
            var signature = new HashSet<>(scan.signatures());
            if (signature.equals(lastSignature) && !deviceConns.isEmpty()) {
                return; // nothing changed; keep the live connections (and discard this scan)
            }
            closeUnsafe();
            openDevicesUnsafe(scan);
            currentScan = scan;
            keepScan = true;
            lastSignature = signature;
        } finally {
            if (!keepScan) {
                scan.close();
            }
        }
    }

    private void openDevicesUnsafe(MtpBackend.Scan scan) throws IOException {
        var signatures = scan.signatures();
        for (int i = 0; i < signatures.size(); i++) {
            var opened = scan.open(i);
            if (opened == null) {
                continue;
            }
            var conn = new MTPDeviceConnection(opened.id(), opened.handle());
            deviceConns.put(conn.deviceId(), conn);
            deviceInfo.put(conn.deviceId(), opened.info());
        }
    }

    private void closeUnsafe() {
        var backend = backend();
        for (var conn : deviceConns.values()) {
            backend.releaseDevice(conn.handle());
        }
        if (currentScan != null) {
            currentScan.close();
            currentScan = null;
        }
        deviceInfo.clear();
        deviceConns.clear();
        invalidateListings();
        lastSignature = Set.of();
        devicesDetected = false;
    }

    private MTPFileStore getFileStore(MTPDeviceConnection conn, String storageName) throws IOException {
        var result = backend().findStorage(conn.handle(), storageName);
        if (result == null) {
            throw new NoSuchFileException("/" + storageName);
        }
        return new MTPFileStore(result.name(), conn.deviceId(), result.storageId());
    }

    /**
     * Resolves path parts to an MTPItemInfo.
     * parts=[] → null (device root)
     * parts=["Storage"] → pseudo-item for the storage root directory
     * parts=["Storage","a","b"] → the actual item at Storage/a/b
     */
    private MTPItemInfo resolveItemUnsafe(MTPDeviceConnection conn, String[] parts) throws IOException {
        if (parts.length == 0) return null;
        var backend = backend();
        var storage = backend.findStorage(conn.handle(), parts[0]);
        if (storage == null) throw new NoSuchFileException("/" + parts[0]);
        if (parts.length == 1) {
            return new MTPItemInfo(MtpBackend.ROOT_PARENT, storage.storageId(), storage.storageId(),
                false, 0, 0, parts[0]);
        }
        String storageId = storage.storageId();
        String parentId = MtpBackend.ROOT_PARENT;
        MTPItemInfo found = null;
        for (int i = 1; i < parts.length; i++) {
            var children = cachedChildItems(conn, storageId, parentId);
            final String name = parts[i];
            found = Arrays.stream(children).filter(c -> c.filename().equals(name)).findFirst().orElse(null);
            if (found == null) {
                throw new NoSuchFileException("/" + String.join("/", parts));
            }
            parentId = found.itemId();
        }
        return found;
    }

    private MTPItemInfo[] listChildrenUnsafe(MTPDeviceConnection conn, String[] parts) throws IOException {
        var backend = backend();
        if (parts.length == 0) {
            return backend.listStorages(conn.handle()).stream()
                .map(s -> new MTPItemInfo(MtpBackend.ROOT_PARENT, s.storageId(), s.storageId(),
                    false, 0, 0, s.name()))
                .toArray(MTPItemInfo[]::new);
        }
        var storage = backend.findStorage(conn.handle(), parts[0]);
        if (storage == null) throw new NoSuchFileException("/" + parts[0]);
        String storageId = storage.storageId();
        String parentId = MtpBackend.ROOT_PARENT;
        if (parts.length > 1) {
            var dirItem = resolveItemUnsafe(conn, parts);
            if (dirItem.isFile()) throw new NotDirectoryException("/" + String.join("/", parts));
            parentId = dirItem.itemId();
        }
        return cachedChildItems(conn, storageId, parentId);
    }

    /** Returns true if the given directory item contains any children. */
    private boolean hasChildrenUnsafe(MTPDeviceConnection conn, MTPItemInfo dir) throws IOException {
        return cachedChildItems(conn, dir.storageId(), dir.itemId()).length > 0;
    }

    /** Returns the named child of (storageId, parentId), or null if no such child exists. */
    private MTPItemInfo findChildUnsafe(MTPDeviceConnection conn, String storageId, String parentId, String name) throws IOException {
        var children = cachedChildItems(conn, storageId, parentId);
        return Arrays.stream(children).filter(c -> c.filename().equals(name)).findFirst().orElse(null);
    }

    /**
     * Returns the children of {@code (storageId, parentId)}, serving a cached listing when one was
     * fetched within {@link #LISTING_TTL_NANOS}. The cached array is shared with callers, which only
     * ever read it. Callers must hold the read lock and the connection monitor.
     */
    private MTPItemInfo[] cachedChildItems(MTPDeviceConnection conn, String storageId, String parentId) throws IOException {
        var key = new ListingKey(conn.deviceId(), storageId, parentId);
        var cached = listingCache.get(key);
        if (cached != null && System.nanoTime() - cached.fetchedNanos() < LISTING_TTL_NANOS) {
            return cached.items();
        }
        var items = backend().getChildItems(conn.handle(), storageId, parentId);
        listingCache.put(key, new ChildListing(items, System.nanoTime()));
        return items;
    }

    /** Drops every cached listing. Called after any mutation and when connections are torn down. */
    private void invalidateListings() {
        listingCache.clear();
    }

    static String[] pathParts(String path) {
        return Arrays.stream(path.split("/")).filter(p -> !p.isEmpty()).toArray(String[]::new);
    }
}
