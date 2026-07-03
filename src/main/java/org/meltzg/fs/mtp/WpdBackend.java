package org.meltzg.fs.mtp;

import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPDeviceInfo;
import org.meltzg.fs.mtp.types.MTPItemInfo;
import org.meltzg.fs.mtp.types.MTPTrackMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.*;
import static org.meltzg.fs.mtp.WpdCom.*;

/**
 * MTP backend built on the Windows Portable Devices (WPD) COM API, driven entirely through the Java
 * FFM API (see {@link WpdCom}). This is the native way to access MTP devices on Windows without
 * replacing the device's driver.
 *
 * <p>WPD object identifiers are opaque strings and are surfaced verbatim through the
 * {@link MtpBackend} contract. {@link MtpBackend#ROOT_PARENT} maps to a storage's functional-object
 * id (which is also that storage's id).
 *
 * <p><b>Note:</b> this code can only run on Windows and is exercised there; it compiles on any
 * platform but is never class-loaded off Windows (see {@link MtpBackend#defaultBackend()}).
 */
class WpdBackend implements MtpBackend {

    // ---- COM class / interface ids ----
    private static final MemorySegment CLSID_DEVICE_MANAGER;
    private static final MemorySegment IID_DEVICE_MANAGER;
    private static final MemorySegment CLSID_DEVICE_FTM;
    private static final MemorySegment IID_DEVICE;
    private static final MemorySegment IID_DATA_STREAM;
    private static final MemorySegment CLSID_VALUES;
    private static final MemorySegment IID_VALUES;
    private static final MemorySegment CLSID_KEY_COLLECTION;
    private static final MemorySegment IID_KEY_COLLECTION;
    private static final MemorySegment CLSID_PROPVARIANT_COLLECTION;
    private static final MemorySegment IID_PROPVARIANT_COLLECTION;

    // ---- PROPERTYKEYs ----
    private static final MemorySegment KEY_OBJECT_ID;
    private static final MemorySegment KEY_PARENT_ID;
    private static final MemorySegment KEY_NAME;
    private static final MemorySegment KEY_ORIGINAL_FILE_NAME;
    private static final MemorySegment KEY_CONTENT_TYPE;
    private static final MemorySegment KEY_OBJECT_FORMAT;
    private static final MemorySegment KEY_OBJECT_SIZE;
    private static final MemorySegment KEY_DATE_MODIFIED;
    private static final MemorySegment KEY_FUNCTIONAL_CATEGORY;
    private static final MemorySegment KEY_STORAGE_CAPACITY;
    private static final MemorySegment KEY_STORAGE_FREE_SPACE;
    private static final MemorySegment KEY_RESOURCE_DEFAULT;
    private static final MemorySegment KEY_MEDIA_TITLE;
    private static final MemorySegment KEY_MEDIA_DURATION;
    private static final MemorySegment KEY_MEDIA_ARTIST;
    private static final MemorySegment KEY_MEDIA_GENRE;
    private static final MemorySegment KEY_MUSIC_ALBUM;
    private static final MemorySegment KEY_MUSIC_TRACK;
    private static final MemorySegment KEY_CLIENT_NAME;
    private static final MemorySegment KEY_CLIENT_MAJOR_VERSION;
    private static final MemorySegment KEY_CLIENT_MINOR_VERSION;
    private static final MemorySegment KEY_CLIENT_REVISION;

    // ---- content-type / category GUID values ----
    private static final MemorySegment CONTENT_TYPE_FOLDER;
    private static final MemorySegment CONTENT_TYPE_FUNCTIONAL_OBJECT;
    private static final MemorySegment CONTENT_TYPE_GENERIC_FILE;
    private static final MemorySegment FORMAT_UNSPECIFIED;
    private static final MemorySegment FORMAT_PROPERTIES_ONLY;
    private static final MemorySegment FUNCTIONAL_CATEGORY_STORAGE;

    // The well-known root from which a device's functional objects (storages) are enumerated.
    private static final String WPD_DEVICE_OBJECT_ID = "DEVICE";

