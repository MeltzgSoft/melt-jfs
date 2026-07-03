package org.meltzg.fs.mtp;

import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPDeviceInfo;
import org.meltzg.fs.mtp.types.MTPItemInfo;
import org.meltzg.fs.mtp.types.MTPTrackMetadata;

import java.io.IOException;
import java.util.List;

/**
 * Platform-neutral MTP backend. Two concrete implementations exist:
 * <ul>
 *   <li>{@link NativeLibMTP} — libmtp via the Java FFM API (Linux/macOS, and Windows if libmtp is
 *       installed with a WinUSB driver).</li>
 *   <li>{@link WpdBackend} — the Windows Portable Devices (WPD) COM API via FFM, the native way to
 *       talk to MTP devices on Windows without replacing the device driver.</li>
 * </ul>
 *
 * <p>Object identifiers (storage / item / parent ids) are opaque {@link String}s. libmtp's 32-bit
 * handles are rendered as unsigned-decimal strings; WPD object-id strings are used verbatim. Callers
 * must treat them as opaque and never parse or order them.
 */
public interface MtpBackend {

    /**
     * Parent id meaning "the top level of a storage". Each backend translates this to its native
     * notion of a storage root (libmtp's {@code 0xFFFFFFFF}; the storage's functional-object id on
     * WPD). Empty string never collides with a real id, which is always non-empty.
     */
    String ROOT_PARENT = "";

    /** Opaque, backend-specific live handle to an opened device. */
    interface DeviceHandle {}

    /** A device opened during a scan: stable identity, descriptive info, and a live handle. */
    record OpenedDevice(MTPDeviceIdentifier id, MTPDeviceInfo info, DeviceHandle handle) {}

    /** A named storage area on a device. */
    record StorageResult(String name, String storageId) {}

    /**
     * The result of a cheap bus scan. {@link #signatures()} lets the caller detect topology changes
     * without paying to open devices; {@link #open(int)} opens a specific device on demand. The scan
     * holds native resources (e.g. libmtp's raw-device allocation) and must be {@link #close() closed}
     * when no longer needed. Devices opened from it remain valid after the scan is closed.
     */
    interface Scan extends AutoCloseable {
        /** Stable per-device signatures (USB identity on libmtp, PnP device id on WPD). */
        List<String> signatures();

        /** Opens the device at {@code index}, reading its identity and info, or null if it won't open. */
        OpenedDevice open(int index) throws IOException;

        @Override
        void close();
    }

    /** Performs a cheap scan of attached MTP devices. */
    Scan scan() throws IOException;

    List<StorageResult> listStorages(DeviceHandle device);

    StorageResult findStorage(DeviceHandle device, String storageName);

    long getCapacity(DeviceHandle device, String storageId);

    long getFreeSpace(DeviceHandle device, String storageId);

    MTPItemInfo[] getChildItems(DeviceHandle device, String storageId, String parentId) throws IOException;

    /**
     * Reads the audio metadata the device reports for {@code itemId} through MTP object properties —
     * a metadata-only exchange that never transfers file content. Returns null when the object is
     * not an audio track or the device reports no metadata for it (e.g. a file its media indexer
     * has not scanned). The default implementation reports no metadata for anything.
     */
    default MTPTrackMetadata getTrackMetadata(DeviceHandle device, String itemId) throws IOException {
        return null;
    }

    String createFolder(DeviceHandle device, String name, String parentId, String storageId) throws IOException;

    void deleteObject(DeviceHandle device, String itemId) throws IOException;

    /** Streams the device object {@code itemId} directly to the local file {@code destPath}. */
    void getFile(DeviceHandle device, String itemId, String destPath) throws IOException;

    /**
     * Uploads {@code localPath} to the device as a new file named {@code filename} under
     * {@code parentId} on {@code storageId}. Returns the new item's id.
     */
    String sendFile(DeviceHandle device, String localPath, String filename,
                    String parentId, String storageId, long filesize) throws IOException;

    /** Relocates an object to a new {@code parentId} on {@code storageId}, keeping its id. */
    void moveObject(DeviceHandle device, String itemId, String storageId, String parentId) throws IOException;

    /** Renames an object in place. */
    void setFileName(DeviceHandle device, String itemId, String newName) throws IOException;

    void releaseDevice(DeviceHandle device);

    /** Selects the native backend for the current platform. */
    static MtpBackend defaultBackend() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? WpdBackend.getInstance() : NativeLibMTP.getInstance();
    }
}
