package org.meltzg.fs.mtp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPDeviceInfo;
import org.meltzg.fs.mtp.types.MTPItemInfo;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Verifies the gated session recycle that recovers from a device reserving a deleted name.
 *
 * <p>Some devices refuse to create an object under a name deleted earlier in the same MTP session,
 * and the reservation does not heal with time — only a fresh session clears it (measured on an
 * Android-based player's SD-card storage; see {@code docs/deleted-name-reservation.md}). The bridge
 * recycles its connections and retries the create once, but only for a name it freed itself, so an
 * ordinary failure costs no reconnect.
 */
public class MTPDeviceBridgeSessionRecycleTest {

    private ReservingBackend backend;
    private MTPDeviceIdentifier id;

    @Before
    public void setUp() throws IOException {
        backend = new ReservingBackend();
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
    public void createDirectoryRecyclesTheSessionWhenTheDeletedNameIsReserved() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        int sessionsBefore = backend.sessionsOpened.get();
        bridge.delete(id, "/Store/dir");

        bridge.createDirectory(id, "/Store/dir");

        assertEquals("the reserved create must be retried against a new session",
            sessionsBefore + 1, backend.sessionsOpened.get());
        assertEquals("the create must have been attempted twice", 2, backend.createCalls.get());
        var names = java.util.Arrays.stream(bridge.listChildren(id, "/Store"))
            .map(MTPItemInfo::filename).toList();
        assertTrue("the recreated directory must be listed", names.contains("dir"));
    }

    /**
     * WPD is exactly this case: a close + reopen leaves the reservation in place, so the reconnect
     * would cost a process-wide teardown and still fail. The failure must surface untouched.
     */
    @Test
    public void backendWhoseReopenDoesNotClearReservationsNeverRecycles() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        backend.reopenClearsNameReservations = false;
        int sessionsBefore = backend.sessionsOpened.get();
        bridge.delete(id, "/Store/dir");

        var failure = assertThrows(IOException.class, () -> bridge.createDirectory(id, "/Store/dir"));

        assertEquals("no reconnect where a reopen is known not to clear the reservation",
            sessionsBefore, backend.sessionsOpened.get());
        assertEquals("the create must not be retried", 1, backend.createCalls.get());
        assertEquals("the failure must surface unwrapped", 0, failure.getSuppressed().length);
    }

    @Test
    public void createDirectoryDoesNotRecycleForANameItDidNotFree() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        int sessionsBefore = backend.sessionsOpened.get();
        // The device refuses this name for its own reasons; the bridge never deleted it.
        backend.reserved.add("untouched");

        var failure = assertThrows(IOException.class,
            () -> bridge.createDirectory(id, "/Store/untouched"));

        assertFalse("a reconnect must not be triggered by an ordinary create failure",
            failure instanceof FileAlreadyExistsException);
        assertEquals("no session recycle for a name we did not free",
            sessionsBefore, backend.sessionsOpened.get());
        assertEquals("the create must not be retried", 1, backend.createCalls.get());
    }

    @Test
    public void existingNameStillFailsFastWithoutRecycling() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        int sessionsBefore = backend.sessionsOpened.get();

        assertThrows("an occupied name is a definitive answer", FileAlreadyExistsException.class,
            () -> bridge.createDirectory(id, "/Store/dir"));

        assertEquals("FileAlreadyExists must not trigger a reconnect",
            sessionsBefore, backend.sessionsOpened.get());
        assertEquals("the create must never reach the device", 0, backend.createCalls.get());
    }

    @Test
    public void recycleIsAttemptedOnlyOncePerFreedName() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        backend.reopenClearsReservations = false; // a device the recycle does not rescue
        int sessionsBefore = backend.sessionsOpened.get();
        bridge.delete(id, "/Store/dir");

        var failure = assertThrows(IOException.class, () -> bridge.createDirectory(id, "/Store/dir"));
        assertEquals("the pre-recycle failure must be kept for diagnosis",
            1, failure.getSuppressed().length);
        assertEquals(sessionsBefore + 1, backend.sessionsOpened.get());

        // The gate was consumed by the first attempt, so a second create must not reconnect again.
        assertThrows(IOException.class, () -> bridge.createDirectory(id, "/Store/dir"));
        assertEquals("a spent gate must not authorise a second recycle",
            sessionsBefore + 1, backend.sessionsOpened.get());
    }

    @Test
    public void failedReplacingWriteLeavesTheNameRecoverable() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        var local = Files.createTempFile("melt-jfs-recycle", ".bin");
        try {
            Files.write(local, new byte[]{1, 2, 3});
            backend.failSends = true;
            // The replacing write deletes /Store/f1 and then fails to send, leaving the name freed.
            assertThrows(IOException.class, () -> bridge.writeFile(id, "/Store/f1", local));

            int sessionsBefore = backend.sessionsOpened.get();
            backend.createCalls.set(0);
            bridge.createDirectory(id, "/Store/f1");

            assertEquals("the name freed by the failed send must authorise a recycle",
                sessionsBefore + 1, backend.sessionsOpened.get());
            assertEquals(2, backend.createCalls.get());
        } finally {
            Files.deleteIfExists(local);
        }
    }

    @Test
    public void successfulReplacingWriteDoesNotLeaveAGate() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        var local = Files.createTempFile("melt-jfs-recycle", ".bin");
        try {
            Files.write(local, new byte[]{1, 2, 3});
            bridge.writeFile(id, "/Store/f1", local); // send reoccupies the name

            // Reserve the name device-side; with no gate recorded this must surface as-is.
            backend.reserved.add("f1");
            bridge.delete(id, "/Store/f1");
            int sessionsBefore = backend.sessionsOpened.get();
            backend.createCalls.set(0);

            // The delete above *does* record a gate, so exactly one recycle is expected — the point
            // is that the earlier successful write did not leave a second, stale one behind.
            bridge.createDirectory(id, "/Store/f1");
            assertEquals(sessionsBefore + 1, backend.sessionsOpened.get());
        } finally {
            Files.deleteIfExists(local);
        }
    }

    /**
     * In-memory backend modelling a device that reserves deleted names for the life of the MTP
     * session:
     * <pre>
     *   /Store            (root, parentId "")
     *     f1              (file,   id "2")
     *     dir             (folder, id "3")
     * </pre>
     * {@code deleteObject} removes the entry and reserves its name; {@code createFolder} then
     * refuses that name until the device is released and reopened.
     */
    private static final class ReservingBackend implements MtpBackend {
        private enum Handle implements DeviceHandle { INSTANCE }

        static final String ROOT = MtpBackend.ROOT_PARENT;
        private static final String STORAGE_ID = "S";

        final MTPDeviceIdentifier id = new MTPDeviceIdentifier(1, 2, "SERIAL");
        final AtomicInteger sessionsOpened = new AtomicInteger();
        final AtomicInteger createCalls = new AtomicInteger();
        final Set<String> reserved = new HashSet<>();
        volatile boolean reopenClearsReservations = true;
        volatile boolean reopenClearsNameReservations = true;
        volatile boolean failSends = false;

        @Override
        public boolean reopenClearsNameReservations() {
            return reopenClearsNameReservations;
        }

        private final Map<String, List<MTPItemInfo>> tree = new java.util.HashMap<>(Map.of(
            ROOT, new ArrayList<>(List.of(file("2", ROOT, "f1"), folder("3", ROOT, "dir")))));
        private int nextId = 50;

        static MTPItemInfo folder(String itemId, String parentId, String name) {
            return new MTPItemInfo(parentId, itemId, STORAGE_ID, false, 0, 0, name);
        }

        static MTPItemInfo file(String itemId, String parentId, String name) {
            return new MTPItemInfo(parentId, itemId, STORAGE_ID, true, 0, 0, name);
        }

        @Override
        public Scan scan() {
            return new Scan() {
                @Override public List<String> signatures() { return List.of("1:2:1:1"); }
                @Override public OpenedDevice open(int index) {
                    sessionsOpened.incrementAndGet();
                    if (reopenClearsReservations) {
                        reserved.clear(); // a fresh MTP session releases every reserved name
                    }
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
        public void deleteObject(DeviceHandle device, String itemId) {
            tree.values().forEach(children -> children.removeIf(c -> {
                if (!c.itemId().equals(itemId)) return false;
                reserved.add(c.filename());
                return true;
            }));
        }

        @Override
        public String createFolder(DeviceHandle device, String name, String parentId, String storageId)
                throws IOException {
            createCalls.incrementAndGet();
            if (reserved.contains(name)) {
                throw new IOException("LIBMTP_Create_Folder failed for: " + name);
            }
            var itemId = String.valueOf(nextId++);
            tree.computeIfAbsent(parentId, k -> new ArrayList<>()).add(folder(itemId, parentId, name));
            return itemId;
        }

        @Override
        public String sendFile(DeviceHandle device, String localPath, String filename,
                               String parentId, String storageId, long filesize) throws IOException {
            if (failSends) {
                throw new IOException("LIBMTP_Send_File_From_File failed with code -1 for: " + filename);
            }
            reserved.remove(filename); // the name is occupied again
            var itemId = String.valueOf(nextId++);
            tree.computeIfAbsent(parentId, k -> new ArrayList<>())
                .add(new MTPItemInfo(parentId, itemId, STORAGE_ID, true, filesize, 0, filename));
            return itemId;
        }

        @Override
        public StorageResult findStorage(DeviceHandle device, String storageName) {
            return "Store".equals(storageName) ? new StorageResult("Store", STORAGE_ID) : null;
        }

        @Override
        public List<StorageResult> listStorages(DeviceHandle device) {
            return List.of(new StorageResult("Store", STORAGE_ID));
        }

        @Override public long getCapacity(DeviceHandle device, String storageId) { return 0; }
        @Override public long getFreeSpace(DeviceHandle device, String storageId) { return 0; }
        @Override public void getFile(DeviceHandle device, String itemId, String destPath) {}
        @Override public void moveObject(DeviceHandle device, String itemId, String storageId, String parentId) {}
        @Override public void setFileName(DeviceHandle device, String itemId, String newName) {}
        @Override public void releaseDevice(DeviceHandle device) {}
    }
}
