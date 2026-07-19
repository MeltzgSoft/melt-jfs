package org.meltzg.fs.mtp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPDeviceInfo;
import org.meltzg.fs.mtp.types.MTPItemInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Verifies the bridge's handling of devices whose MTP database applies deletes asynchronously
 * (observed on an Android-based player's SD-card storage): listings fetched right after a
 * successful DeleteObject can still contain the deleted handle ("ghost"), and a send that reuses a
 * just-deleted filename can be transiently rejected. The same devices keep listing a renamed object
 * under its old filename for a while after a successful SetObjectName. The bridge tombstones deleted
 * ids out of listings until the device catches up, retries the replacing send, and overlays the new
 * filename onto renamed items until the device reports it.
 */
public class MTPDeviceBridgeTombstoneTest {

    private GhostingBackend backend;
    private MTPDeviceIdentifier id;

    @Before
    public void setUp() throws IOException {
        backend = new GhostingBackend();
        id = backend.id;
        MTPDeviceBridge.setBackend(backend);
        MTPDeviceBridge.INSTANCE.close();
    }

    @After
    public void tearDown() throws IOException {
        MTPDeviceBridge.INSTANCE.close();
        MTPDeviceBridge.setBackend(null);
    }

    @Test
    public void deletedItemIsFilteredFromListingsWhileDeviceStillReportsIt() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.delete(id, "/Store/f1");

        assertTrue("backend is still reporting the ghost", backend.listingContains("2"));
        var names = Arrays.stream(bridge.listChildren(id, "/Store"))
            .map(MTPItemInfo::filename).toList();
        assertFalse("deleted file must not be listed", names.contains("f1"));
        assertThrows("deleted file must not resolve", NoSuchFileException.class,
            () -> bridge.resolveItem(id, "/Store/f1"));
    }

    @Test
    public void ghostInAnotherFolderIsFilteredToo() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.delete(id, "/Store/dir/f2");

        // The device also reports the dead handle in a stale listing of a different folder
        // (as happens after a move); it must be suppressed there as well.
        backend.addGhost(GhostingBackend.ROOT, GhostingBackend.file("4", MtpBackend.ROOT_PARENT, "f2"));
        var names = Arrays.stream(bridge.listChildren(id, "/Store"))
            .map(MTPItemInfo::filename).toList();
        assertFalse("dead handle must be filtered from every listing", names.contains("f2"));
    }

    @Test
    public void tombstoneClearsOnceDeviceStopsReportingTheId() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.delete(id, "/Store/f1");
        backend.dropGhosts(); // device catches up

        // The listing of the folder it was deleted from no longer reports the id: the
        // tombstone resolves, so a later object reusing the handle is visible again.
        assertEquals(0, Arrays.stream(bridge.listChildren(id, "/Store"))
            .filter(i -> i.filename().equals("f1")).count());
        backend.addItem(GhostingBackend.ROOT, GhostingBackend.file("2", MtpBackend.ROOT_PARENT, "reborn"));
        bridge.createDirectory(id, "/Store/poke"); // mutation drops the listing cache
        var names = Arrays.stream(bridge.listChildren(id, "/Store"))
            .map(MTPItemInfo::filename).toList();
        assertTrue("a reused handle must be visible after the tombstone resolves", names.contains("reborn"));
    }

    @Test
    public void movedItemIsHiddenFromTheListingItLeftOnly() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.move(id, "/Store/f1", "/Store/dir/f1", false);

        var rootNames = Arrays.stream(bridge.listChildren(id, "/Store"))
            .map(MTPItemInfo::filename).toList();
        assertFalse("moved file must not be listed where it left", rootNames.contains("f1"));
        var dirNames = Arrays.stream(bridge.listChildren(id, "/Store/dir"))
            .map(MTPItemInfo::filename).toList();
        assertTrue("moved file must be listed at its new location", dirNames.contains("f1"));
    }

    @Test
    public void replacingMoveOntoFileIsDelegatedToEmulation() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        assertThrows(MTPOperationUnsupportedException.class,
            () -> bridge.move(id, "/Store/f1", "/Store/dir/f2", true));
        assertTrue("target must not have been deleted by the failed native path",
            backend.listingContains("4"));
    }

    @Test
    public void replacingWriteEditsInPlaceWhenSupported() throws IOException {
        backend.objectEditing = true;
        var bridge = MTPDeviceBridge.getInstance();
        var local = Files.createTempFile("melt-jfs-tombstone", ".bin");
        try {
            Files.write(local, new byte[]{1, 2, 3});
            var newId = bridge.writeFile(id, "/Store/f1", local);
            assertEquals("in-place edit keeps the object's id", "2", newId);
            assertEquals("edited, not re-sent", 0, backend.sendCalls.get());
            assertEquals(List.of("2"), backend.overwrittenIds);
            assertTrue("no delete must have happened", backend.listingContains("2"));
        } finally {
            Files.deleteIfExists(local);
        }
    }

    @Test
    public void failedInPlaceEditFallsBackToDeleteAndSend() throws IOException {
        backend.objectEditing = true;
        backend.failEdits = true;
        var bridge = MTPDeviceBridge.getInstance();
        var local = Files.createTempFile("melt-jfs-tombstone", ".bin");
        try {
            Files.write(local, new byte[]{1});
            var newId = bridge.writeFile(id, "/Store/f1", local);
            assertEquals("fallback re-sends the file", "100", newId);
            assertEquals(1, backend.sendCalls.get());
        } finally {
            Files.deleteIfExists(local);
        }
    }

    @Test
    public void replacingWriteRetriesRejectedSend() throws IOException {
        backend.sendFailuresRemaining = 2;
        var bridge = MTPDeviceBridge.getInstance();
        var local = Files.createTempFile("melt-jfs-tombstone", ".bin");
        try {
            Files.write(local, new byte[]{1, 2, 3});
            var newId = bridge.writeFile(id, "/Store/f1", local); // replaces the existing f1
            assertEquals("send should have succeeded on a retry", "100", newId);
            assertEquals("two rejections then a success", 3, backend.sendCalls.get());
        } finally {
            Files.deleteIfExists(local);
        }
    }

    @Test
    public void renamedItemListsUnderNewNameWhileDeviceReportsOldName() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.move(id, "/Store/f1", "/Store/f1-renamed", false); // pure rename, same directory

        // The device (setFileName while ghosting) still lists the old name; the overlay must
        // present the new one.
        var names = Arrays.stream(bridge.listChildren(id, "/Store"))
            .map(MTPItemInfo::filename).toList();
        assertTrue("renamed file must list under its new name", names.contains("f1-renamed"));
        assertFalse("old name must no longer be listed", names.contains("f1"));
        assertEquals("new name must resolve to the same object", "2",
            bridge.resolveItem(id, "/Store/f1-renamed").itemId());
        assertThrows("old name must not resolve", NoSuchFileException.class,
            () -> bridge.resolveItem(id, "/Store/f1"));
    }

    @Test
    public void moveWithRenameOverlaysNewNameAtDestination() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.move(id, "/Store/f1", "/Store/dir/f1-renamed", false);

        var rootNames = Arrays.stream(bridge.listChildren(id, "/Store"))
            .map(MTPItemInfo::filename).toList();
        assertFalse("moved file must not be listed where it left", rootNames.contains("f1"));
        var dirNames = Arrays.stream(bridge.listChildren(id, "/Store/dir"))
            .map(MTPItemInfo::filename).toList();
        assertTrue("moved file must list under its new name at the destination",
            dirNames.contains("f1-renamed"));
        assertFalse("moved file must not list under its old name at the destination",
            dirNames.contains("f1"));
    }

    @Test
    public void renameOverlayClearsOnceDeviceReportsTheNewName() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.move(id, "/Store/f1", "/Store/f1-renamed", false);
        backend.applyRenames(); // device catches up

        // This fetch sees the device reporting the new name, which resolves the overlay.
        var names = Arrays.stream(bridge.listChildren(id, "/Store"))
            .map(MTPItemInfo::filename).toList();
        assertTrue(names.contains("f1-renamed"));

        // A later device-side rename must now show through: a still-active overlay would pin the
        // filename to "f1-renamed" and hide it.
        backend.renameEntry("2", "external");
        bridge.createDirectory(id, "/Store/poke"); // mutation drops the listing cache
        names = Arrays.stream(bridge.listChildren(id, "/Store"))
            .map(MTPItemInfo::filename).toList();
        assertTrue("resolved overlay must not pin the old overlay name", names.contains("external"));
        assertFalse(names.contains("f1-renamed"));
    }

    @Test
    public void renamedThenDeletedItemIsFullySuppressed() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        bridge.move(id, "/Store/f1", "/Store/f1-renamed", false);
        bridge.delete(id, "/Store/f1-renamed"); // resolves through the overlay

        var names = Arrays.stream(bridge.listChildren(id, "/Store"))
            .map(MTPItemInfo::filename).toList();
        assertFalse("deleted item must not appear under its new name", names.contains("f1-renamed"));
        assertFalse("deleted item must not appear under its old name", names.contains("f1"));
    }

    @Test
    public void freshCreateDoesNotRetryFailedSend() throws IOException {
        backend.sendFailuresRemaining = Integer.MAX_VALUE;
        var bridge = MTPDeviceBridge.getInstance();
        var local = Files.createTempFile("melt-jfs-tombstone", ".bin");
        try {
            Files.write(local, new byte[]{1});
            assertThrows(IOException.class, () -> bridge.writeFile(id, "/Store/brand-new.bin", local));
            assertEquals("a create that replaced nothing must not retry", 1, backend.sendCalls.get());
        } finally {
            Files.deleteIfExists(local);
        }
    }

    /**
     * In-memory backend simulating asynchronous delete propagation:
     * <pre>
     *   /Store            (root, parentId "")
     *     f1              (file,   id "2")
     *     dir             (folder, id "3")
     *       f2            (file,   id "4")
     * </pre>
     * {@code deleteObject} succeeds but leaves the entry in the listings as a ghost until
     * {@link #dropGhosts()}; deleting a ghost again fails like the real device does. Sends fail
     * while {@link #sendFailuresRemaining} is positive.
     */
    private static final class GhostingBackend implements MtpBackend {
        private enum Handle implements DeviceHandle { INSTANCE }

        static final String ROOT = MtpBackend.ROOT_PARENT;
        final MTPDeviceIdentifier id = new MTPDeviceIdentifier(1, 2, "SERIAL");
        final AtomicInteger sendCalls = new AtomicInteger();
        volatile int sendFailuresRemaining = 0;
        volatile boolean objectEditing = false;
        volatile boolean failEdits = false;
        final List<String> overwrittenIds = new ArrayList<>();
        private static final String STORAGE_ID = "S";

        private final Map<String, List<MTPItemInfo>> tree = new java.util.HashMap<>(Map.of(
            ROOT, new ArrayList<>(List.of(file("2", ROOT, "f1"), folder("3", ROOT, "dir"))),
            "3", new ArrayList<>(List.of(file("4", "3", "f2")))));
        private final List<String> deletedIds = new ArrayList<>();
        private volatile boolean ghosting = true;

        static MTPItemInfo folder(String itemId, String parentId, String name) {
            return new MTPItemInfo(parentId, itemId, STORAGE_ID, false, 0, 0, name);
        }

        static MTPItemInfo file(String itemId, String parentId, String name) {
            return new MTPItemInfo(parentId, itemId, STORAGE_ID, true, 0, 0, name);
        }

        boolean listingContains(String itemId) {
            return tree.values().stream().flatMap(List::stream).anyMatch(i -> i.itemId().equals(itemId));
        }

        void addGhost(String parentId, MTPItemInfo item) {
            tree.computeIfAbsent(parentId, k -> new ArrayList<>()).add(item);
        }

        void addItem(String parentId, MTPItemInfo item) {
            deletedIds.remove(item.itemId());
            tree.computeIfAbsent(parentId, k -> new ArrayList<>()).add(item);
        }

        /** The device's database catches up: ghost entries disappear from listings. */
        void dropGhosts() {
            ghosting = false;
            tree.values().forEach(children -> children.removeIf(c -> deletedIds.contains(c.itemId())));
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
            return tree.getOrDefault(parentId, List.of()).toArray(new MTPItemInfo[0]);
        }

        @Override
        public void deleteObject(DeviceHandle device, String itemId) throws IOException {
            if (deletedIds.contains(itemId)) {
                throw new IOException("LIBMTP_Delete_Object failed for id: " + itemId);
            }
            deletedIds.add(itemId);
            if (!ghosting) {
                tree.values().forEach(children -> children.removeIf(c -> c.itemId().equals(itemId)));
            }
        }

        @Override
        public String sendFile(DeviceHandle device, String localPath, String filename,
                               String parentId, String storageId, long filesize) throws IOException {
            sendCalls.incrementAndGet();
            if (sendFailuresRemaining > 0) {
                sendFailuresRemaining--;
                throw new IOException("LIBMTP_Send_File_From_File failed with code -1 for: " + filename);
            }
            return "100";
        }

        @Override
        public boolean supportsObjectEditing(DeviceHandle device) {
            return objectEditing;
        }

        @Override
        public void overwriteFile(DeviceHandle device, String itemId, String localPath) throws IOException {
            if (failEdits) throw new IOException("LIBMTP_BeginEditObject failed for id: " + itemId);
            overwrittenIds.add(itemId);
        }

        @Override
        public void moveObject(DeviceHandle device, String itemId, String storageId, String parentId) {
            var moved = tree.values().stream().flatMap(List::stream)
                .filter(i -> i.itemId().equals(itemId)).findFirst().orElseThrow();
            // Relocate in the tree but keep the stale entry in the listing it left, as the
            // ghosting device's session cache does.
            tree.computeIfAbsent(parentId, k -> new ArrayList<>())
                .add(new MTPItemInfo(parentId, moved.itemId(), moved.storageId(), moved.isFile(),
                    moved.filesize(), moved.modificationDate(), moved.filename()));
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
        public void setFileName(DeviceHandle device, String itemId, String newName) {
            // Like deletes, renames apply to the device's database asynchronously: listings keep
            // reporting the old filename until the device catches up (applyRenames).
            pendingRenames.put(itemId, newName);
            if (!ghosting) {
                applyRenames();
            }
        }

        /** The device's database catches up: renames become visible in listings. */
        void applyRenames() {
            pendingRenames.forEach(this::renameEntry);
            pendingRenames.clear();
        }

        /** Rewrites the filename of {@code itemId} directly in the listings (a device-side rename). */
        void renameEntry(String itemId, String name) {
            tree.values().forEach(children -> children.replaceAll(c -> c.itemId().equals(itemId)
                ? new MTPItemInfo(c.parentId(), c.itemId(), c.storageId(), c.isFile(),
                    c.filesize(), c.modificationDate(), name)
                : c));
        }

        private final Map<String, String> pendingRenames = new java.util.HashMap<>();

        @Override public long getCapacity(DeviceHandle device, String storageId) { return 0; }
        @Override public long getFreeSpace(DeviceHandle device, String storageId) { return 0; }
        @Override public String createFolder(DeviceHandle device, String name, String parentId, String storageId) { return "50"; }
        @Override public void getFile(DeviceHandle device, String itemId, String destPath) {}
        @Override public void releaseDevice(DeviceHandle device) {}
    }
}