    // ---- vtable indices (after IUnknown's QueryInterface=0, AddRef=1, Release=2) ----
    private static final int MGR_GET_DEVICES = 3, MGR_FRIENDLY_NAME = 5, MGR_DESCRIPTION = 6, MGR_MANUFACTURER = 7;
    private static final int DEV_OPEN = 3, DEV_CONTENT = 5, DEV_CLOSE = 8;
    // IPortableDeviceContent vtable order (after IUnknown): EnumObjects(3), Properties(4), Transfer(5),
    // CreateObjectWithPropertiesOnly(6), CreateObjectWithPropertiesAndData(7), Delete(8),
    // GetObjectIDsFromPersistentUniqueIDs(9), Cancel(10), Move(11), Copy(12).
    private static final int CONTENT_ENUM = 3, CONTENT_PROPERTIES = 4, CONTENT_TRANSFER = 5,
        CONTENT_CREATE_PROPS = 6, CONTENT_CREATE_DATA = 7, CONTENT_DELETE = 8, CONTENT_MOVE = 11;
    private static final int ENUM_NEXT = 3;
    private static final int PROPS_GET_VALUES = 5, PROPS_SET_VALUES = 6;
    private static final int RES_GET_STREAM = 5;
    private static final int VAL_GET_VALUE = 6, VAL_SET_STRING = 7, VAL_GET_STRING = 8, VAL_SET_U4 = 9,
        VAL_GET_U4 = 10, VAL_SET_U8 = 13, VAL_GET_U8 = 14, VAL_SET_GUID = 27, VAL_GET_GUID = 28;
    private static final int KEYCOLL_ADD = 5;
    private static final int PVCOLL_ADD = 5;
    private static final int STREAM_READ = 3, STREAM_WRITE = 4, STREAM_COMMIT = 8;
    private static final int DATASTREAM_GET_OBJECT_ID = 14;

    private static final int PORTABLE_DEVICE_DELETE_NO_RECURSION = 0;

    private static final Pattern VID = Pattern.compile("vid_([0-9a-fA-F]{4})");
    private static final Pattern PID = Pattern.compile("pid_([0-9a-fA-F]{4})");

