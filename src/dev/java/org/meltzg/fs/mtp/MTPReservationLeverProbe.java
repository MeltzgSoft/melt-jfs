package org.meltzg.fs.mtp;

import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks for a <b>lever that clears a deleted-name reservation on WPD</b> — the one real parity gap
 * left in {@code docs/windows-parity.md}.
 *
 * <p>Background, all measured (see {@code docs/deleted-name-reservation.md}): the FiiO M11 Plus Micro
 * SD card refuses to recreate a folder name deleted earlier in the same MTP session. On libmtp a
 * device release + reopen clears it 4/4, which is what {@code MtpBackend.reopenClearsNameReservations}
 * reports and what the bridge's gated session recycle relies on. On WPD the same reopen does
 * <em>not</em> clear it, because WpdMtpDr pools an MTP session across clients: closing a client handle
 * never ends the session the device sees, so nothing on the device gets rebuilt.
 *
 * <p>The standing hypothesis is therefore that the lever must act on the <em>device's</em> session
 * rather than the host's handle. This probe tests that directly, issuing session-level MTP opcodes
 * through the SendCommand pass-through that {@code readPartial} already uses.
 *
 * <h2>Read this before running</h2>
 * <b>The reservation only reproduces on the FiiO M11 Plus Micro SD card.</b> Neither AK100 II storage
 * exhibits it, nor does the FiiO's internal storage. On any of those every recreate succeeds no matter
 * what a lever did, so a "working" lever there is indistinguishable from one that does nothing. The
 * probe refuses to report a lever result unless it first confirms the reservation on that storage.
 *
 * <p><b>The session levers are gated off by default</b> ({@code -Plevers=...}). {@code CloseSession}
 * and {@code ResetDevice} are sent underneath a driver that believes it owns the session, which is
 * unsupported and can desynchronise or wedge WpdMtpDr. A wedged driver makes
 * {@code IPortableDevice::Open} block indefinitely with no timeout for <em>every</em> client of that
 * device, and the recorded recovery is a physical replug — worth knowing before running this against
 * hardware you cannot reach.
 *
 * Usage:
 *   ./gradlew reservationLeverProbe --args="FiiO M11 Plus|M11 Plus Micro SD"
 *   ./gradlew reservationLeverProbe --args="FiiO M11 Plus|M11 Plus Micro SD;recycle,reset,session"
 *   ./gradlew reservationLeverProbe --args=";recycle,force"    # smoke test on any device
 *
 * Levers: recycle (safe control) · reset (MTP ResetDevice) · session (MTP CloseSession+OpenSession)
 *
 * <p>{@code force} is not a lever. It runs the lever path on hardware that does not reserve names, so
 * the harness can be exercised before the affected device is available; every result it yields is
 * printed UNSCORED, because with nothing reserved a recreate succeeds whatever the lever did.
 */
public class MTPReservationLeverProbe {

    /** MTP opcodes. Session-level, and deliberately not constants in the backend. */
    private static final int OP_CLOSE_SESSION = 0x1003, OP_OPEN_SESSION = 0x1002, OP_RESET_DEVICE = 0x1010;
    private static final int MTP_RESPONSE_OK = 0x2001;

    private record Target(MTPDeviceIdentifier deviceId, String deviceName, String storageName) {}

