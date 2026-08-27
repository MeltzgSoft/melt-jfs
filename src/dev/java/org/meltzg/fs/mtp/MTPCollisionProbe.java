package org.meltzg.fs.mtp;

import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPItemInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures what a device and the backend actually do when an object is created under a <b>name that
 * is already taken</b> — the case {@code MTPDeviceBridge.createDirectory} normally decides above the
 * SPI, from its cached listing, before any native call.
 *
 * <p>That cached check has a hole (caveat 2 on PR #25): if something outside this session takes the
 * name while a listing is still within its TTL, the check misses it and the create falls through to
 * the driver. What happens then is <em>asserted</em> in {@code docs/windows-parity.md} but has never
 * been measured — libmtp is said to fail with a generic {@code IOException}, WPD to "silently succeed
 * and create a duplicate-named object". This probe settles it by calling {@link MtpBackend} directly,
 * with no bridge and no cache in the way, which is exactly the state the TTL hole leaves us in.
 *
 * <p>Three questions, per (device, storage):
 * <ol>
 *   <li><b>Folder over folder</b> — does {@code createFolder} refuse, return the existing object's id,
 *       or create a second object under the same name?</li>
 *   <li><b>Folder over file</b> — same question where the incumbent is a file, since the device may
 *       only enforce uniqueness within an object format.</li>
 *   <li><b>File over file</b> — the same question for {@code sendFile}, the other create path.</li>
 * </ol>
 * Each reports the outcome, the HRESULT or error text on refusal, and then <b>counts the children
 * actually carrying the name</b>. The count is the part that matters: a create that "succeeds" is
 * only benign if it left one object behind.
 *
 * <p><b>Why the HRESULT matters.</b> A backstop that maps an already-exists failure to
 * {@code FileAlreadyExistsException} can only key off what the driver returns. The deleted-name
 * reservation already surfaces from this same call as a bare {@code E_FAIL} (0x80004005) — so if a
 * duplicate name returns E_FAIL too, the two are indistinguishable at this layer and a mapping keyed
 * on the HRESULT alone would silently convert reservation failures into the wrong exception. This
 * probe prints both codes so they can be compared rather than assumed.
 *
 * <p><b>Reservation phase.</b> The probe also creates a folder, deletes it, and immediately tries to
 * recreate it, to report whether this storage exhibits the deleted-name reservation at all. Only the
 * FiiO M11 Plus Micro SD card is known to (see {@code docs/deleted-name-reservation.md}); everywhere
 * else this prints "not reproduced" and there is nothing for a lever to clear. Run the probe on
 * hardware that reproduces it before drawing any conclusion about a fix.
 *
 * <p>Works at the {@link MtpBackend} level like {@code MTPGrowProbe}, and shares its handle-lifetime
 * rule: never carry a {@code DeviceHandle} across a bridge close.
 *
 * Usage:
 *   ./gradlew collisionProbe                                  # every device and storage
 *   ./gradlew collisionProbe --args="FiiO M11 Plus|M11 Plus Micro SD"
 */
public class MTPCollisionProbe {

    /** One (device, storage) pair to probe. Only strings and ids — nothing with a lifetime. */
    private record Target(MTPDeviceIdentifier deviceId, String deviceName, String storageName) {}

    /** What a create did when the name was already taken. */
    private record Outcome(String verdict, String detail) {}

    public static void main(String[] args) throws Exception {
        String wantDevice = null, wantStorage = null;
        if (args.length > 0 && !args[0].isBlank()) {
            var parts = args[0].split("\\|", 2);
            wantDevice = parts[0].trim();
            if (parts.length > 1) wantStorage = parts[1].trim();
        }

        var backend = MtpBackend.defaultBackend();
        System.out.println("backend: " + backend.getClass().getSimpleName());
        System.out.println("reopenClearsNameReservations: " + backend.reopenClearsNameReservations());
        System.out.println();

        // One connection for the whole run, as in MTPGrowProbe: repeated open/close is what wedges
        // WpdMtpDr, and a wedged Open blocks forever with no timeout.
        var bridge = MTPDeviceBridge.getInstance();
        try {
            var targets = discover(backend, bridge, wantDevice, wantStorage);
            if (targets.isEmpty()) {
                System.out.println("No matching device/storage found.");
                return;
            }
            for (var target : targets) {
                System.out.println("=== " + target.deviceName() + " / " + target.storageName() + " ===");
                try {
                    probe(backend, bridge, target);
                } catch (Exception e) {
                    // Keep going: one wedged storage should not cost us the data for the others.
                    System.out.println("  PROBE FAILED: " + e);
                    e.printStackTrace(System.out);
                }
                System.out.println();
            }
        } finally {
            bridge.close();
        }
    }

    /** Enumerates the (device, storage) pairs to probe on the already-open connection. */
    private static List<Target> discover(MtpBackend backend, MTPDeviceBridge bridge,
                                         String wantDevice, String wantStorage) {
        var targets = new ArrayList<Target>();
        if (bridge.getDeviceConns().isEmpty()) {
            System.out.println("No MTP devices connected.");
            return targets;
        }
        for (var entry : bridge.getDeviceInfo().entrySet()) {
            var id = entry.getKey();
            var name = entry.getValue().description();
            if (wantDevice != null && !name.contains(wantDevice)) continue;
            var conn = bridge.getDeviceConns().get(id);
            if (conn == null) continue;
            for (var storage : backend.listStorages(conn.handle())) {
                if (wantStorage != null && !storage.name().equals(wantStorage)) continue;
                targets.add(new Target(id, name, storage.name()));
            }
        }
        return targets;
    }

    private static void probe(MtpBackend backend, MTPDeviceBridge bridge, Target target) throws IOException {
        var handle = handleFor(bridge, target);
        if (handle == null) {
            System.out.println("  ABORT: device is not connected");
            return;
        }
        var storage = backend.findStorage(handle, target.storageName());
        if (storage == null) {
            System.out.println("  ABORT: storage not found");
            return;
        }
        String storageId = storage.storageId();

        folderOverFolder(backend, handle, storageId);
        folderOverFile(backend, handle, storageId);
        fileOverFile(backend, handle, storageId);
        reservation(backend, handle, storageId);
    }

    /** Create a folder, then create a folder of the same name again. */
    private static void folderOverFolder(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                         String storageId) {
        var name = uniqueName("dir");
        System.out.println("  [folder over folder] " + name);
        try {
            var first = backend.createFolder(handle, name, MtpBackend.ROOT_PARENT, storageId);
            System.out.println("      incumbent folder id  : " + first);
            var outcome = attempt(() -> backend.createFolder(handle, name, MtpBackend.ROOT_PARENT, storageId));
            reportOutcome(backend, handle, storageId, name, outcome, first);
        } catch (IOException e) {
            System.out.println("      SETUP FAILED: " + e);
        } finally {
            deleteAllNamed(backend, handle, storageId, name);
        }
    }

    /** Send a file, then create a folder under that same name. */
    private static void folderOverFile(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                       String storageId) {
        var name = uniqueName("mixed");
        System.out.println("  [folder over file] " + name);
        try {
            var first = sendSmallFile(backend, handle, name, storageId);
            System.out.println("      incumbent file id    : " + first);
            var outcome = attempt(() -> backend.createFolder(handle, name, MtpBackend.ROOT_PARENT, storageId));
            reportOutcome(backend, handle, storageId, name, outcome, first);
        } catch (IOException e) {
            System.out.println("      SETUP FAILED: " + e);
        } finally {
            deleteAllNamed(backend, handle, storageId, name);
        }
    }

    /** Send a file, then send another file under that same name. */
    private static void fileOverFile(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                     String storageId) {
        var name = uniqueName("file");
        System.out.println("  [file over file] " + name);
        try {
            var first = sendSmallFile(backend, handle, name, storageId);
            System.out.println("      incumbent file id    : " + first);
            var outcome = attempt(() -> sendSmallFile(backend, handle, name, storageId));
            reportOutcome(backend, handle, storageId, name, outcome, first);
        } catch (IOException e) {
            System.out.println("      SETUP FAILED: " + e);
        } finally {
            deleteAllNamed(backend, handle, storageId, name);
        }
    }

    /**
     * Does this storage keep a deleted name reserved? Create, delete, recreate immediately. A refusal
     * here is the condition {@code docs/deleted-name-reservation.md} describes; anywhere it recreates
     * cleanly there is nothing for a session-recycle lever to clear, so lever results from such a
     * storage prove nothing.
     */
    private static void reservation(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                    String storageId) {
        var name = uniqueName("resv");
        System.out.println("  [deleted-name reservation] " + name);
        try {
            var first = backend.createFolder(handle, name, MtpBackend.ROOT_PARENT, storageId);
            backend.deleteObject(handle, first);
            var outcome = attempt(() -> backend.createFolder(handle, name, MtpBackend.ROOT_PARENT, storageId));
            if (outcome.detail() == null) {
                System.out.println("      recreate after delete: OK — reservation NOT reproduced here.");
                System.out.println("      (so this storage cannot evaluate a lever: nothing is reserved)");
            } else {
                System.out.println("      recreate after delete: REFUSED — reservation reproduced.");
                System.out.println("      error                : " + outcome.detail());
                System.out.println("      (compare this HRESULT with the collision cases above)");
            }
        } catch (IOException e) {
            System.out.println("      SETUP FAILED: " + e);
        } finally {
            deleteAllNamed(backend, handle, storageId, name);
        }
    }

    /**
     * Prints what the duplicate create did and, crucially, how many objects now carry the name — the
     * difference between a harmless refusal, an idempotent no-op, and a real duplicate.
     */
    private static void reportOutcome(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                      String storageId, String name, Outcome outcome, String incumbentId) {
        if (outcome.detail() != null) {
            System.out.println("      duplicate create     : REFUSED");
            System.out.println("      error                : " + outcome.detail());
        } else {
            String same = incumbentId.equals(outcome.verdict())
                ? "  (SAME id as the incumbent — idempotent)"
                : "  (NEW id — a second object)";
            System.out.println("      duplicate create     : SUCCEEDED, id " + outcome.verdict() + same);
        }
        System.out.println("      objects now named it : " + countNamed(backend, handle, storageId, name));
    }

    /** Runs a create, returning its id or the failure text. Never throws: the outcome is the datum. */
    private static Outcome attempt(Create create) {
        try {
            return new Outcome(create.run(), null);
        } catch (IOException | RuntimeException e) {
            return new Outcome(null, e.toString());
        }
    }

    @FunctionalInterface
    private interface Create {
        String run() throws IOException;
    }

    private static String sendSmallFile(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                        String name, String storageId) throws IOException {
        var local = Files.createTempFile("collisionprobe", ".bin");
        try {
            Files.write(local, "probe".getBytes(StandardCharsets.UTF_8));
            return backend.sendFile(handle, local.toString(), name,
                MtpBackend.ROOT_PARENT, storageId, Files.size(local));
        } finally {
            Files.deleteIfExists(local);
        }
    }

    /** Resolves a live handle from the open bridge. Never cache the result across a close. */
    private static MtpBackend.DeviceHandle handleFor(MTPDeviceBridge bridge, Target target) {
        var conn = bridge.getDeviceConns().get(target.deviceId());
        return conn == null ? null : conn.handle();
    }

    private static int countNamed(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                  String storageId, String name) {
        try {
            int n = 0;
            for (var item : backend.getChildItems(handle, storageId, MtpBackend.ROOT_PARENT)) {
                if (item.filename().equals(name)) n++;
            }
            return n;
        } catch (IOException | RuntimeException e) {
            System.out.println("      (listing failed: " + e + ")");
            return -1;
        }
    }

    /** Removes every object carrying the probe name — there may be more than one, which is the point. */
    private static void deleteAllNamed(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                       String storageId, String name) {
        try {
            for (MTPItemInfo item : backend.getChildItems(handle, storageId, MtpBackend.ROOT_PARENT)) {
                if (!item.filename().equals(name)) continue;
                try {
                    backend.deleteObject(handle, item.itemId());
                } catch (IOException | RuntimeException e) {
                    System.out.println("      (cleanup of " + item.itemId() + " failed: " + e + ")");
                }
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("      (cleanup listing failed: " + e + ")");
        }
    }

    private static String uniqueName(String kind) {
        return "__melt_jfs_collisionprobe_" + kind + "__" + Long.toHexString(System.nanoTime());
    }
}
