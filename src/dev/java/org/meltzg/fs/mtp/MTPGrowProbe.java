package org.meltzg.fs.mtp;

import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPItemInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Measures what a device and the WPD driver actually report after an <b>in-place grow</b> — the one
 * case {@code WpdBackend.overwriteFile} refuses, forcing the Windows-only delete + send fallback that
 * races asynchronous deletes.
 *
 * <p>Reports, after the grow: the device-reported object size, a ranged read (GetPartialObject), and
 * a whole-object read — immediately, after a delay, and after reconnecting the device. A size that
 * heals on reconnect points at the driver's per-connection object cache; one that never heals is the
 * device's own metadata. Either way, if both reads return the full content then the object is intact
 * and only our view of its size is wrong, which the bridge can carry locally the way libmtp serves
 * size from its own cached metadata.
 *
 * <p>This works purely at the {@link MtpBackend} level — no {@code MTPFileSystem}, no provider, no
 * listing cache — so what it prints is what the device and driver say, unfiltered.
 *
 * <p><b>Handle lifetime:</b> {@code MTPDeviceBridge.close()} releases every COM interface pointer it
 * owns, so a {@code DeviceHandle} obtained before a close is dangling afterwards and using one
 * crashes the JVM with an access violation (and corrupts the WPD driver's state along the way).
 * Every phase below therefore re-acquires the bridge, the connection and the handle from scratch, and
 * nothing is ever carried across a close except plain strings.
 *
 * Usage:
 *   ./gradlew growProbe                                  # every device and storage
 *   ./gradlew growProbe --args="FiiO M11 Plus|M11 Plus Micro SD"
 */
public class MTPGrowProbe {

    private static final String SMALL = "first";
    private static final String GROWN = "first-second-and-then-some";

    /** One (device, storage) pair to probe. Only strings and ids — nothing with a lifetime. */
    private record Target(MTPDeviceIdentifier deviceId, String deviceName, String storageName) {}

    public static void main(String[] args) throws Exception {
        String wantDevice = null, wantStorage = null;
        if (args.length > 0 && !args[0].isBlank()) {
            var parts = args[0].split("\\|", 2);
            wantDevice = parts[0].trim();
            if (parts.length > 1) wantStorage = parts[1].trim();
        }

        var backend = MtpBackend.defaultBackend();
        System.out.println("backend: " + backend.getClass().getSimpleName());
        System.out.println("allowInPlaceGrow: " + Boolean.getBoolean("melt.jfs.wpd.allowInPlaceGrow"));
        System.out.println();

        // One connection for the whole run. Repeatedly closing and reopening is what wedges
        // WpdMtpDr — once wedged, IPortableDevice::Open blocks forever with no timeout — so the
        // probe opens the device exactly once and every phase below shares that connection.
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
        var name = "__melt_jfs_growprobe__" + Long.toHexString(System.nanoTime()) + ".bin";
        var small = Files.createTempFile("growprobe-small", ".bin");
        var grown = Files.createTempFile("growprobe-grown", ".bin");
        Files.write(small, SMALL.getBytes(StandardCharsets.UTF_8));
        Files.write(grown, GROWN.getBytes(StandardCharsets.UTF_8));

        try {
            var handle = handleFor(bridge, target);
            if (handle == null) {
                System.out.println("  ABORT: device is not connected");
                return;
            }
            System.out.println("  supportsObjectEditing: " + backend.supportsObjectEditing(handle));

            var storage = backend.findStorage(handle, target.storageName());
            if (storage == null) {
                System.out.println("  ABORT: storage not found");
                return;
            }
            var itemId = backend.sendFile(handle, small.toString(), name,
                MtpBackend.ROOT_PARENT, storage.storageId(), Files.size(small));
            System.out.println("  created " + name + " at " + SMALL.length() + " bytes"
                + "   itemId: " + itemId
                + "   device-reported size: " + reportedSize(backend, handle, target, name));

            System.out.println("  growing in place: " + SMALL.length() + " -> " + GROWN.length() + " bytes");
            try {
                backend.overwriteFile(handle, itemId, grown.toString());
                System.out.println("  overwriteFile: OK (all edit commands accepted)");
            } catch (IOException e) {
                System.out.println("  overwriteFile REFUSED/FAILED: " + e.getMessage());
                return;
            }

            // The reconnect check that used to live here is gone: it cost an open/close cycle per
            // target, and it already answered its question — on the AK100_II the stale size did not
            // heal across a reopen, so this is the device's own metadata, not a driver-side cache.
            report(backend, handle, target, name, itemId, "immediately after the grow");
            sleep(3000);
            report(backend, handle, target, name, itemId, "after 3s");
        } finally {
            Files.deleteIfExists(small);
            Files.deleteIfExists(grown);
            cleanUp(backend, bridge, target, name);
        }
    }

    /** Resolves a live handle from a freshly opened bridge. Never cache the result across a close. */
    private static MtpBackend.DeviceHandle handleFor(MTPDeviceBridge bridge, Target target) {
        var conn = bridge.getDeviceConns().get(target.deviceId());
        return conn == null ? null : conn.handle();
    }

    /**
     * Prints the three views that disagree when a grow half-lands: the size the device/driver
     * reports, what a ranged read returns, and what a whole-object transfer returns.
     */
    private static void report(MtpBackend backend, MtpBackend.DeviceHandle handle, Target target,
                               String name, String itemId, String when) throws IOException {
        long size = reportedSize(backend, handle, target, name);
        System.out.println("  [" + when + "]");
        System.out.println("      device-reported size : " + size
            + (size == GROWN.length() ? "  (correct)" : "  (STALE, expected " + GROWN.length() + ")"));

        try {
            var ranged = backend.readPartial(handle, itemId, 0, GROWN.length());
            System.out.println("      GetPartialObject     : " + ranged.length + " bytes = "
                + quote(new String(ranged, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            System.out.println("      GetPartialObject     : FAILED " + e);
        }

        Path tmp = Files.createTempFile("growprobe-read", ".bin");
        try {
            backend.getFile(handle, itemId, tmp.toString());
            var whole = Files.readAllBytes(tmp);
            System.out.println("      whole-object read    : " + whole.length + " bytes = "
                + quote(new String(whole, StandardCharsets.UTF_8))
                + (Arrays.equals(whole, GROWN.getBytes(StandardCharsets.UTF_8))
                    ? "  (correct)" : "  (WRONG)"));
        } catch (IOException | RuntimeException e) {
            System.out.println("      whole-object read    : FAILED " + e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static long reportedSize(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                     Target target, String name) throws IOException {
        var item = find(backend, handle, target.storageName(), name);
        return item == null ? -1 : item.filesize();
    }

    private static MTPItemInfo find(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                    String storageName, String name) throws IOException {
        var storage = backend.findStorage(handle, storageName);
        if (storage == null) return null;
        for (var item : backend.getChildItems(handle, storage.storageId(), MtpBackend.ROOT_PARENT)) {
            if (item.filename().equals(name)) return item;
        }
        return null;
    }

    /** Removes the probe artifact on the shared connection, whatever happened above. */
    private static void cleanUp(MtpBackend backend, MTPDeviceBridge bridge, Target target, String name) {
        try {
            var handle = handleFor(bridge, target);
            if (handle == null) return;
            var item = find(backend, handle, target.storageName(), name);
            if (item != null) backend.deleteObject(handle, item.itemId());
        } catch (IOException | RuntimeException e) {
            System.out.println("  (cleanup of " + name + " failed: " + e + ")");
        }
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
