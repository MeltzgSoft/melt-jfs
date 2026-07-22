package org.meltzg.fs.mtp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPDeviceInfo;
import org.meltzg.fs.mtp.types.MTPItemInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Verifies that the bridge caches directory listings so that re-resolving paths from the storage
 * root (as every walk does) does not re-issue the underlying {@code getChildItems} USB call.
 */
public class MTPDeviceBridgeListingCacheTest {

    private CountingTreeBackend backend;
    private MTPDeviceIdentifier id;

    @Before
    public void setUp() throws IOException {
        backend = new CountingTreeBackend();
        id = backend.id;
        MTPDeviceBridge.setBackend(backend);
        MTPDeviceBridge.INSTANCE.close();
    }

    @After
    public void tearDown() throws IOException {
        MTPDeviceBridge.INSTANCE.close();
        MTPDeviceBridge.setBackend(null);
    }

    /**
     * Mirrors what {@code Files.walkFileTree} does: list a directory, then read attributes (resolve
     * from the root) for every child. The tree has three directory levels ("", "1", "3"), so a fully
     * cached walk must issue exactly three {@code getChildItems} calls regardless of how many times
     * each path is re-resolved.
     */
    @Test
    public void walkListsEachDirectoryOnce() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        walk(bridge, "/Store");
        assertEquals("one listing per directory level", 3, backend.getChildItemsCalls.get());
    }

    /** A second identical walk within the TTL window adds no further device listings. */
    @Test
    public void repeatedResolvesAreServedFromCache() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.listChildren(id, "/Store/a");
        int afterFirst = backend.getChildItemsCalls.get();
        bridge.resolveItem(id, "/Store/a/f1");
        bridge.resolveItem(id, "/Store/a/b");
        bridge.listChildren(id, "/Store/a");
        assertEquals("subsequent resolves hit the cache", afterFirst, backend.getChildItemsCalls.get());
    }

    /**
     * A delete patches the cached listing rather than dropping it: the entry disappears without a
     * device round-trip.
     */
    @Test
    public void deletePatchesCachedListingWithoutRefetch() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.listChildren(id, "/Store/a");
        int afterFirst = backend.getChildItemsCalls.get();

        bridge.delete(id, "/Store/a/f1");
        var names = Arrays.stream(bridge.listChildren(id, "/Store/a"))
            .map(MTPItemInfo::filename).toList();
        assertFalse("deleted file must leave the cached listing", names.contains("f1"));
        assertEquals("served from the patched cache, not refetched",
            afterFirst, backend.getChildItemsCalls.get());
    }

    /** A created directory appears in the cached parent listing without a device round-trip. */
    @Test
    public void createDirectoryAppearsInCachedListingWithoutRefetch() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.listChildren(id, "/Store/a");
        int afterFirst = backend.getChildItemsCalls.get();

        bridge.createDirectory(id, "/Store/a/newdir");
        var names = Arrays.stream(bridge.listChildren(id, "/Store/a"))
            .map(MTPItemInfo::filename).toList();
        assertTrue("created directory must appear in the cached listing", names.contains("newdir"));
        assertEquals("served from the patched cache, not refetched",
            afterFirst, backend.getChildItemsCalls.get());
    }

    /** A written file appears in the cached parent listing without a device round-trip. */
    @Test
    public void writeFileAppearsInCachedListingWithoutRefetch() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.listChildren(id, "/Store/a");
        int afterFirst = backend.getChildItemsCalls.get();

        var local = java.nio.file.Files.createTempFile("melt-jfs-cache", ".bin");
        try {
            java.nio.file.Files.write(local, new byte[]{1, 2, 3});
            bridge.writeFile(id, "/Store/a/upload.bin", local);
        } finally {
            java.nio.file.Files.deleteIfExists(local);
        }
        var uploaded = Arrays.stream(bridge.listChildren(id, "/Store/a"))
            .filter(i -> i.filename().equals("upload.bin")).findFirst().orElse(null);
        assertNotNull("written file must appear in the cached listing", uploaded);
        assertEquals("cached entry must carry the written size", 3, uploaded.filesize());
        assertEquals("served from the patched cache, not refetched",
            afterFirst, backend.getChildItemsCalls.get());
    }

    /** A move updates both the cached source and destination listings without a round-trip. */
    @Test
    public void movePatchesSourceAndDestinationListingsWithoutRefetch() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.listChildren(id, "/Store/a");
        bridge.listChildren(id, "/Store/a/b");
        int afterFirst = backend.getChildItemsCalls.get();

        bridge.move(id, "/Store/a/f1", "/Store/a/b/f1", false);
        var srcNames = Arrays.stream(bridge.listChildren(id, "/Store/a"))
            .map(MTPItemInfo::filename).toList();
        var dstNames = Arrays.stream(bridge.listChildren(id, "/Store/a/b"))
            .map(MTPItemInfo::filename).toList();
        assertFalse("moved file must leave the cached source listing", srcNames.contains("f1"));
        assertTrue("moved file must join the cached destination listing", dstNames.contains("f1"));
        assertEquals("served from the patched caches, not refetched",
            afterFirst, backend.getChildItemsCalls.get());
    }

    /** A same-directory rename patches the cached entry's filename without a round-trip. */
    @Test
    public void renamePatchesCachedListingWithoutRefetch() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.listChildren(id, "/Store/a");
        int afterFirst = backend.getChildItemsCalls.get();

        bridge.move(id, "/Store/a/f1", "/Store/a/f1-renamed", false);
        var names = Arrays.stream(bridge.listChildren(id, "/Store/a"))
            .map(MTPItemInfo::filename).toList();
        assertTrue("renamed file must list under its new name", names.contains("f1-renamed"));
        assertFalse("old name must leave the cached listing", names.contains("f1"));
        assertEquals("served from the patched cache, not refetched",
            afterFirst, backend.getChildItemsCalls.get());
    }

    /** Walks the tree the way NIO does: list a directory, then resolve each child's attributes. */
    private void walk(MTPDeviceBridge bridge, String dir) throws IOException {
        for (var child : bridge.listChildren(id, dir)) {
            var childPath = dir + "/" + child.filename();
            var resolved = bridge.resolveItem(id, childPath); // attribute read re-resolves from root
            if (!resolved.isFile()) {
                walk(bridge, childPath);
            }
        }
    }

    /**
     * In-memory backend exposing a fixed tree and counting how many {@code getChildItems} calls
     * reach the device:
     * <pre>
     *   /Store            (root, parentId "")
     *     a               (folder, id "1")
     *       f1            (file,   id "2")
     *       b             (folder, id "3")
     *         f2          (file,   id "4")
     * </pre>
     */
    private static final class CountingTreeBackend implements MtpBackend {
        private enum Handle implements DeviceHandle { INSTANCE }

        final MTPDeviceIdentifier id = new MTPDeviceIdentifier(1, 2, "SERIAL");
        final AtomicInteger getChildItemsCalls = new AtomicInteger();
        private static final String STORAGE_ID = "S";

        // parentId -> children. ROOT_PARENT ("") is the storage root.
        private final Map<String, List<MTPItemInfo>> tree = new java.util.HashMap<>(Map.of(
            ROOT_PARENT, new ArrayList<>(List.of(folder("1", ROOT_PARENT, "a"))),
            "1", new ArrayList<>(List.of(file("2", "1", "f1"), folder("3", "1", "b"))),
            "3", new ArrayList<>(List.of(file("4", "3", "f2")))));

        private static MTPItemInfo folder(String itemId, String parentId, String name) {
            return new MTPItemInfo(parentId, itemId, STORAGE_ID, false, 0, 0, name);
        }

        private static MTPItemInfo file(String itemId, String parentId, String name) {
            return new MTPItemInfo(parentId, itemId, STORAGE_ID, true, 0, 0, name);
        }

        @Override
        public Scan scan() {
            return new Scan() {
                @Override public List<String> signatures() { return List.of("1:2:1:1"); }
                @Override public OpenedDevice open(int index) {
                    var info = new MTPDeviceInfo(id, "dev", "dev", "vendor", 1, 1);
                    return new OpenedDevice(id, info, Handle.INSTANCE);
                }
                @Override public void close() {}
            };
        }

        @Override
        public MTPItemInfo[] getChildItems(DeviceHandle device, String storageId, String parentId) {
            getChildItemsCalls.incrementAndGet();
            return tree.getOrDefault(parentId, List.of()).toArray(new MTPItemInfo[0]);
        }

        @Override
        public StorageResult findStorage(DeviceHandle device, String storageName) {
            return "Store".equals(storageName) ? new StorageResult("Store", STORAGE_ID) : null;
        }

        @Override
        public List<StorageResult> listStorages(DeviceHandle device) {
            return List.of(new StorageResult("Store", STORAGE_ID));
        }

        @Override
        public void deleteObject(DeviceHandle device, String itemId) {
            tree.values().forEach(children -> children.removeIf(c -> c.itemId().equals(itemId)));
        }

        @Override public long getCapacity(DeviceHandle device, String storageId) { return 0; }
        @Override public long getFreeSpace(DeviceHandle device, String storageId) { return 0; }
        @Override public String createFolder(DeviceHandle device, String name, String parentId, String storageId) { return "0"; }
        @Override public void getFile(DeviceHandle device, String itemId, String destPath) {}
        @Override public String sendFile(DeviceHandle device, String localPath, String filename, String parentId, String storageId, long filesize) { return "0"; }
        @Override public void moveObject(DeviceHandle device, String itemId, String storageId, String parentId) {}
        @Override public void setFileName(DeviceHandle device, String itemId, String newName) {}
        @Override public void releaseDevice(DeviceHandle device) {}
    }
}
