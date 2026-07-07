package org.meltzg.fs.mtp;

import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPDeviceInfo;
import org.meltzg.fs.mtp.types.MTPItemInfo;
import org.meltzg.fs.mtp.types.MTPTrackMetadata;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for libmtp. Struct layouts are defined for libmtp 1.1.x on x86-64 Linux.
 * For other platforms, use jextract against the installed libmtp.h to regenerate layouts.
 *
 * <p>libmtp's 32-bit object handles are exposed through the {@link MtpBackend} contract as
 * unsigned-decimal strings; {@link MtpBackend#ROOT_PARENT} maps to libmtp's {@code 0xFFFFFFFF}.
 */
class NativeLibMTP implements MtpBackend {

    static final int LIBMTP_ERROR_NONE = 0;
    static final int LIBMTP_ERROR_NO_DEVICE_ATTACHED = 5;
    static final int LIBMTP_FILES_AND_FOLDERS_ROOT = 0xFFFFFFFF;
    static final int LIBMTP_FILETYPE_FOLDER = 0;
    // Audio filetypes whose enum indices are stable across libmtp 1.1.x (verified against 1.1.21).
    static final int LIBMTP_FILETYPE_WAV = 1;
    static final int LIBMTP_FILETYPE_MP3 = 2;
    static final int LIBMTP_FILETYPE_WMA = 3;
    static final int LIBMTP_FILETYPE_OGG = 4;
    static final int LIBMTP_FILETYPE_AAC = 30;
    static final int LIBMTP_FILETYPE_FLAC = 32;
    static final int LIBMTP_FILETYPE_MP2 = 33;
    static final int LIBMTP_FILETYPE_M4A = 34;
    // Neutral "generic file" type; index of LIBMTP_FILETYPE_UNKNOWN in libmtp 1.1.x's enum.
    static final int LIBMTP_FILETYPE_UNKNOWN = 44;

    // Uploads carry only a filename, so the audio object format is inferred from the extension.
    // Storing a track under a media filetype lets the device index it and expose its tags through
    // getTrackMetadata; anything unrecognised stays LIBMTP_FILETYPE_UNKNOWN (a plain file).
    private static final Map<String, Integer> AUDIO_FILETYPES_BY_EXTENSION = Map.of(
        "mp3",  LIBMTP_FILETYPE_MP3,
        "flac", LIBMTP_FILETYPE_FLAC,
        "m4a",  LIBMTP_FILETYPE_M4A,
        "aac",  LIBMTP_FILETYPE_AAC,
        "ogg",  LIBMTP_FILETYPE_OGG,
        "wav",  LIBMTP_FILETYPE_WAV,
        "wma",  LIBMTP_FILETYPE_WMA,
        "mp2",  LIBMTP_FILETYPE_MP2);

    /** Maps a filename's extension to a libmtp audio filetype, or UNKNOWN when it is not audio. */
    static int filetypeForFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return LIBMTP_FILETYPE_UNKNOWN;
        var ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return AUDIO_FILETYPES_BY_EXTENSION.getOrDefault(ext, LIBMTP_FILETYPE_UNKNOWN);
    }

    /** Live libmtp handle: the opened device plus the raw-device slice it was opened from. */
    private record LibMtpDevice(MemorySegment rawDevice, MemorySegment device) implements DeviceHandle {}

    // Offset of `storage` in LIBMTP_mtpdevice_t:
    //   uint8_t(1) + pad(7) + void*(8) + void*(8) = 24
    private static final long DEVICE_STORAGE_FIELD_OFFSET = 24L;

    // LIBMTP_device_entry_t layout
    private static final StructLayout DEVICE_ENTRY_LAYOUT = MemoryLayout.structLayout(
        ADDRESS.withName("vendor"),
        JAVA_SHORT.withName("vendor_id"),
        MemoryLayout.paddingLayout(6),
        ADDRESS.withName("product"),
        JAVA_SHORT.withName("product_id"),
        MemoryLayout.paddingLayout(2),
        JAVA_INT.withName("device_flags")
    ).withName("LIBMTP_device_entry_t");

    // LIBMTP_raw_device_t layout
    static final StructLayout RAW_DEVICE_LAYOUT = MemoryLayout.structLayout(
        DEVICE_ENTRY_LAYOUT.withName("device_entry"),
        JAVA_INT.withName("bus_location"),
        JAVA_BYTE.withName("devnum"),
        MemoryLayout.paddingLayout(3)
    ).withName("LIBMTP_raw_device_t");

    // LIBMTP_devicestorage_t layout
    private static final StructLayout DEVICE_STORAGE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("id"),
        JAVA_SHORT.withName("StorageType"),
        JAVA_SHORT.withName("FilesystemType"),
        JAVA_SHORT.withName("AccessCapability"),
        MemoryLayout.paddingLayout(6),
        JAVA_LONG.withName("MaxCapacity"),
        JAVA_LONG.withName("FreeSpaceInBytes"),
        JAVA_LONG.withName("FreeSpaceInObjects"),
        ADDRESS.withName("StorageDescription"),
        ADDRESS.withName("VolumeIdentifier"),
        ADDRESS.withName("next"),
        ADDRESS.withName("prev")
    ).withName("LIBMTP_devicestorage_t");

    // LIBMTP_file_t layout
    private static final StructLayout FILE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("item_id"),
        JAVA_INT.withName("parent_id"),
        JAVA_INT.withName("storage_id"),
        MemoryLayout.paddingLayout(4),
        ADDRESS.withName("filename"),
        JAVA_LONG.withName("filesize"),
        JAVA_LONG.withName("modificationdate"),
        JAVA_INT.withName("filetype"),
        MemoryLayout.paddingLayout(4),
        ADDRESS.withName("next")
    ).withName("LIBMTP_file_t");

    private static final VarHandle RAW_DEVICE_VENDOR_ID =
        vh(RAW_DEVICE_LAYOUT, groupElement("device_entry"), groupElement("vendor_id"));
    private static final VarHandle RAW_DEVICE_PRODUCT_ID =
        vh(RAW_DEVICE_LAYOUT, groupElement("device_entry"), groupElement("product_id"));
    private static final VarHandle RAW_DEVICE_BUS_LOCATION =
        vh(RAW_DEVICE_LAYOUT, groupElement("bus_location"));
    private static final VarHandle RAW_DEVICE_DEVNUM =
        vh(RAW_DEVICE_LAYOUT, groupElement("devnum"));

    private static final VarHandle STORAGE_ID =
        vh(DEVICE_STORAGE_LAYOUT, groupElement("id"));
    private static final VarHandle STORAGE_DESCRIPTION =
        vh(DEVICE_STORAGE_LAYOUT, groupElement("StorageDescription"));
    private static final VarHandle STORAGE_MAX_CAPACITY =
        vh(DEVICE_STORAGE_LAYOUT, groupElement("MaxCapacity"));
    private static final VarHandle STORAGE_FREE_SPACE_BYTES =
        vh(DEVICE_STORAGE_LAYOUT, groupElement("FreeSpaceInBytes"));
    private static final VarHandle STORAGE_NEXT =
        vh(DEVICE_STORAGE_LAYOUT, groupElement("next"));

    // LIBMTP_track_t layout
    private static final StructLayout TRACK_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("item_id"),
        JAVA_INT.withName("parent_id"),
        JAVA_INT.withName("storage_id"),
        MemoryLayout.paddingLayout(4),
        ADDRESS.withName("title"),
        ADDRESS.withName("artist"),
        ADDRESS.withName("composer"),
        ADDRESS.withName("genre"),
        ADDRESS.withName("album"),
        ADDRESS.withName("date"),
        ADDRESS.withName("filename"),
        JAVA_SHORT.withName("tracknumber"),
        MemoryLayout.paddingLayout(2),
        JAVA_INT.withName("duration"),
        JAVA_INT.withName("samplerate"),
        JAVA_SHORT.withName("nochannels"),
        MemoryLayout.paddingLayout(2),
        JAVA_INT.withName("wavecodec"),
        JAVA_INT.withName("bitrate"),
        JAVA_SHORT.withName("bitratetype"),
        JAVA_SHORT.withName("rating"),
        JAVA_INT.withName("usecount"),
        JAVA_LONG.withName("filesize"),
        JAVA_LONG.withName("modificationdate"),
        JAVA_INT.withName("filetype"),
        MemoryLayout.paddingLayout(4),
        ADDRESS.withName("next")
    ).withName("LIBMTP_track_t");

    private static final VarHandle FILE_ITEM_ID =
        vh(FILE_LAYOUT, groupElement("item_id"));
    private static final VarHandle FILE_PARENT_ID =
        vh(FILE_LAYOUT, groupElement("parent_id"));
    private static final VarHandle FILE_STORAGE_ID =
        vh(FILE_LAYOUT, groupElement("storage_id"));
    private static final VarHandle FILE_FILENAME =
        vh(FILE_LAYOUT, groupElement("filename"));
    private static final VarHandle FILE_FILESIZE =
        vh(FILE_LAYOUT, groupElement("filesize"));
    private static final VarHandle FILE_MODIFICATIONDATE =
        vh(FILE_LAYOUT, groupElement("modificationdate"));
    private static final VarHandle FILE_FILETYPE =
        vh(FILE_LAYOUT, groupElement("filetype"));
    private static final VarHandle FILE_NEXT =
        vh(FILE_LAYOUT, groupElement("next"));

    private static final VarHandle TRACK_TITLE =
        vh(TRACK_LAYOUT, groupElement("title"));
    private static final VarHandle TRACK_ARTIST =
        vh(TRACK_LAYOUT, groupElement("artist"));
    private static final VarHandle TRACK_GENRE =
        vh(TRACK_LAYOUT, groupElement("genre"));
    private static final VarHandle TRACK_ALBUM =
        vh(TRACK_LAYOUT, groupElement("album"));
    private static final VarHandle TRACK_TRACKNUMBER =
        vh(TRACK_LAYOUT, groupElement("tracknumber"));
    private static final VarHandle TRACK_DURATION =
        vh(TRACK_LAYOUT, groupElement("duration"));

    private final MethodHandle init;
    private final MethodHandle releaseDevice;
    private final MethodHandle detectRawDevices;
    private final MethodHandle openRawDeviceUncached;
    private final MethodHandle getSerialNumber;
    private final MethodHandle getFriendlyName;
    private final MethodHandle getModelName;
    private final MethodHandle getManufacturerName;
    private final MethodHandle getFilesAndFolders;
    private final MethodHandle getTrackMetadataFn;
    private final MethodHandle destroyTrack;
    private final MethodHandle getFileToFile;
    private final MethodHandle sendFileFromFile;
    private final MethodHandle destroyFile;
    private final MethodHandle createFolderFn;
    private final MethodHandle moveObjectFn;
    private final MethodHandle setFileNameFn;
    private final MethodHandle deleteObjectFn;
    private final MethodHandle freeFn;

    private static final NativeLibMTP INSTANCE = new NativeLibMTP();

    static NativeLibMTP getInstance() {
        return INSTANCE;
    }

    private NativeLibMTP() {
        var linker = Linker.nativeLinker();
        var libmtp = SymbolLookup.libraryLookup("libmtp.so.9", Arena.global());

        init = bind(linker, libmtp, "LIBMTP_Init",
            FunctionDescriptor.ofVoid());
        releaseDevice = bind(linker, libmtp, "LIBMTP_Release_Device",
            FunctionDescriptor.ofVoid(ADDRESS));
        detectRawDevices = bind(linker, libmtp, "LIBMTP_Detect_Raw_Devices",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        openRawDeviceUncached = bind(linker, libmtp, "LIBMTP_Open_Raw_Device_Uncached",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
        getSerialNumber = bind(linker, libmtp, "LIBMTP_Get_Serialnumber",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
        getFriendlyName = bind(linker, libmtp, "LIBMTP_Get_Friendlyname",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
        getModelName = bind(linker, libmtp, "LIBMTP_Get_Modelname",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
        getManufacturerName = bind(linker, libmtp, "LIBMTP_Get_Manufacturername",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
        getFilesAndFolders = bind(linker, libmtp, "LIBMTP_Get_Files_And_Folders",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        getTrackMetadataFn = bind(linker, libmtp, "LIBMTP_Get_Trackmetadata",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        destroyTrack = bind(linker, libmtp, "LIBMTP_destroy_track_t",
            FunctionDescriptor.ofVoid(ADDRESS));
        getFileToFile = bind(linker, libmtp, "LIBMTP_Get_File_To_File",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        sendFileFromFile = bind(linker, libmtp, "LIBMTP_Send_File_From_File",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        destroyFile = bind(linker, libmtp, "LIBMTP_destroy_file_t",
            FunctionDescriptor.ofVoid(ADDRESS));
        createFolderFn = bind(linker, libmtp, "LIBMTP_Create_Folder",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        moveObjectFn = bind(linker, libmtp, "LIBMTP_Move_Object",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        setFileNameFn = bind(linker, libmtp, "LIBMTP_Set_File_Name",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        deleteObjectFn = bind(linker, libmtp, "LIBMTP_Delete_Object",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        freeFn = linker.downcallHandle(
            linker.defaultLookup().find("free").orElseThrow(),
            FunctionDescriptor.ofVoid(ADDRESS));

        try {
            init.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to initialize libmtp", t);
        }
    }

    // As of the finalized FFM API (JDK 22), layout-derived VarHandles carry a leading `long`
    // base-offset coordinate. We bind it to 0 here so call sites keep using just the segment.
    private static VarHandle vh(MemoryLayout layout, MemoryLayout.PathElement... path) {
        return MethodHandles.insertCoordinates(layout.varHandle(path), 1, 0L);
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return linker.downcallHandle(
            lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name)),
            desc);
    }

    // ---- Object-id translation between libmtp's 32-bit handles and opaque strings ----

    private static int toHandle(String id) {
        return id.equals(ROOT_PARENT) ? LIBMTP_FILES_AND_FOLDERS_ROOT : Integer.parseUnsignedInt(id);
    }

    private static String idStr(int handle) {
        return Integer.toUnsignedString(handle);
    }

    private static MemorySegment dev(DeviceHandle handle) {
        return ((LibMtpDevice) handle).device();
    }

    // ---- Scan / device lifecycle ----

    @Override
    public Scan scan() throws IOException {
        try (var arena = Arena.ofConfined()) {
            var ptrOut = arena.allocate(ADDRESS);
            var countOut = arena.allocate(JAVA_INT);
            int ret = (int) detectRawDevices.invokeExact(ptrOut, countOut);
            if (ret != LIBMTP_ERROR_NONE && ret != LIBMTP_ERROR_NO_DEVICE_ATTACHED) {
                throw new IOException("LIBMTP_Detect_Raw_Devices failed with code " + ret);
            }
            int count = countOut.get(JAVA_INT, 0);
            if (count == 0) {
                return new LibMtpScan(MemorySegment.NULL, 0);
            }
            var allocation = ptrOut.get(ADDRESS, 0).reinterpret(RAW_DEVICE_LAYOUT.byteSize() * count);
            return new LibMtpScan(allocation, count);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Failed to detect MTP devices", t);
        }
    }

    /** A libmtp raw-device scan; owns the raw-device allocation until {@link #close()}. */
    private final class LibMtpScan implements Scan {
        private final MemorySegment allocation;
        private final int count;

        LibMtpScan(MemorySegment allocation, int count) {
            this.allocation = allocation;
            this.count = count;
        }

        private MemorySegment rawDeviceAt(int index) {
            return allocation.asSlice(index * RAW_DEVICE_LAYOUT.byteSize(), RAW_DEVICE_LAYOUT.byteSize());
        }

        @Override
        public List<String> signatures() {
            var sigs = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) {
                var raw = rawDeviceAt(i);
                sigs.add(Short.toUnsignedInt((short) RAW_DEVICE_VENDOR_ID.get(raw)) + ":"
                    + Short.toUnsignedInt((short) RAW_DEVICE_PRODUCT_ID.get(raw)) + ":"
                    + ((int) RAW_DEVICE_BUS_LOCATION.get(raw)) + ":"
                    + Byte.toUnsignedInt((byte) RAW_DEVICE_DEVNUM.get(raw)));
            }
            return sigs;
        }

        @Override
        public OpenedDevice open(int index) {
            var raw = rawDeviceAt(index);
            MemorySegment device;
            try {
                device = (MemorySegment) openRawDeviceUncached.invokeExact(raw);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to open MTP device", t);
            }
            if (MemorySegment.NULL.equals(device)) {
                return null;
            }
            int vendorId = Short.toUnsignedInt((short) RAW_DEVICE_VENDOR_ID.get(raw));
            int productId = Short.toUnsignedInt((short) RAW_DEVICE_PRODUCT_ID.get(raw));
            var id = new MTPDeviceIdentifier(vendorId, productId, readCString(invoke(getSerialNumber, device)));
            var info = new MTPDeviceInfo(
                id,
                readCString(invoke(getFriendlyName, device)),
                readCString(invoke(getModelName, device)),
                readCString(invoke(getManufacturerName, device)),
                Integer.toUnsignedLong((int) RAW_DEVICE_BUS_LOCATION.get(raw)),
                Byte.toUnsignedLong((byte) RAW_DEVICE_DEVNUM.get(raw)));
            return new OpenedDevice(id, info, new LibMtpDevice(raw, device));
        }

        @Override
        public void close() {
            if (!MemorySegment.NULL.equals(allocation)) {
                free(allocation);
            }
        }
    }

    @Override
    public void releaseDevice(DeviceHandle handle) {
        try {
            releaseDevice.invokeExact(dev(handle));
        } catch (Throwable t) {
            throw new RuntimeException("Failed to release MTP device", t);
        }
    }

    // ---- Storage ----

    @Override
    public StorageResult findStorage(DeviceHandle handle, String storageName) {
        var storage = firstStorage(dev(handle));
        while (!MemorySegment.NULL.equals(storage)) {
            storage = storage.reinterpret(DEVICE_STORAGE_LAYOUT.byteSize());
            var descPtr = (MemorySegment) STORAGE_DESCRIPTION.get(storage);
            if (storageName.equals(readCString(descPtr))) {
                return new StorageResult(storageName, idStr((int) STORAGE_ID.get(storage)));
            }
            storage = (MemorySegment) STORAGE_NEXT.get(storage);
        }
        return null;
    }

    @Override
    public long getCapacity(DeviceHandle handle, String storageId) {
        return storageField(dev(handle), storageId, STORAGE_MAX_CAPACITY);
    }

    @Override
    public long getFreeSpace(DeviceHandle handle, String storageId) {
        return storageField(dev(handle), storageId, STORAGE_FREE_SPACE_BYTES);
    }

    private long storageField(MemorySegment device, String storageId, VarHandle field) {
        long target = Integer.toUnsignedLong(toHandle(storageId));
        var storage = firstStorage(device);
        while (!MemorySegment.NULL.equals(storage)) {
            storage = storage.reinterpret(DEVICE_STORAGE_LAYOUT.byteSize());
            if (Integer.toUnsignedLong((int) STORAGE_ID.get(storage)) == target) {
                return (long) field.get(storage);
            }
            storage = (MemorySegment) STORAGE_NEXT.get(storage);
        }
        return -1;
    }

    @Override
    public List<StorageResult> listStorages(DeviceHandle handle) {
        var results = new ArrayList<StorageResult>();
        var storage = firstStorage(dev(handle));
        while (!MemorySegment.NULL.equals(storage)) {
            storage = storage.reinterpret(DEVICE_STORAGE_LAYOUT.byteSize());
            var descPtr = (MemorySegment) STORAGE_DESCRIPTION.get(storage);
            results.add(new StorageResult(readCString(descPtr), idStr((int) STORAGE_ID.get(storage))));
            storage = (MemorySegment) STORAGE_NEXT.get(storage);
        }
        return results;
    }

    // ---- Items ----

    @Override
    public MTPItemInfo[] getChildItems(DeviceHandle handle, String storageId, String parentId) throws IOException {
        try {
            // A non-root parent handle already pins the folder uniquely, so search across every
            // storage (LIBMTP_FILES_AND_FOLDERS_ROOT == 0xFFFFFFFF doubles as the "all storages"
            // id). Pairing a concrete storage id with a non-root parent makes some devices return
            // nothing, which surfaces as a populated subfolder browsing empty. The storage-root
            // listing keeps its concrete storage id so each storage's top level stays separate.
            int storageArg = parentId.equals(ROOT_PARENT) ? toHandle(storageId) : LIBMTP_FILES_AND_FOLDERS_ROOT;
            var filePtr = (MemorySegment) getFilesAndFolders.invokeExact(
                dev(handle), storageArg, toHandle(parentId));

            var items = new ArrayList<MTPItemInfo>();
            while (!MemorySegment.NULL.equals(filePtr)) {
                var file = filePtr.reinterpret(FILE_LAYOUT.byteSize());
                var nextPtr = (MemorySegment) FILE_NEXT.get(file);
                items.add(new MTPItemInfo(
                    idStr((int) FILE_PARENT_ID.get(file)),
                    idStr((int) FILE_ITEM_ID.get(file)),
                    idStr((int) FILE_STORAGE_ID.get(file)),
                    (int) FILE_FILETYPE.get(file) != LIBMTP_FILETYPE_FOLDER,
                    (long) FILE_FILESIZE.get(file),
                    (long) FILE_MODIFICATIONDATE.get(file),
                    readCString((MemorySegment) FILE_FILENAME.get(file))
                ));
                destroyFile.invokeExact(filePtr);
                filePtr = nextPtr;
            }
            return items.toArray(new MTPItemInfo[0]);
        } catch (Throwable t) {
            throw new IOException("Failed to list files and folders", t);
        }
    }

    /**
     * Backed by {@code LIBMTP_Get_Trackmetadata}. On the uncached device handle this library opens,
     * that costs one GetObjectInfo for the handle (already cached when a listing walked past it)
     * plus a single-object GetObjectPropList (or per-property GetObjectPropValue calls on devices
     * without proplist support) — a few small USB transactions, never a data transfer. libmtp
     * returns NULL for objects whose format is not a known track type. {@link #sendFile} infers an
     * audio filetype from the filename extension, so tracks uploaded with a recognised extension are
     * stored in an indexable format; files stored as "unknown" report no metadata until the device's
     * own indexer reclassifies them.
     */
    @Override
    public MTPTrackMetadata getTrackMetadata(DeviceHandle handle, String itemId) throws IOException {
        MemorySegment trackPtr;
        try {
            trackPtr = (MemorySegment) getTrackMetadataFn.invokeExact(dev(handle), toHandle(itemId));
        } catch (Throwable t) {
            throw new IOException("Failed to read track metadata for id: " + itemId, t);
        }
        if (MemorySegment.NULL.equals(trackPtr)) {
            return null; // not an audio-track object (libmtp filters by object format)
        }
        try {
            var track = trackPtr.reinterpret(TRACK_LAYOUT.byteSize());
            var meta = new MTPTrackMetadata(
                emptyToNull(readCString((MemorySegment) TRACK_TITLE.get(track))),
                emptyToNull(readCString((MemorySegment) TRACK_ARTIST.get(track))),
                emptyToNull(readCString((MemorySegment) TRACK_ALBUM.get(track))),
                emptyToNull(readCString((MemorySegment) TRACK_GENRE.get(track))),
                Short.toUnsignedInt((short) TRACK_TRACKNUMBER.get(track)),
                Integer.toUnsignedLong((int) TRACK_DURATION.get(track)));
            return meta.isEmpty() ? null : meta;
        } finally {
            try {
                destroyTrack.invokeExact(trackPtr);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to free track metadata", t);
            }
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    @Override
    public String createFolder(DeviceHandle handle, String name, String parentId, String storageId) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var nameSeg = arena.allocateFrom(name);
            int folderId = (int) createFolderFn.invokeExact(
                dev(handle), nameSeg, toHandle(parentId), toHandle(storageId));
            if (folderId == 0) throw new IOException("LIBMTP_Create_Folder failed for: " + name);
            return idStr(folderId);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Failed to create folder: " + name, t);
        }
    }

    @Override
    public void deleteObject(DeviceHandle handle, String itemId) throws IOException {
        try {
            int ret = (int) deleteObjectFn.invokeExact(dev(handle), toHandle(itemId));
            if (ret != 0) throw new IOException("LIBMTP_Delete_Object failed for id: " + itemId);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Failed to delete object: " + itemId, t);
        }
    }

    @Override
    public void getFile(DeviceHandle handle, String itemId, String destPath) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var pathSeg = arena.allocateFrom(destPath);
            int ret;
            try {
                ret = (int) getFileToFile.invokeExact(
                    dev(handle), toHandle(itemId), pathSeg, MemorySegment.NULL, MemorySegment.NULL);
            } catch (Throwable t) {
                throw new IOException("Failed to retrieve file from device", t);
            }
            if (ret != 0) {
                throw new IOException("LIBMTP_Get_File_To_File failed with code " + ret);
            }
        }
    }

    @Override
    public String sendFile(DeviceHandle handle, String localPath, String filename,
                           String parentId, String storageId, long filesize) throws IOException {
        try (var arena = Arena.ofConfined()) {
            // Arena.allocate zero-fills, so item_id, padding and next are 0/NULL.
            var fileData = arena.allocate(FILE_LAYOUT);
            FILE_PARENT_ID.set(fileData, toHandle(parentId));
            FILE_STORAGE_ID.set(fileData, toHandle(storageId));
            FILE_FILENAME.set(fileData, arena.allocateFrom(filename));
            FILE_FILESIZE.set(fileData, filesize);
            FILE_FILETYPE.set(fileData, filetypeForFilename(filename));

            var pathSeg = arena.allocateFrom(localPath);
            int ret;
            try {
                ret = (int) sendFileFromFile.invokeExact(
                    dev(handle), pathSeg, fileData, MemorySegment.NULL, MemorySegment.NULL);
            } catch (Throwable t) {
                throw new IOException("Failed to send file to device", t);
            }
            if (ret != 0) {
                throw new IOException("LIBMTP_Send_File_From_File failed with code " + ret + " for: " + filename);
            }
            return idStr((int) FILE_ITEM_ID.get(fileData));
        }
    }

    @Override
    public void moveObject(DeviceHandle handle, String itemId, String storageId, String parentId) throws IOException {
        try {
            int ret = (int) moveObjectFn.invokeExact(
                dev(handle), toHandle(itemId), toHandle(storageId), toHandle(parentId));
            if (ret != 0) throw new IOException("LIBMTP_Move_Object failed with code " + ret + " for id: " + itemId);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Failed to move object: " + itemId, t);
        }
    }

    @Override
    public void setFileName(DeviceHandle handle, String itemId, String newName) throws IOException {
        try (var arena = Arena.ofConfined()) {
            // Set_File_Name only reads filedata->item_id; the rest is zero-filled by allocate.
            var fileData = arena.allocate(FILE_LAYOUT);
            FILE_ITEM_ID.set(fileData, toHandle(itemId));
            FILE_FILETYPE.set(fileData, LIBMTP_FILETYPE_UNKNOWN);
            var nameSeg = arena.allocateFrom(newName);
            int ret;
            try {
                ret = (int) setFileNameFn.invokeExact(dev(handle), fileData, nameSeg);
            } catch (Throwable t) {
                throw new IOException("Failed to rename object on device", t);
            }
            if (ret != 0) throw new IOException("LIBMTP_Set_File_Name failed with code " + ret + " for id: " + itemId);
        }
    }

    // ---- Low-level helpers ----

    private void free(MemorySegment ptr) {
        try {
            freeFn.invokeExact(ptr);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to free memory", t);
        }
    }

    private MemorySegment firstStorage(MemorySegment device) {
        return device.reinterpret(DEVICE_STORAGE_FIELD_OFFSET + ADDRESS.byteSize())
            .get(ADDRESS, DEVICE_STORAGE_FIELD_OFFSET);
    }

    private MemorySegment invoke(MethodHandle handle, MemorySegment arg) {
        try {
            return (MemorySegment) handle.invokeExact(arg);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private String readCString(MemorySegment ptr) {
        if (ptr == null || MemorySegment.NULL.equals(ptr)) return "";
        return ptr.reinterpret(Long.MAX_VALUE).getString(0);
    }
}
