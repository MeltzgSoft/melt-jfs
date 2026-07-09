package org.meltzg.fs.mtp;

import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPDeviceInfo;
import org.meltzg.fs.mtp.types.MTPItemInfo;
import org.meltzg.fs.mtp.types.MTPTrackMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link MtpBackend} implementation for unit tests. Returns fixed data matching the
 * AK100 II fixture. No native libraries are loaded. Swap in via MTPDeviceBridge.setBackend(new FakeLibMTP()).
 */
class FakeLibMTP implements MtpBackend {

    static final int VENDOR_ID = 16642;       // 0x4102
    static final int PRODUCT_ID = 4497;       // 0x1191
    static final String SERIAL = "F2000018D562F2A412B4";
    static final String FRIENDLY_NAME = "AK100 II";
    static final String MODEL_NAME = "AK100_II";
    static final String MANUFACTURER = "iriver";
    static final String STORAGE_NAME = "Internal storage";
    static final String STORAGE_ID = "65537";       // 0x00010001
    static final long CAPACITY = 512_000_000_000L;   // 512 GB (> 50 GB threshold in MTPFileStoreTest)
    static final long FREE_SPACE = 128_000_000_000L;

    private static final String SIGNATURE = VENDOR_ID + ":" + PRODUCT_ID + ":1:17";

    /** Opaque marker handle; the fake holds no native state. */
    private enum FakeHandle implements DeviceHandle { INSTANCE }

    private static final MTPDeviceIdentifier ID = new MTPDeviceIdentifier(VENDOR_ID, PRODUCT_ID, SERIAL);

    // Tests toggle this to simulate the device being unplugged/replugged.
    volatile boolean devicePresent = true;

    // Tests seed these to expose a directory tree (keyed by parentId; ROOT_PARENT for the storage
    // root) and per-item track metadata (keyed by itemId). Empty by default, preserving the
    // "empty device" the other fixtures assume.
    final Map<String, MTPItemInfo[]> childItems = new HashMap<>();
    final Map<String, MTPTrackMetadata> trackMetadata = new HashMap<>();
    // Per-item raw bytes, keyed by itemId, used to exercise the ranged-read (readPartial) path.
    final Map<String, byte[]> content = new HashMap<>();

    @Override
    public Scan scan() {
        boolean present = devicePresent;
        return new Scan() {
            @Override
            public List<String> signatures() {
                return present ? List.of(SIGNATURE) : List.of();
            }

            @Override
            public OpenedDevice open(int index) {
                var info = new MTPDeviceInfo(ID, FRIENDLY_NAME, MODEL_NAME, MANUFACTURER, 1, 17);
                return new OpenedDevice(ID, info, FakeHandle.INSTANCE);
            }

            @Override
            public void close() {}
        };
    }

    @Override
    public StorageResult findStorage(DeviceHandle device, String storageName) {
        return STORAGE_NAME.equals(storageName) ? new StorageResult(STORAGE_NAME, STORAGE_ID) : null;
    }

    @Override
    public long getCapacity(DeviceHandle device, String storageId) {
        return STORAGE_ID.equals(storageId) ? CAPACITY : -1;
    }

    @Override
    public long getFreeSpace(DeviceHandle device, String storageId) {
        return STORAGE_ID.equals(storageId) ? FREE_SPACE : -1;
    }

    @Override
    public List<StorageResult> listStorages(DeviceHandle device) {
        return List.of(new StorageResult(STORAGE_NAME, STORAGE_ID));
    }

    @Override
    public MTPItemInfo[] getChildItems(DeviceHandle device, String storageId, String parentId) throws IOException {
        return childItems.getOrDefault(parentId, new MTPItemInfo[0]);
    }

    @Override
    public MTPTrackMetadata getTrackMetadata(DeviceHandle device, String itemId) throws IOException {
        return trackMetadata.get(itemId);
    }

    @Override
    public String createFolder(DeviceHandle device, String name, String parentId, String storageId) throws IOException {
        return "1";
    }

    @Override
    public void deleteObject(DeviceHandle device, String itemId) throws IOException {}

    // Counts whole-object transfers, so tests can assert a copy took the bulk path (not ranged reads).
    volatile int getFileCalls = 0;

    @Override
    public void getFile(DeviceHandle device, String itemId, String destPath) throws IOException {
        getFileCalls++;
        var bytes = content.get(itemId);
        // Write seeded content when present; otherwise leave destPath as the (empty) temp file.
        if (bytes != null) Files.write(Path.of(destPath), bytes);
    }

    // Total bytes served through readPartial, so tests can assert a ranged read stayed bounded.
    volatile long partialBytesServed = 0;

    @Override
    public boolean supportsPartialReads() {
        return true;
    }

    @Override
    public byte[] readPartial(DeviceHandle device, String itemId, long offset, int maxBytes) throws IOException {
        var bytes = content.get(itemId);
        if (bytes == null) throw new IOException("no content for id: " + itemId);
        if (offset >= bytes.length) return new byte[0];
        int from = (int) offset;
        int len = Math.min(maxBytes, bytes.length - from);
        var out = new byte[len];
        System.arraycopy(bytes, from, out, 0, len);
        partialBytesServed += len;
        return out;
    }

    @Override
    public String sendFile(DeviceHandle device, String localPath, String filename,
                           String parentId, String storageId, long filesize) throws IOException {
        return "1";
    }

    @Override
    public void moveObject(DeviceHandle device, String itemId, String storageId, String parentId) throws IOException {}

    @Override
    public void setFileName(DeviceHandle device, String itemId, String newName) throws IOException {}

    @Override
    public void releaseDevice(DeviceHandle device) {}
}