    public static void main(String[] args) throws Exception {
        String wantDevice = null, wantStorage = null;
        var levers = new ArrayList<String>();
        if (args.length > 0 && !args[0].isBlank()) {
            var halves = args[0].split(";", 2);
            var parts = halves[0].split("\\|", 2);
            wantDevice = parts[0].trim();
            if (parts.length > 1) wantStorage = parts[1].trim();
            if (halves.length > 1) {
                for (var l : halves[1].split(",")) if (!l.isBlank()) levers.add(l.trim());
            }
        }
        // "force" is a smoke-test switch, not a lever: it runs the lever path on hardware that does
        // not reserve names, so the harness can be exercised end to end before the affected device is
        // available. Every result it produces is labelled UNSCORED and none of it is evidence.
        boolean force = levers.remove("force");
        if (levers.isEmpty()) levers.add("recycle"); // the safe control only

        var backend = MtpBackend.defaultBackend();
        System.out.println("backend: " + backend.getClass().getSimpleName());
        System.out.println("reopenClearsNameReservations: " + backend.reopenClearsNameReservations());
        System.out.println("levers: " + levers);
        if (force) {
            System.out.println("force: ON — levers will run without a confirmed reservation.");
            System.out.println("       Results are UNSCORED: with nothing reserved, a recreate succeeds");
            System.out.println("       whatever the lever did. This proves the probe runs, nothing more.");
        }
        if (!(backend instanceof WpdBackend) && levers.stream().anyMatch(l -> !l.equals("recycle"))) {
            System.out.println("NOTE: the raw MTP levers are WPD-only; libmtp owns the USB handle and a");
            System.out.println("      plain reopen already clears the reservation there. Running control only.");
            levers.removeIf(l -> !l.equals("recycle"));
        }
        System.out.println();

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
                    probe(backend, bridge, target, levers, force);
                } catch (Exception e) {
                    System.out.println("  PROBE FAILED: " + e);
                    e.printStackTrace(System.out);
                }
                System.out.println();
            }
        } finally {
            MTPDeviceBridge.getInstance().close();
        }
    }

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

    private static void probe(MtpBackend backend, MTPDeviceBridge bridge, Target target,
                              List<String> levers, boolean force) throws IOException {
        // Gate: no confirmed reservation on this storage means no lever result is worth anything.
        var confirm = reserve(backend, bridge, target);
        if (confirm == null) {
            System.out.println("  ABORT: could not set up a reservation attempt.");
            return;
        }
        if (confirm.cleared()) {
            System.out.println("  reservation NOT reproduced on this storage — recreate after delete succeeded.");
            System.out.println("  (only the FiiO M11 Plus Micro SD card is known to reproduce it)");
            if (!force) {
                System.out.println("  ABORT: nothing is reserved here, so a lever result would prove nothing.");
                return;
            }
            System.out.println("  force: continuing anyway; everything below is UNSCORED.");
        } else {
            System.out.println("  reservation confirmed: " + confirm.detail());
        }

        for (var lever : levers) {
            System.out.println("  --- lever: " + lever + " ---");
            var fresh = reserve(backend, bridge, target);
            if (fresh == null) {
                System.out.println("      SKIP: could not establish a fresh reservation");
                continue;
            }
            // The reservation is "overwhelmingly but not perfectly reproducible" (1 run in 5 recreated
            // cleanly), so a stray success here is the device, not the lever. Never score such a run.
            boolean scored = !fresh.cleared();
            if (!scored && !force) {
                System.out.println("      INCONCLUSIVE: the control recreate succeeded before the lever ran");
                continue;
            }

            long start = System.nanoTime();
            try {
                applyLever(backend, bridge, target, lever);
            } catch (IOException | RuntimeException e) {
                System.out.println("      lever FAILED: " + e);
                continue;
            }
            long leverMs = (System.nanoTime() - start) / 1_000_000;

            // The lever may have closed and reopened everything; never reuse a pre-lever handle.
            var handle = handleFor(bridge, target);
            var storage = handle == null ? null : backend.findStorage(handle, target.storageName());
            if (storage == null) {
                System.out.println("      lever ran (" + leverMs + "ms) but the storage is gone afterwards —");
                System.out.println("      the device did not come back. Treat as a wedge, not a fix.");
                continue;
            }
            try {
                backend.createFolder(handle, fresh.name(), MtpBackend.ROOT_PARENT, storage.storageId());
                System.out.println(scored
                    ? "      CLEARED — recreate succeeded after the lever (" + leverMs + "ms)"
                    : "      UNSCORED — recreate succeeded, but nothing was reserved (" + leverMs + "ms)");
                deleteNamed(backend, handle, storage.storageId(), fresh.name());
            } catch (IOException | RuntimeException e) {
                System.out.println((scored ? "      still refused" : "      UNSCORED — failed")
                    + " after the lever (" + leverMs + "ms): " + e);
            }
        }
    }

    /** A name freed on the device, and whether the immediate recreate was refused (the reservation). */
    private record Reservation(String name, boolean cleared, String detail) {}

    /**
     * Creates a folder, deletes it, and tries once to recreate it. Returns with {@code cleared} false
     * when the device refused — i.e. the name is now reserved and a lever has something to act on.
     */
    private static Reservation reserve(MtpBackend backend, MTPDeviceBridge bridge, Target target) {
        var handle = handleFor(bridge, target);
        if (handle == null) return null;
        try {
            var storage = backend.findStorage(handle, target.storageName());
            if (storage == null) return null;
            var name = "__melt_jfs_leverprobe__" + Long.toHexString(System.nanoTime());
            var id = backend.createFolder(handle, name, MtpBackend.ROOT_PARENT, storage.storageId());
            backend.deleteObject(handle, id);
            try {
                backend.createFolder(handle, name, MtpBackend.ROOT_PARENT, storage.storageId());
                deleteNamed(backend, handle, storage.storageId(), name);
                return new Reservation(name, true, "recreate succeeded");
            } catch (IOException | RuntimeException e) {
                return new Reservation(name, false, e.toString());
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("      (reservation setup failed: " + e + ")");
            return null;
        }
    }

    private static void applyLever(MtpBackend backend, MTPDeviceBridge bridge, Target target, String lever)
            throws IOException {
        switch (lever) {
            // Control: the same teardown and reopen the bridge's gated mitigation performs. Spelled
            // close() + refresh() rather than reaching for the bridge's private recycleConnections(),
            // which is deliberately reachable only from createDirectory's gated retry. refresh() does
            // the reopen here rather than no-opping: its device-set short-circuit requires live
            // connections, and close() has just dropped them all.
            // Known to clear the reservation on libmtp and known NOT to on WPD -- so on WPD this
            // coming back "still refused" is the harness working, not a new finding.
            case "recycle" -> {
                bridge.close();
                bridge.refresh();
            }

            // Return the device's MTP stack to a known state. A defined operation, but the driver is
            // not expecting it and may not resynchronise.
            case "reset" -> {
                var handle = requireHandle(bridge, target);
                int rc = ((WpdBackend) backend).sendRawMtpCommand(handle, OP_RESET_DEVICE);
                System.out.println("      ResetDevice MTP response: 0x" + Integer.toHexString(rc)
                    + (rc == MTP_RESPONSE_OK ? " (OK)" : " (NOT OK)"));
            }

            // The direct test of the hypothesis: end the session the device sees, then start a new one.
            // Session id 1 is what the driver would have used; if it disagrees, expect the device to
            // reject subsequent operations until the driver reopens.
            case "session" -> {
                var handle = requireHandle(bridge, target);
                var wpd = (WpdBackend) backend;
                int closed = wpd.sendRawMtpCommand(handle, OP_CLOSE_SESSION);
                System.out.println("      CloseSession MTP response: 0x" + Integer.toHexString(closed));
                int opened = wpd.sendRawMtpCommand(handle, OP_OPEN_SESSION, 1);
                System.out.println("      OpenSession  MTP response: 0x" + Integer.toHexString(opened));
            }

            default -> throw new IllegalArgumentException("unknown lever: " + lever);
        }
    }

    private static MtpBackend.DeviceHandle requireHandle(MTPDeviceBridge bridge, Target target) throws IOException {
        var handle = handleFor(bridge, target);
        if (handle == null) throw new IOException("device is not connected");
        return handle;
    }

    /** Resolves a live handle from the bridge. Never cache one across a close or a recycle. */
    private static MtpBackend.DeviceHandle handleFor(MTPDeviceBridge bridge, Target target) {
        var conn = bridge.getDeviceConns().get(target.deviceId());
        return conn == null ? null : conn.handle();
    }

    private static void deleteNamed(MtpBackend backend, MtpBackend.DeviceHandle handle,
                                    String storageId, String name) {
        try {
            for (var item : backend.getChildItems(handle, storageId, MtpBackend.ROOT_PARENT)) {
                if (item.filename().equals(name)) backend.deleteObject(handle, item.itemId());
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("      (cleanup of " + name + " failed: " + e + ")");
        }
    }
}