    static {
        var a = GLOBAL;
        CLSID_DEVICE_MANAGER = guid(a, "0af10cec-2ecd-4b92-9581-34f6ae0637f3");
        IID_DEVICE_MANAGER = guid(a, "a1567595-4c2f-4574-a6fa-ecef917b9a40");
        CLSID_DEVICE_FTM = guid(a, "f7c0039a-4762-488a-b4b3-760ef9a1ba9b");
        IID_DEVICE = guid(a, "625e2df8-6392-4cf0-9ad1-3cfa5f17775c");
        IID_DATA_STREAM = guid(a, "88e04db3-1012-4d64-9996-f703a950d3f4");
        CLSID_VALUES = guid(a, "0c15d503-d017-47ce-9016-7b3f978721cc");
        IID_VALUES = guid(a, "6848f6f2-3155-4f86-b6f5-263eeeab3143");
        CLSID_KEY_COLLECTION = guid(a, "de2d022d-2480-43be-97f0-d1fa2cf98f4f");
        IID_KEY_COLLECTION = guid(a, "dada2357-e0ad-492e-98db-dd61c53ba353");
        CLSID_PROPVARIANT_COLLECTION = guid(a, "08a99e2f-6d6d-4b80-af5a-baf2bcbe4cb9");
        IID_PROPVARIANT_COLLECTION = guid(a, "89b2e422-4f1b-4316-bcef-a44afea83eb3");

        String objFmt = "ef6b490d-5cd8-437a-affc-da8b60ee4a3c";
        KEY_OBJECT_ID = propertyKey(a, objFmt, 2);
        KEY_PARENT_ID = propertyKey(a, objFmt, 3);
        KEY_NAME = propertyKey(a, objFmt, 4);
        KEY_OBJECT_FORMAT = propertyKey(a, objFmt, 6);
        KEY_CONTENT_TYPE = propertyKey(a, objFmt, 7);
        KEY_OBJECT_SIZE = propertyKey(a, objFmt, 11);
        KEY_ORIGINAL_FILE_NAME = propertyKey(a, objFmt, 12);
        KEY_DATE_MODIFIED = propertyKey(a, objFmt, 19);
        KEY_FUNCTIONAL_CATEGORY = propertyKey(a, "8f052d93-abca-4fc5-a5ac-b01df4dbe598", 2);
        KEY_STORAGE_CAPACITY = propertyKey(a, "01a3057a-74d6-4e80-bea7-dc4c212ce50a", 4);
        KEY_STORAGE_FREE_SPACE = propertyKey(a, "01a3057a-74d6-4e80-bea7-dc4c212ce50a", 5);
        KEY_RESOURCE_DEFAULT = propertyKey(a, "e81e79be-34f0-41bf-b53f-f1a06ae87842", 0);
        // WPD_MEDIA_* / WPD_MUSIC_* property keys (PortableDevice.h), read by getTrackMetadata.
        String mediaFmt = "2ed8ba05-0ad3-42dc-b0d0-bc95ac396ac8";
        KEY_MEDIA_TITLE = propertyKey(a, mediaFmt, 18);
        KEY_MEDIA_DURATION = propertyKey(a, mediaFmt, 19);
        KEY_MEDIA_ARTIST = propertyKey(a, mediaFmt, 24);
        KEY_MEDIA_GENRE = propertyKey(a, mediaFmt, 32);
        String musicFmt = "b324f56a-dc5d-46e5-b6df-d2ea414888c6";
        KEY_MUSIC_ALBUM = propertyKey(a, musicFmt, 3);
        KEY_MUSIC_TRACK = propertyKey(a, musicFmt, 4);
        String clientFmt = "204d9f0c-2292-4080-9f42-40664e70f859";
        KEY_CLIENT_NAME = propertyKey(a, clientFmt, 2);
        KEY_CLIENT_MAJOR_VERSION = propertyKey(a, clientFmt, 3);
        KEY_CLIENT_MINOR_VERSION = propertyKey(a, clientFmt, 4);
        KEY_CLIENT_REVISION = propertyKey(a, clientFmt, 5);

        CONTENT_TYPE_FOLDER = guid(a, "27e2e392-a111-48e0-ab0c-e17705a05f85");
        CONTENT_TYPE_FUNCTIONAL_OBJECT = guid(a, "99ed0160-17ff-4c44-9d98-1d7a6f941921");
        CONTENT_TYPE_GENERIC_FILE = guid(a, "0085e0a6-8d34-45d7-bc5c-447e59c73d48");
        FORMAT_UNSPECIFIED = guid(a, "30000000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_PROPERTIES_ONLY = guid(a, "30010000-ae6c-4804-98ba-c57b46965fe7");
        FUNCTIONAL_CATEGORY_STORAGE = guid(a, "23f05bbc-15de-4c2a-a55b-a9af5ce412ef");
    }

    private static final WpdBackend INSTANCE = new WpdBackend();

    static WpdBackend getInstance() {
        return INSTANCE;
    }

    private WpdBackend() {}

    /** Live WPD handle: the device plus its content and properties interfaces. */
    private record WpdDevice(MemorySegment device, MemorySegment content, MemorySegment properties)
        implements DeviceHandle {}

    private static WpdDevice dev(DeviceHandle handle) {
        return (WpdDevice) handle;
    }

    // ---- generic vtable call: every WPD method returns an HRESULT ----

    private static int call(MemorySegment obj, int idx, FunctionDescriptor desc, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = obj;
        System.arraycopy(args, 0, all, 1, args.length);
        try {
            return ((Number) WpdCom.method(obj, idx, desc).invokeWithArguments(all)).intValue();
        } catch (Throwable t) {
            throw new RuntimeException("COM call (vtbl index " + idx + ") failed", t);
        }
    }

    // ---- scan / device lifecycle ----

    @Override
    public Scan scan() throws IOException {
        ensureInitialized();
        var manager = createInstance(CLSID_DEVICE_MANAGER, IID_DEVICE_MANAGER, "create PortableDeviceManager");
        try (var arena = Arena.ofConfined()) {
            var countOut = arena.allocate(JAVA_INT);
            checkHr(call(manager, MGR_GET_DEVICES,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    MemorySegment.NULL, countOut),
                "IPortableDeviceManager::GetDevices (count)");
            int count = countOut.get(JAVA_INT, 0);
            var ids = new ArrayList<String>(count);
            if (count > 0) {
                var arr = arena.allocate(ADDRESS.byteSize() * count);
                checkHr(call(manager, MGR_GET_DEVICES,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                        arr, countOut),
                    "IPortableDeviceManager::GetDevices");
                int n = countOut.get(JAVA_INT, 0);
                for (int i = 0; i < n; i++) {
                    var ptr = arr.getAtIndex(ADDRESS, i);
                    ids.add(readWstr(ptr));
                    coTaskMemFree(ptr);
                }
            }
            return new WpdScan(manager, ids);
        } catch (Throwable t) {
            release(manager);
            if (t instanceof IOException io) throw io;
            throw new IOException("Failed to enumerate WPD devices", t);
        }
    }

    /** A WPD scan: the device manager plus the stable PnP device-id strings it returned. */
    private final class WpdScan implements Scan {
        private final MemorySegment manager;
        private final List<String> ids;

        WpdScan(MemorySegment manager, List<String> ids) {
            this.manager = manager;
            this.ids = ids;
        }

        @Override
        public List<String> signatures() {
            return ids; // PnP device ids are stable while attached
        }

        @Override
        public OpenedDevice open(int index) throws IOException {
            return openDevice(manager, ids.get(index));
        }

        @Override
        public void close() {
            release(manager);
        }
    }

    private OpenedDevice openDevice(MemorySegment manager, String deviceId) throws IOException {
        var device = createInstance(CLSID_DEVICE_FTM, IID_DEVICE, "create PortableDevice");
        try (var arena = Arena.ofConfined()) {
            var clientInfo = createInstance(CLSID_VALUES, IID_VALUES, "create client info");
            // WPD generates the per-connection client context from these during Open; some device
            // operations (notably object creation) misbehave when the version fields are absent.
            call(clientInfo, VAL_SET_STRING, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                KEY_CLIENT_NAME, wstr(arena, "melt-jfs"));
            call(clientInfo, VAL_SET_U4, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), KEY_CLIENT_MAJOR_VERSION, 1);
            call(clientInfo, VAL_SET_U4, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), KEY_CLIENT_MINOR_VERSION, 0);
            call(clientInfo, VAL_SET_U4, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), KEY_CLIENT_REVISION, 0);
            var idW = wstr(arena, deviceId);
            int hr = call(device, DEV_OPEN, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), idW, clientInfo);
            release(clientInfo);
            checkHr(hr, "IPortableDevice::Open");

            var contentOut = arena.allocate(ADDRESS);
            checkHr(call(device, DEV_CONTENT, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), contentOut),
                "IPortableDevice::Content");
            var content = contentOut.get(ADDRESS, 0);

            var propsOut = arena.allocate(ADDRESS);
            checkHr(call(content, CONTENT_PROPERTIES, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), propsOut),
                "IPortableDeviceContent::Properties");
            var props = propsOut.get(ADDRESS, 0);

            var friendly = deviceStringProp(manager, deviceId, MGR_FRIENDLY_NAME, arena);
            var description = deviceStringProp(manager, deviceId, MGR_DESCRIPTION, arena);
            var manufacturer = deviceStringProp(manager, deviceId, MGR_MANUFACTURER, arena);

            var id = parseIdentifier(deviceId);
            var info = new MTPDeviceInfo(id, friendly, description, manufacturer, 0, 0);
            return new OpenedDevice(id, info, new WpdDevice(device, content, props));
        } catch (Throwable t) {
            release(device);
            if (t instanceof IOException io) throw io;
            throw new IOException("Failed to open WPD device " + deviceId, t);
        }
    }

    private String deviceStringProp(MemorySegment manager, String deviceId, int methodIdx, Arena arena) {
        var idW = wstr(arena, deviceId);
        var cch = arena.allocate(JAVA_INT);
        var desc = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        // First call with a NULL buffer asks for the required length (in characters).
        call(manager, methodIdx, desc, idW, MemorySegment.NULL, cch);
        int n = cch.get(JAVA_INT, 0);
        if (n <= 0) return "";
        var buf = arena.allocate(JAVA_CHAR, n);
        int hr = call(manager, methodIdx, desc, idW, buf, cch);
        return failed(hr) ? "" : readWstr(buf);
    }

    private MTPDeviceIdentifier parseIdentifier(String deviceId) {
        String lower = deviceId.toLowerCase();
        int vendor = 0, product = 0;
        Matcher mv = VID.matcher(lower);
        if (mv.find()) vendor = Integer.parseInt(mv.group(1), 16);
        Matcher mp = PID.matcher(lower);
        if (mp.find()) product = Integer.parseInt(mp.group(1), 16);
        // The instance id (typically the device serial) is the 3rd '#'-delimited segment.
        var segments = deviceId.split("#");
        String serial = segments.length >= 3 ? segments[2] : deviceId.replaceAll("[^0-9A-Za-z]", "");
        return new MTPDeviceIdentifier(vendor, product, serial);
    }

    @Override
    public void releaseDevice(DeviceHandle handle) {
        var d = dev(handle);
        try {
            call(d.device(), DEV_CLOSE, FunctionDescriptor.of(JAVA_INT, ADDRESS));
        } catch (RuntimeException ignored) {
            // Closing is best-effort; still release the interface pointers below.
        }
        release(d.properties());
        release(d.content());
        release(d.device());
    }

    // ---- storage ----

    @Override
    public List<StorageResult> listStorages(DeviceHandle handle) {
        var d = dev(handle);
        var results = new ArrayList<StorageResult>();
        try {
            for (String childId : enumChildren(d.content(), WPD_DEVICE_OBJECT_ID)) {
                var values = getValues(d.properties(), childId, KEY_FUNCTIONAL_CATEGORY, KEY_NAME);
                if (MemorySegment.NULL.equals(values)) continue;
                try (var arena = Arena.ofConfined()) {
                    var guidBuf = arena.allocate(GUID_SIZE);
                    if (getGuid(values, KEY_FUNCTIONAL_CATEGORY, guidBuf)
                        && guidEquals(guidBuf, FUNCTIONAL_CATEGORY_STORAGE)) {
                        var name = getString(values, KEY_NAME);
                        results.add(new StorageResult(name.isEmpty() ? childId : name, childId));
                    }
                } finally {
                    release(values);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list WPD storages", e);
        }
        return results;
    }

    @Override
    public StorageResult findStorage(DeviceHandle handle, String storageName) {
        return listStorages(handle).stream()
            .filter(s -> s.name().equals(storageName))
            .findFirst().orElse(null);
    }

    @Override
    public long getCapacity(DeviceHandle handle, String storageId) {
        return storageU8(dev(handle), storageId, KEY_STORAGE_CAPACITY);
    }

    @Override
    public long getFreeSpace(DeviceHandle handle, String storageId) {
        return storageU8(dev(handle), storageId, KEY_STORAGE_FREE_SPACE);
    }

    private long storageU8(WpdDevice d, String storageId, MemorySegment key) {
        var values = getValues(d.properties(), storageId, key);
        if (MemorySegment.NULL.equals(values)) return -1;
        try {
            return getU8(values, key);
        } finally {
            release(values);
        }
    }

    // ---- items ----

    @Override
    public MTPItemInfo[] getChildItems(DeviceHandle handle, String storageId, String parentId) throws IOException {
        var d = dev(handle);
        String wpdParent = parentId.equals(ROOT_PARENT) ? storageId : parentId;
        var items = new ArrayList<MTPItemInfo>();
        for (String childId : enumChildren(d.content(), wpdParent)) {
            var values = getValues(d.properties(), childId,
                KEY_CONTENT_TYPE, KEY_ORIGINAL_FILE_NAME, KEY_NAME, KEY_OBJECT_SIZE, KEY_DATE_MODIFIED);
            if (MemorySegment.NULL.equals(values)) continue;
            try (var arena = Arena.ofConfined()) {
                var guidBuf = arena.allocate(GUID_SIZE);
                boolean isFile = true;
                if (getGuid(values, KEY_CONTENT_TYPE, guidBuf)) {
                    isFile = !(guidEquals(guidBuf, CONTENT_TYPE_FOLDER)
                        || guidEquals(guidBuf, CONTENT_TYPE_FUNCTIONAL_OBJECT));
                }
                var name = getString(values, KEY_ORIGINAL_FILE_NAME);
                if (name.isEmpty()) name = getString(values, KEY_NAME);
                if (name.isEmpty()) name = childId;
                long size = getU8(values, KEY_OBJECT_SIZE);
                long modified = getDateEpochSeconds(values, KEY_DATE_MODIFIED);
                items.add(new MTPItemInfo(wpdParent, childId, storageId, isFile,
                    size < 0 ? 0 : size, modified, name));
            } finally {
                release(values);
            }
        }
        return items.toArray(new MTPItemInfo[0]);
    }

    /**
     * Backed by a single {@code IPortableDeviceProperties::GetValues} call requesting the
     * WPD_MEDIA_* / WPD_MUSIC_* keys — a metadata-only exchange, never a data transfer. WPD has no
     * "is a track" gate, so an object where every requested property is absent (a folder, a
     * non-audio file, or an audio file the device has not indexed) reports null.
     */
    @Override
    public MTPTrackMetadata getTrackMetadata(DeviceHandle handle, String itemId) throws IOException {
        var d = dev(handle);
        var values = getValues(d.properties(), itemId,
            KEY_MEDIA_TITLE, KEY_MEDIA_ARTIST, KEY_MUSIC_ALBUM, KEY_MEDIA_GENRE,
            KEY_MUSIC_TRACK, KEY_MEDIA_DURATION);
        if (MemorySegment.NULL.equals(values)) return null;
        try {
            long trackNumber = getU4(values, KEY_MUSIC_TRACK);
            long duration = getU8(values, KEY_MEDIA_DURATION);
            var meta = new MTPTrackMetadata(
                emptyToNull(getString(values, KEY_MEDIA_TITLE)),
                emptyToNull(getString(values, KEY_MEDIA_ARTIST)),
                emptyToNull(getString(values, KEY_MUSIC_ALBUM)),
                emptyToNull(getString(values, KEY_MEDIA_GENRE)),
                (int) Math.max(trackNumber, 0),
                Math.max(duration, 0));
            return meta.isEmpty() ? null : meta;
        } finally {
            release(values);
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    @Override
    public String createFolder(DeviceHandle handle, String name, String parentId, String storageId) throws IOException {
        var d = dev(handle);
        String parent = parentId.equals(ROOT_PARENT) ? storageId : parentId;
        try (var arena = Arena.ofConfined()) {
            var values = createInstance(CLSID_VALUES, IID_VALUES, "create object properties");
            try {
                setString(values, KEY_PARENT_ID, wstr(arena, parent));
                setString(values, KEY_NAME, wstr(arena, name));
                setString(values, KEY_ORIGINAL_FILE_NAME, wstr(arena, name));
                setGuid(values, KEY_CONTENT_TYPE, CONTENT_TYPE_FOLDER);
                // A folder is a properties-only object; WPD requires the matching format guid.
                setGuid(values, KEY_OBJECT_FORMAT, FORMAT_PROPERTIES_ONLY);
                var idOut = arena.allocate(ADDRESS);
                checkHr(call(d.content(), CONTENT_CREATE_PROPS,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), values, idOut),
                    "CreateObjectWithPropertiesOnly");
                var ptr = idOut.get(ADDRESS, 0);
                var id = readWstr(ptr);
                coTaskMemFree(ptr);
                return id;
            } finally {
                release(values);
            }
        }
    }

    @Override
    public void deleteObject(DeviceHandle handle, String itemId) throws IOException {
        var d = dev(handle);
        var coll = objectIdCollection(itemId);
        try {
            checkHr(call(d.content(), CONTENT_DELETE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    PORTABLE_DEVICE_DELETE_NO_RECURSION, coll, MemorySegment.NULL),
                "IPortableDeviceContent::Delete");
        } finally {
            release(coll);
        }
    }

    @Override
    public void getFile(DeviceHandle handle, String itemId, String destPath) throws IOException {
        var d = dev(handle);
        try (var arena = Arena.ofConfined()) {
            var resOut = arena.allocate(ADDRESS);
            checkHr(call(d.content(), CONTENT_TRANSFER,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), resOut),
                "IPortableDeviceContent::Transfer");
            var resources = resOut.get(ADDRESS, 0);
            try {
                var optBuf = arena.allocate(JAVA_INT);
                var streamOut = arena.allocate(ADDRESS);
                checkHr(call(resources, RES_GET_STREAM,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                        wstr(arena, itemId), KEY_RESOURCE_DEFAULT, STGM_READ, optBuf, streamOut),
                    "IPortableDeviceResources::GetStream");
                var stream = streamOut.get(ADDRESS, 0);
                int bufSize = Math.max(optBuf.get(JAVA_INT, 0), 1 << 16);
                try (var out = Files.newOutputStream(Path.of(destPath))) {
                    copyStreamToFile(stream, out, bufSize);
                } finally {
                    release(stream);
                }
            } finally {
                release(resources);
            }
        }
    }

    @Override
    public String sendFile(DeviceHandle handle, String localPath, String filename,
                           String parentId, String storageId, long filesize) throws IOException {
        var d = dev(handle);
        String parent = parentId.equals(ROOT_PARENT) ? storageId : parentId;
        try (var arena = Arena.ofConfined()) {
            var values = createInstance(CLSID_VALUES, IID_VALUES, "create object properties");
            MemorySegment stream;
            try {
                setString(values, KEY_PARENT_ID, wstr(arena, parent));
                setString(values, KEY_NAME, wstr(arena, filename));
                setString(values, KEY_ORIGINAL_FILE_NAME, wstr(arena, filename));
                setU8(values, KEY_OBJECT_SIZE, filesize);
                setGuid(values, KEY_CONTENT_TYPE, CONTENT_TYPE_GENERIC_FILE);
                // WPD requires an object format alongside the content type; the generic
                // "unspecified" format lets the device store an arbitrary byte stream.
                setGuid(values, KEY_OBJECT_FORMAT, FORMAT_UNSPECIFIED);

                var streamOut = arena.allocate(ADDRESS);
                var optBuf = arena.allocate(JAVA_INT);
                checkHr(call(d.content(), CONTENT_CREATE_DATA,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                        values, streamOut, optBuf, MemorySegment.NULL),
                    "CreateObjectWithPropertiesAndData");
                stream = streamOut.get(ADDRESS, 0);
                int bufSize = Math.max(optBuf.get(JAVA_INT, 0), 1 << 16);
                try (var in = Files.newInputStream(Path.of(localPath))) {
                    copyFileToStream(in, stream, bufSize);
                }
            } finally {
                release(values);
            }
            try {
                checkHr(call(stream, STREAM_COMMIT, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), 0),
                    "IStream::Commit");
                return readNewObjectId(stream);
            } finally {
                release(stream);
            }
        }
    }

    @Override
    public void moveObject(DeviceHandle handle, String itemId, String storageId, String parentId) throws IOException {
        var d = dev(handle);
        String dest = parentId.equals(ROOT_PARENT) ? storageId : parentId;
        var coll = objectIdCollection(itemId);
        try (var arena = Arena.ofConfined()) {
            // Many devices do not implement Move; a failing HRESULT surfaces as IOException, which
            // MTPDeviceBridge.move() turns into MTPOperationUnsupportedException for emulation.
            checkHr(call(d.content(), CONTENT_MOVE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                    coll, wstr(arena, dest), MemorySegment.NULL),
                "IPortableDeviceContent::Move");
        } finally {
            release(coll);
        }
    }

    @Override
    public void setFileName(DeviceHandle handle, String itemId, String newName) throws IOException {
        var d = dev(handle);
        try (var arena = Arena.ofConfined()) {
            var values = createInstance(CLSID_VALUES, IID_VALUES, "create rename properties");
            try {
                setString(values, KEY_NAME, wstr(arena, newName));
                setString(values, KEY_ORIGINAL_FILE_NAME, wstr(arena, newName));
                var resultsOut = arena.allocate(ADDRESS);
                int hr = call(d.properties(), PROPS_SET_VALUES,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                    wstr(arena, itemId), values, resultsOut);
                if (!failed(hr)) {
                    release(resultsOut.get(ADDRESS, 0));
                }
                checkHr(hr, "IPortableDeviceProperties::SetValues");
            } finally {
                release(values);
            }
        }
    }

    // ---- helpers ----

    /** Enumerates the immediate child object ids of {@code parentObjectId}. */
    private List<String> enumChildren(MemorySegment content, String parentObjectId) throws IOException {
        var ids = new ArrayList<String>();
        try (var arena = Arena.ofConfined()) {
            var enumOut = arena.allocate(ADDRESS);
            checkHr(call(content, CONTENT_ENUM,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    0, wstr(arena, parentObjectId), MemorySegment.NULL, enumOut),
                "IPortableDeviceContent::EnumObjects");
            var enumObj = enumOut.get(ADDRESS, 0);
            try {
                final int batch = 32;
                var fetched = arena.allocate(JAVA_INT);
                var arr = arena.allocate(ADDRESS.byteSize() * batch);
                while (true) {
                    int hr = call(enumObj, ENUM_NEXT,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                        batch, arr, fetched);
                    if (failed(hr)) break;
                    int n = fetched.get(JAVA_INT, 0);
                    for (int i = 0; i < n; i++) {
                        var ptr = arr.getAtIndex(ADDRESS, i);
                        ids.add(readWstr(ptr));
                        coTaskMemFree(ptr);
                    }
                    if (n < batch) break; // S_FALSE / fewer than requested ⇒ end of enumeration
                }
            } finally {
                release(enumObj);
            }
        }
        return ids;
    }

    /** Fetches the given properties for one object. Returns NULL on failure; caller must release. */
    private MemorySegment getValues(MemorySegment properties, String objectId, MemorySegment... keys) {
        var keyColl = createInstanceQuiet(CLSID_KEY_COLLECTION, IID_KEY_COLLECTION);
        if (MemorySegment.NULL.equals(keyColl)) return MemorySegment.NULL;
        try (var arena = Arena.ofConfined()) {
            for (var key : keys) {
                call(keyColl, KEYCOLL_ADD, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), key);
            }
            var out = arena.allocate(ADDRESS);
            int hr = call(properties, PROPS_GET_VALUES,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                wstr(arena, objectId), keyColl, out);
            return failed(hr) ? MemorySegment.NULL : out.get(ADDRESS, 0);
        } finally {
            release(keyColl);
        }
    }

    private static MemorySegment createInstanceQuiet(MemorySegment clsid, MemorySegment iid) {
        try {
            return createInstance(clsid, iid, "create COM object");
        } catch (IOException e) {
            return MemorySegment.NULL;
        }
    }

    private String getString(MemorySegment values, MemorySegment key) {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(ADDRESS);
            int hr = call(values, VAL_GET_STRING,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, out);
            if (failed(hr)) return "";
            var ptr = out.get(ADDRESS, 0);
            var s = readWstr(ptr);
            coTaskMemFree(ptr);
            return s;
        }
    }

    private long getU8(MemorySegment values, MemorySegment key) {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(JAVA_LONG);
            int hr = call(values, VAL_GET_U8,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, out);
            return failed(hr) ? -1 : out.get(JAVA_LONG, 0);
        }
    }

    private long getU4(MemorySegment values, MemorySegment key) {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(JAVA_INT);
            int hr = call(values, VAL_GET_U4,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, out);
            return failed(hr) ? -1 : Integer.toUnsignedLong(out.get(JAVA_INT, 0));
        }
    }

    // Days between the OLE Automation date epoch (1899-12-30) and the Unix epoch (1970-01-01).
    private static final double OA_EPOCH_DAYS = 25569.0;
    private static final double SECONDS_PER_DAY = 86400.0;

    /**
     * Reads {@code key} as a VT_DATE (the documented type of WPD_OBJECT_DATE_MODIFIED) and converts
     * it to Unix epoch seconds, matching the {@code time_t} semantics the rest of the code expects.
     * Returns 0 when the property is absent or not a date.
     */
    private long getDateEpochSeconds(MemorySegment values, MemorySegment key) {
        try (var arena = Arena.ofConfined()) {
            var pv = arena.allocate(PROPVARIANT_SIZE); // zero-filled
            int hr = call(values, VAL_GET_VALUE,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, pv);
            if (failed(hr)) return 0;
            try {
                if (pv.get(JAVA_SHORT, 0) != VT_DATE) return 0;
                double oaDate = pv.get(JAVA_DOUBLE, 8);
                return Math.round((oaDate - OA_EPOCH_DAYS) * SECONDS_PER_DAY);
            } finally {
                propVariantClear(pv);
            }
        }
    }

    private boolean getGuid(MemorySegment values, MemorySegment key, MemorySegment outBuf) {
        int hr = call(values, VAL_GET_GUID,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, outBuf);
        return !failed(hr);
    }

    private void setString(MemorySegment values, MemorySegment key, MemorySegment valueW) {
        call(values, VAL_SET_STRING, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, valueW);
    }

    private void setU8(MemorySegment values, MemorySegment key, long value) {
        call(values, VAL_SET_U8, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG), key, value);
    }

    private void setGuid(MemorySegment values, MemorySegment key, MemorySegment guid) {
        call(values, VAL_SET_GUID, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, guid);
    }

    /** Builds an IPortableDevicePropVariantCollection holding a single VT_LPWSTR object id. */
    private MemorySegment objectIdCollection(String objectId) throws IOException {
        var coll = createInstance(CLSID_PROPVARIANT_COLLECTION, IID_PROPVARIANT_COLLECTION,
            "create object-id collection");
        try (var arena = Arena.ofConfined()) {
            var pv = arena.allocate(PROPVARIANT_SIZE); // zero-filled
            pv.set(JAVA_SHORT, 0, VT_LPWSTR);
            pv.set(ADDRESS, 8, wstr(arena, objectId));
            // Add deep-copies the PROPVARIANT (including the string), so the arena copy is safe to free.
            checkHr(call(coll, PVCOLL_ADD, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), pv),
                "IPortableDevicePropVariantCollection::Add");
            return coll;
        } catch (Throwable t) {
            release(coll);
            if (t instanceof IOException io) throw io;
            throw new IOException("Failed to build object-id collection", t);
        }
    }

    private String readNewObjectId(MemorySegment stream) {
        var dataStream = queryInterface(stream, IID_DATA_STREAM);
        if (MemorySegment.NULL.equals(dataStream)) return "";
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(ADDRESS);
            int hr = call(dataStream, DATASTREAM_GET_OBJECT_ID,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), out);
            if (failed(hr)) return "";
            var ptr = out.get(ADDRESS, 0);
            var id = readWstr(ptr);
            coTaskMemFree(ptr);
            return id;
        } finally {
            release(dataStream);
        }
    }

    private void copyStreamToFile(MemorySegment stream, OutputStream out, int bufSize) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var buf = arena.allocate(bufSize);
            var readOut = arena.allocate(JAVA_INT);
            var desc = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
            while (true) {
                int hr = call(stream, STREAM_READ, desc, buf, bufSize, readOut);
                checkHr(hr, "IStream::Read");
                int got = readOut.get(JAVA_INT, 0);
                if (got <= 0) break;
                out.write(buf.asSlice(0, got).toArray(JAVA_BYTE));
            }
        }
    }

    private void copyFileToStream(InputStream in, MemorySegment stream, int bufSize) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var buf = arena.allocate(bufSize);
            var written = arena.allocate(JAVA_INT);
            var desc = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
            byte[] heap = new byte[bufSize];
            int r;
            while ((r = in.read(heap)) > 0) {
                MemorySegment.copy(heap, 0, buf, JAVA_BYTE, 0, r);
                checkHr(call(stream, STREAM_WRITE, desc, buf, r, written), "IStream::Write");
            }
        }
    }
}
