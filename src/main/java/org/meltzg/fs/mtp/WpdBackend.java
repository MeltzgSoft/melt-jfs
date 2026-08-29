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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.meltzg.fs.mtp.MtpBackend.emptyToNull;
import static org.meltzg.fs.mtp.WpdCom.*;

/**
 * Windows-native MTP backend implemented on top of Windows Portable Devices (WPD).
 *
 * <p>The provider sees WPD object ids as opaque strings. This backend only derives the underlying
 * numeric MTP handle for raw pass-through commands such as GetPartialObject and BeginEditObject.
 */
class WpdBackend implements MtpBackend {

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
    private static final MemorySegment KEY_DEVICE_PROTOCOL;
    private static final MemorySegment KEY_DEVICE_SERIAL_NUMBER;

    private static final MemorySegment MTP_EXT_CATEGORY;
    private static final MemorySegment KEY_COMMON_COMMAND_CATEGORY;
    private static final MemorySegment KEY_COMMON_COMMAND_ID;
    private static final MemorySegment KEY_COMMON_HRESULT;
    private static final MemorySegment KEY_MTP_OP_CODE;
    private static final MemorySegment KEY_MTP_OP_PARAMS;
    private static final MemorySegment KEY_MTP_RESPONSE_CODE;
    private static final MemorySegment KEY_MTP_TRANSFER_CONTEXT;
    private static final MemorySegment KEY_MTP_TRANSFER_TOTAL_SIZE;
    private static final MemorySegment KEY_MTP_NUM_BYTES_TO_READ;
    private static final MemorySegment KEY_MTP_NUM_BYTES_TO_WRITE;
    private static final MemorySegment KEY_MTP_TRANSFER_DATA;
    private static final MemorySegment KEY_MTP_OPERATION_CODES;

    private static final MemorySegment CONTENT_TYPE_FOLDER;
    private static final MemorySegment CONTENT_TYPE_FUNCTIONAL_OBJECT;
    private static final MemorySegment CONTENT_TYPE_GENERIC_FILE;
    private static final MemorySegment CONTENT_TYPE_AUDIO;
    private static final MemorySegment FORMAT_PROPERTIES_ONLY;
    private static final MemorySegment FORMAT_UNSPECIFIED;
    private static final MemorySegment FORMAT_MP3;
    private static final MemorySegment FORMAT_WAV;
    private static final MemorySegment FORMAT_WMA;
    private static final MemorySegment FORMAT_OGG;
    private static final MemorySegment FORMAT_AAC;
    private static final MemorySegment FORMAT_FLAC;
    private static final MemorySegment FORMAT_M4A;
    private static final MemorySegment FORMAT_MP2;
    private static final MemorySegment FUNCTIONAL_CATEGORY_STORAGE;

    private static final String WPD_DEVICE_OBJECT_ID = "DEVICE";

    private static final int MGR_GET_DEVICES = 3;
    private static final int MGR_FRIENDLY_NAME = 5;
    private static final int MGR_DESCRIPTION = 6;
    private static final int MGR_MANUFACTURER = 7;
    private static final int DEV_OPEN = 3;
    private static final int DEV_SEND_COMMAND = 4;
    private static final int DEV_CONTENT = 5;
    private static final int DEV_CLOSE = 8;
    private static final int CONTENT_ENUM = 3;
    private static final int CONTENT_PROPERTIES = 4;
    private static final int CONTENT_TRANSFER = 5;
    private static final int CONTENT_CREATE_PROPS = 6;
    private static final int CONTENT_CREATE_DATA = 7;
    private static final int CONTENT_DELETE = 8;
    private static final int CONTENT_MOVE = 11;
    private static final int ENUM_NEXT = 3;
    private static final int PROPS_GET_VALUES = 5;
    private static final int PROPS_SET_VALUES = 6;
    private static final int RES_GET_STREAM = 5;
    private static final int VAL_GET_VALUE = 6;
    private static final int VAL_SET_STRING = 7;
    private static final int VAL_GET_STRING = 8;
    private static final int VAL_SET_U4 = 9;
    private static final int VAL_GET_U4 = 10;
    private static final int VAL_SET_U8 = 13;
    private static final int VAL_GET_U8 = 14;
    private static final int VAL_GET_ERROR = 20;
    private static final int VAL_SET_GUID = 27;
    private static final int VAL_GET_GUID = 28;
    private static final int VAL_SET_BUFFER = 29;
    private static final int VAL_GET_BUFFER = 30;
    private static final int VAL_SET_PVCOLL = 33;
    private static final int VAL_GET_PVCOLL = 34;
    private static final int KEYCOLL_ADD = 5;
    private static final int PVCOLL_GET_COUNT = 3;
    private static final int PVCOLL_GET_AT = 4;
    private static final int PVCOLL_ADD = 5;
    private static final int STREAM_READ = 3;
    private static final int STREAM_WRITE = 4;
    private static final int STREAM_COMMIT = 8;
    private static final int DATASTREAM_GET_OBJECT_ID = 14;

    private static final int PORTABLE_DEVICE_DELETE_NO_RECURSION = 0;
    private static final int STORAGE_ENUM_ATTEMPTS = 3;
    private static final long STORAGE_ENUM_RETRY_MILLIS = 250;
    private static final int STREAM_FALLBACK_BUFFER = 1 << 16;
    private static final int MTP_TRANSFER_CHUNK = 256 * 1024;

    private static final int OP_GET_PARTIAL_OBJECT = 0x101B;
    private static final int OP_GET_PARTIAL_OBJECT_64 = 0x95C1;
    private static final int OP_SEND_PARTIAL_OBJECT = 0x95C2;
    private static final int OP_TRUNCATE_OBJECT = 0x95C3;
    private static final int OP_BEGIN_EDIT_OBJECT = 0x95C4;
    private static final int OP_END_EDIT_OBJECT = 0x95C5;
    private static final int MTP_RESPONSE_OK = 0x2001;
    private static final int MTP_RESPONSE_OP_NOT_SUPPORTED = 0x2005;

    private static final int PID_GET_SUPPORTED_VENDOR_OPCODES = 11;
    private static final int PID_EXECUTE_WITHOUT_DATA_PHASE = 12;
    private static final int PID_EXECUTE_WITH_DATA_TO_READ = 13;
    private static final int PID_EXECUTE_WITH_DATA_TO_WRITE = 14;
    private static final int PID_READ_DATA = 15;
    private static final int PID_WRITE_DATA = 16;
    private static final int PID_END_DATA_TRANSFER = 17;

    private static final boolean UPLOAD_AUDIO_AS_GENERIC = Boolean.parseBoolean(
        System.getProperty("melt-jfs.wpd.uploadAudioAsGeneric", "false"));
    private static final boolean RECYCLE_AFTER_PARTIAL_READ = Boolean.parseBoolean(
        System.getProperty("melt-jfs.wpd.recycleAfterPartialRead", "true"));
    private static final boolean USE_TEMPORARY_UPLOAD_NAMES = Boolean.parseBoolean(
        System.getProperty("melt-jfs.wpd.temporaryUploadNames", "false"));
    private static final boolean USE_MTP_PARTIAL_READS = Boolean.parseBoolean(
        System.getProperty("melt-jfs.wpd.mtpPartialReads", "true"));

    private static final Pattern VID = Pattern.compile("vid_([0-9a-fA-F]{4})");
    private static final Pattern PID = Pattern.compile("pid_([0-9a-fA-F]{4})");

    private static final double OA_EPOCH_DAYS = 25569.0;
    private static final double SECONDS_PER_DAY = 86400.0;

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

        String object = "ef6b490d-5cd8-437a-affc-da8b60ee4a3c";
        KEY_PARENT_ID = propertyKey(a, object, 3);
        KEY_NAME = propertyKey(a, object, 4);
        KEY_OBJECT_FORMAT = propertyKey(a, object, 6);
        KEY_CONTENT_TYPE = propertyKey(a, object, 7);
        KEY_OBJECT_SIZE = propertyKey(a, object, 11);
        KEY_ORIGINAL_FILE_NAME = propertyKey(a, object, 12);
        KEY_DATE_MODIFIED = propertyKey(a, object, 19);
        KEY_FUNCTIONAL_CATEGORY = propertyKey(a, "8f052d93-abca-4fc5-a5ac-b01df4dbe598", 2);
        KEY_STORAGE_CAPACITY = propertyKey(a, "01a3057a-74d6-4e80-bea7-dc4c212ce50a", 4);
        KEY_STORAGE_FREE_SPACE = propertyKey(a, "01a3057a-74d6-4e80-bea7-dc4c212ce50a", 5);
        KEY_RESOURCE_DEFAULT = propertyKey(a, "e81e79be-34f0-41bf-b53f-f1a06ae87842", 0);

        String media = "2ed8ba05-0ad3-42dc-b0d0-bc95ac396ac8";
        KEY_MEDIA_TITLE = propertyKey(a, media, 18);
        KEY_MEDIA_DURATION = propertyKey(a, media, 19);
        KEY_MEDIA_ARTIST = propertyKey(a, media, 24);
        KEY_MEDIA_GENRE = propertyKey(a, media, 32);
        String music = "b324f56a-dc5d-46e5-b6df-d2ea414888c6";
        KEY_MUSIC_ALBUM = propertyKey(a, music, 3);
        KEY_MUSIC_TRACK = propertyKey(a, music, 4);

        String client = "204d9f0c-2292-4080-9f42-40664e70f859";
        KEY_CLIENT_NAME = propertyKey(a, client, 2);
        KEY_CLIENT_MAJOR_VERSION = propertyKey(a, client, 3);
        KEY_CLIENT_MINOR_VERSION = propertyKey(a, client, 4);
        KEY_CLIENT_REVISION = propertyKey(a, client, 5);

        String device = "26d4979a-e643-4626-9e2b-736dc0c92fdc";
        KEY_DEVICE_PROTOCOL = propertyKey(a, device, 6);
        KEY_DEVICE_SERIAL_NUMBER = propertyKey(a, device, 9);

        String common = "f0422a9c-5dc8-4440-b5bd-5df28835658a";
        KEY_COMMON_COMMAND_CATEGORY = propertyKey(a, common, 1001);
        KEY_COMMON_COMMAND_ID = propertyKey(a, common, 1002);
        KEY_COMMON_HRESULT = propertyKey(a, common, 1003);

        String mtp = "4d545058-1a2e-4106-a357-771e0819fc56";
        MTP_EXT_CATEGORY = guid(a, mtp);
        KEY_MTP_OP_CODE = propertyKey(a, mtp, 1001);
        KEY_MTP_OP_PARAMS = propertyKey(a, mtp, 1002);
        KEY_MTP_RESPONSE_CODE = propertyKey(a, mtp, 1003);
        KEY_MTP_OPERATION_CODES = propertyKey(a, mtp, 1005);
        KEY_MTP_TRANSFER_CONTEXT = propertyKey(a, mtp, 1006);
        KEY_MTP_TRANSFER_TOTAL_SIZE = propertyKey(a, mtp, 1007);
        KEY_MTP_NUM_BYTES_TO_READ = propertyKey(a, mtp, 1008);
        KEY_MTP_NUM_BYTES_TO_WRITE = propertyKey(a, mtp, 1010);
        KEY_MTP_TRANSFER_DATA = propertyKey(a, mtp, 1012);

        CONTENT_TYPE_FOLDER = guid(a, "27e2e392-a111-48e0-ab0c-e17705a05f85");
        CONTENT_TYPE_FUNCTIONAL_OBJECT = guid(a, "99ed0160-17ff-4c44-9d98-1d7a6f941921");
        CONTENT_TYPE_GENERIC_FILE = guid(a, "0085e0a6-8d34-45d7-bc5c-447e59c73d48");
        CONTENT_TYPE_AUDIO = guid(a, "4ad2c85e-5e2d-45e5-8864-4f229e3c6cf0");
        FUNCTIONAL_CATEGORY_STORAGE = guid(a, "23f05bbc-15de-4c2a-a55b-a9af5ce412ef");

        FORMAT_PROPERTIES_ONLY = guid(a, "30010000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_UNSPECIFIED = guid(a, "30000000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_WAV = guid(a, "30080000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_MP3 = guid(a, "30090000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_WMA = guid(a, "b9010000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_OGG = guid(a, "b9020000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_AAC = guid(a, "b9030000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_FLAC = guid(a, "b9060000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_MP2 = guid(a, "b9830000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_M4A = guid(a, "30aba7ac-6ffd-4c23-a359-3e9b52f3f1c8");
    }

    private static final WpdBackend INSTANCE = new WpdBackend();

    static WpdBackend getInstance() {
        return INSTANCE;
    }

    private WpdBackend() {}

    private volatile int partialReadOpcode;

    private record WpdDevice(MemorySegment device, MemorySegment content, MemorySegment properties,
                             AtomicReference<List<StorageResult>> storages) implements DeviceHandle {
        WpdDevice(MemorySegment device, MemorySegment content, MemorySegment properties) {
            this(device, content, properties, new AtomicReference<>());
        }
    }

    private static WpdDevice asDevice(DeviceHandle handle) {
        return (WpdDevice) handle;
    }

    private static int call(MemorySegment obj, int methodIndex, FunctionDescriptor descriptor, Object... args) {
        Object[] withThis = new Object[args.length + 1];
        withThis[0] = obj;
        System.arraycopy(args, 0, withThis, 1, args.length);
        try {
            return ((Number) WpdCom.method(obj, methodIndex, descriptor)
                .invokeWithArguments(withThis)).intValue();
        } catch (Throwable t) {
            throw new RuntimeException("COM call failed at vtable index " + methodIndex, t);
        }
    }

    @Override
    public Scan scan() throws IOException {
        ensureInitialized();
        var manager = createInstance(CLSID_DEVICE_MANAGER, IID_DEVICE_MANAGER, "create PortableDeviceManager");
        boolean keepManager = false;
        try {
            var ids = listDeviceIds(manager);
            keepManager = true;
            return new WpdScan(manager, ids);
        } finally {
            if (!keepManager) releaseQuietly(manager);
        }
    }

    private List<String> listDeviceIds(MemorySegment manager) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var countOut = arena.allocate(JAVA_INT);
            checkHr(call(manager, MGR_GET_DEVICES,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    MemorySegment.NULL, countOut),
                "IPortableDeviceManager::GetDevices(count)");
            int count = countOut.get(JAVA_INT, 0);
            if (count <= 0) return List.of();

            var ids = new ArrayList<String>(count);
            var idArray = arena.allocate(ADDRESS.byteSize() * count);
            checkHr(call(manager, MGR_GET_DEVICES,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    idArray, countOut),
                "IPortableDeviceManager::GetDevices");
            int actual = countOut.get(JAVA_INT, 0);
            for (int i = 0; i < actual; i++) {
                var ptr = idArray.getAtIndex(ADDRESS, i);
                ids.add(readWstr(ptr));
                coTaskMemFree(ptr);
            }
            return List.copyOf(ids);
        }
    }

    private final class WpdScan implements Scan {
        private final MemorySegment manager;
        private final List<String> deviceIds;

        WpdScan(MemorySegment manager, List<String> deviceIds) {
            this.manager = manager;
            this.deviceIds = deviceIds;
        }

        @Override
        public List<String> signatures() {
            return deviceIds;
        }

        @Override
        public OpenedDevice open(int index) throws IOException {
            return openDevice(manager, deviceIds.get(index));
        }

        @Override
        public void close() {
            release(manager);
        }
    }

    private OpenedDevice openDevice(MemorySegment manager, String deviceId) throws IOException {
        var device = MemorySegment.NULL;
        var content = MemorySegment.NULL;
        var properties = MemorySegment.NULL;
        boolean handedOff = false;

        try (var arena = Arena.ofConfined()) {
            device = createInstance(CLSID_DEVICE_FTM, IID_DEVICE, "create PortableDevice");
            openPortableDevice(device, deviceId, arena);
            content = contentInterface(device, arena);
            properties = propertiesInterface(content, arena);

            if (!isMtpDevice(properties)) {
                return null;
            }

            var id = parseIdentifier(deviceId, deviceSerialNumber(properties));
            var info = new MTPDeviceInfo(
                id,
                managerString(manager, deviceId, MGR_FRIENDLY_NAME, arena),
                managerString(manager, deviceId, MGR_DESCRIPTION, arena),
                managerString(manager, deviceId, MGR_MANUFACTURER, arena),
                0,
                0);
            handedOff = true;
            return new OpenedDevice(id, info, new WpdDevice(device, content, properties));
        } catch (Throwable t) {
            if (t instanceof IOException io) throw io;
            throw new IOException("Failed to open WPD device " + deviceId, t);
        } finally {
            if (!handedOff) closeInterfaces(device, content, properties);
        }
    }

    private void openPortableDevice(MemorySegment device, String deviceId, Arena arena) throws IOException {
        var clientInfo = createInstance(CLSID_VALUES, IID_VALUES, "create WPD client info");
        try {
            setString(clientInfo, KEY_CLIENT_NAME, wstr(arena, "melt-jfs"));
            setU4(clientInfo, KEY_CLIENT_MAJOR_VERSION, 1);
            setU4(clientInfo, KEY_CLIENT_MINOR_VERSION, 0);
            setU4(clientInfo, KEY_CLIENT_REVISION, 0);
            checkHr(call(device, DEV_OPEN,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    wstr(arena, deviceId), clientInfo),
                "IPortableDevice::Open");
        } finally {
            release(clientInfo);
        }
    }

    private MemorySegment contentInterface(MemorySegment device, Arena arena) throws IOException {
        var out = arena.allocate(ADDRESS);
        checkHr(call(device, DEV_CONTENT,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), out),
            "IPortableDevice::Content");
        return out.get(ADDRESS, 0);
    }

    private MemorySegment propertiesInterface(MemorySegment content, Arena arena) throws IOException {
        var out = arena.allocate(ADDRESS);
        checkHr(call(content, CONTENT_PROPERTIES,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), out),
            "IPortableDeviceContent::Properties");
        return out.get(ADDRESS, 0);
    }

    private String managerString(MemorySegment manager, String deviceId, int methodIndex, Arena arena) {
        var required = arena.allocate(JAVA_INT);
        var descriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        int firstHr = call(manager, methodIndex, descriptor, wstr(arena, deviceId), MemorySegment.NULL, required);
        if (failed(firstHr) || required.get(JAVA_INT, 0) <= 0) return "";

        var buffer = arena.allocate(JAVA_CHAR, required.get(JAVA_INT, 0));
        int secondHr = call(manager, methodIndex, descriptor, wstr(arena, deviceId), buffer, required);
        return failed(secondHr) ? "" : readWstr(buffer);
    }

    private boolean isMtpDevice(MemorySegment properties) {
        var values = getValues(properties, WPD_DEVICE_OBJECT_ID, KEY_DEVICE_PROTOCOL);
        if (MemorySegment.NULL.equals(values)) return true;
        try {
            var protocol = getString(values, KEY_DEVICE_PROTOCOL);
            return protocol.isBlank() || protocol.regionMatches(true, 0, "MTP", 0, 3);
        } finally {
            release(values);
        }
    }

    private String deviceSerialNumber(MemorySegment properties) {
        var values = getValues(properties, WPD_DEVICE_OBJECT_ID, KEY_DEVICE_SERIAL_NUMBER);
        if (MemorySegment.NULL.equals(values)) return "";
        try {
            return getString(values, KEY_DEVICE_SERIAL_NUMBER);
        } finally {
            release(values);
        }
    }

    private MTPDeviceIdentifier parseIdentifier(String deviceId, String deviceSerial) {
        var lower = deviceId.toLowerCase(Locale.ROOT);
        int vendor = parseHexGroup(VID, lower);
        int product = parseHexGroup(PID, lower);
        String rawSerial = deviceSerial;
        if (rawSerial == null || rawSerial.isBlank()) {
            var parts = deviceId.split("#");
            rawSerial = parts.length >= 3 ? parts[2] : deviceId;
        }
        var serial = rawSerial.replaceAll("[^0-9A-Za-z_]+", "_").replaceAll("^_+|_+$", "");
        return new MTPDeviceIdentifier(vendor, product, serial.isEmpty() ? "unknown" : serial);
    }

    private static int parseHexGroup(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1), 16) : 0;
    }

    @Override
    public void releaseDevice(DeviceHandle handle) {
        var d = asDevice(handle);
        closeInterfaces(d.device(), d.content(), d.properties());
    }

    private static void closeInterfaces(MemorySegment device, MemorySegment content, MemorySegment properties) {
        releaseQuietly(properties);
        releaseQuietly(content);
        if (!isNull(device)) {
            try {
                call(device, DEV_CLOSE, FunctionDescriptor.of(JAVA_INT, ADDRESS));
            } catch (RuntimeException ignored) {
            }
            releaseQuietly(device);
        }
    }

    private static void releaseQuietly(MemorySegment obj) {
        try {
            release(obj);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || MemorySegment.NULL.equals(segment);
    }

    @Override
    public List<StorageResult> listStorages(DeviceHandle handle) {
        var d = asDevice(handle);
        var cached = d.storages().get();
        if (cached != null) return cached;

        IOException last = null;
        for (int attempt = 0; attempt < STORAGE_ENUM_ATTEMPTS; attempt++) {
            if (attempt > 0) sleepQuietly(STORAGE_ENUM_RETRY_MILLIS);
            try {
                var storages = enumerateStorages(d);
                if (!storages.isEmpty()) {
                    d.storages().compareAndSet(null, List.copyOf(storages));
                    return d.storages().get();
                }
                return storages;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new RuntimeException("Failed to list WPD storages", last);
    }

    private List<StorageResult> enumerateStorages(WpdDevice d) throws IOException {
        var storages = new ArrayList<StorageResult>();
        for (var childId : enumChildren(d.content(), WPD_DEVICE_OBJECT_ID)) {
            var values = getValues(d.properties(), childId, KEY_FUNCTIONAL_CATEGORY, KEY_NAME);
            if (MemorySegment.NULL.equals(values)) {
                throw new IOException("Failed to classify WPD functional object: " + childId);
            }
            try (var arena = Arena.ofConfined()) {
                var category = arena.allocate(GUID_SIZE);
                if (getGuid(values, KEY_FUNCTIONAL_CATEGORY, category)
                        && guidEquals(category, FUNCTIONAL_CATEGORY_STORAGE)) {
                    var name = getString(values, KEY_NAME);
                    storages.add(new StorageResult(name.isBlank() ? childId : name, childId));
                }
            } finally {
                release(values);
            }
        }
        return storages;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public StorageResult findStorage(DeviceHandle handle, String storageName) {
        return listStorages(handle).stream()
            .filter(storage -> storage.name().equals(storageName))
            .findFirst()
            .orElse(null);
    }

    @Override
    public long getCapacity(DeviceHandle handle, String storageId) {
        return readStorageU8(asDevice(handle), storageId, KEY_STORAGE_CAPACITY);
    }

    @Override
    public long getFreeSpace(DeviceHandle handle, String storageId) {
        return readStorageU8(asDevice(handle), storageId, KEY_STORAGE_FREE_SPACE);
    }

    private long readStorageU8(WpdDevice d, String storageId, MemorySegment key) {
        var values = getValues(d.properties(), storageId, key);
        if (MemorySegment.NULL.equals(values)) return -1;
        try {
            return getU8(values, key);
        } finally {
            release(values);
        }
    }

    @Override
    public MTPItemInfo[] getChildItems(DeviceHandle handle, String storageId, String parentId) throws IOException {
        var d = asDevice(handle);
        String parent = parentForWpd(parentId, storageId);
        var items = new ArrayList<MTPItemInfo>();
        for (var childId : enumChildren(d.content(), parent)) {
            var values = getValues(d.properties(), childId,
                KEY_CONTENT_TYPE, KEY_ORIGINAL_FILE_NAME, KEY_NAME, KEY_OBJECT_SIZE, KEY_DATE_MODIFIED);
            if (MemorySegment.NULL.equals(values)) continue;
            try (var arena = Arena.ofConfined()) {
                boolean isFile = true;
                var contentType = arena.allocate(GUID_SIZE);
                if (getGuid(values, KEY_CONTENT_TYPE, contentType)) {
                    isFile = !(guidEquals(contentType, CONTENT_TYPE_FOLDER)
                        || guidEquals(contentType, CONTENT_TYPE_FUNCTIONAL_OBJECT));
                }
                String name = firstNonBlank(getString(values, KEY_ORIGINAL_FILE_NAME),
                    getString(values, KEY_NAME), childId);
                long size = Math.max(getU8(values, KEY_OBJECT_SIZE), 0);
                long modified = getDateEpochSeconds(values, KEY_DATE_MODIFIED);
                items.add(new MTPItemInfo(parent, childId, storageId, isFile, size, modified, name));
            } finally {
                release(values);
            }
        }
        return items.toArray(MTPItemInfo[]::new);
    }

    @Override
    public MTPTrackMetadata getTrackMetadata(DeviceHandle handle, String itemId) throws IOException {
        var d = asDevice(handle);
        var values = getValues(d.properties(), itemId,
            KEY_MEDIA_TITLE, KEY_MEDIA_ARTIST, KEY_MUSIC_ALBUM, KEY_MEDIA_GENRE,
            KEY_MUSIC_TRACK, KEY_MEDIA_DURATION, KEY_NAME);
        if (MemorySegment.NULL.equals(values)) return null;
        try {
            String title = emptyToNull(getString(values, KEY_MEDIA_TITLE));
            String artist = emptyToNull(getString(values, KEY_MEDIA_ARTIST));
            String album = emptyToNull(getString(values, KEY_MUSIC_ALBUM));
            String genre = emptyToNull(getString(values, KEY_MEDIA_GENRE));
            int trackNumber = (int) Math.max(getU4(values, KEY_MUSIC_TRACK), 0);
            long duration = Math.max(getU8(values, KEY_MEDIA_DURATION), 0);
            boolean recognized = artist != null || album != null || genre != null
                || trackNumber > 0 || duration > 0;
            if (title == null && recognized) title = emptyToNull(getString(values, KEY_NAME));
            var meta = new MTPTrackMetadata(title, artist, album, genre, trackNumber, duration);
            return meta.isEmpty() ? null : meta;
        } finally {
            release(values);
        }
    }

    @Override
    public String createFolder(DeviceHandle handle, String name, String parentId, String storageId)
            throws IOException {
        var d = asDevice(handle);
        try (var arena = Arena.ofConfined()) {
            var values = createInstance(CLSID_VALUES, IID_VALUES, "create folder properties");
            try {
                setString(values, KEY_PARENT_ID, wstr(arena, parentForWpd(parentId, storageId)));
                setString(values, KEY_NAME, wstr(arena, name));
                setString(values, KEY_ORIGINAL_FILE_NAME, wstr(arena, name));
                setGuid(values, KEY_CONTENT_TYPE, CONTENT_TYPE_FOLDER);
                setGuid(values, KEY_OBJECT_FORMAT, FORMAT_PROPERTIES_ONLY);
                var idOut = arena.allocate(ADDRESS);
                checkHr(call(d.content(), CONTENT_CREATE_PROPS,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), values, idOut),
                    "CreateObjectWithPropertiesOnly");
                var ptr = idOut.get(ADDRESS, 0);
                try {
                    return readWstr(ptr);
                } finally {
                    coTaskMemFree(ptr);
                }
            } finally {
                release(values);
            }
        }
    }

    @Override
    public void deleteObject(DeviceHandle handle, String itemId) throws IOException {
        var d = asDevice(handle);
        var ids = objectIdCollection(itemId);
        try {
            checkHr(call(d.content(), CONTENT_DELETE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    PORTABLE_DEVICE_DELETE_NO_RECURSION, ids, MemorySegment.NULL),
                "IPortableDeviceContent::Delete");
        } finally {
            release(ids);
        }
    }

    @Override
    public void getFile(DeviceHandle handle, String itemId, String destPath) throws IOException {
        var d = asDevice(handle);
        try (var arena = Arena.ofConfined()) {
            var resourcesOut = arena.allocate(ADDRESS);
            checkHr(call(d.content(), CONTENT_TRANSFER,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), resourcesOut),
                "IPortableDeviceContent::Transfer");
            var resources = resourcesOut.get(ADDRESS, 0);
            try {
                var optimal = arena.allocate(JAVA_INT);
                var streamOut = arena.allocate(ADDRESS);
                checkHr(call(resources, RES_GET_STREAM,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                        wstr(arena, itemId), KEY_RESOURCE_DEFAULT, STGM_READ, optimal, streamOut),
                    "IPortableDeviceResources::GetStream");
                var stream = streamOut.get(ADDRESS, 0);
                try (var out = Files.newOutputStream(Path.of(destPath))) {
                    copyStreamToFile(stream, out, transferBufferSize(optimal.get(JAVA_INT, 0)));
                } finally {
                    release(stream);
                }
            } finally {
                release(resources);
            }
        }
    }

    @Override
    public boolean reopenClearsNameReservations() {
        return false;
    }

    @Override
    public boolean supportsPartialReads() {
        return true;
    }

    @Override
    public boolean recycleBeforeMutationAfterPartialRead() {
        return RECYCLE_AFTER_PARTIAL_READ;
    }

    @Override
    public boolean uploadWithTemporaryName() {
        return USE_TEMPORARY_UPLOAD_NAMES;
    }

    @Override
    public byte[] readPartial(DeviceHandle handle, String itemId, long offset, int maxBytes) throws IOException {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative: " + offset);
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must be non-negative: " + maxBytes);
        if (maxBytes == 0) return new byte[0];

        if (USE_MTP_PARTIAL_READS) {
            return readPartialViaMtpPassThrough(handle, itemId, offset, maxBytes);
        }

        var d = asDevice(handle);
        try (var arena = Arena.ofConfined()) {
            var resourcesOut = arena.allocate(ADDRESS);
            checkHr(call(d.content(), CONTENT_TRANSFER,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), resourcesOut),
                "IPortableDeviceContent::Transfer");
            var resources = resourcesOut.get(ADDRESS, 0);
            try {
                var optimal = arena.allocate(JAVA_INT);
                var streamOut = arena.allocate(ADDRESS);
                checkHr(call(resources, RES_GET_STREAM,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                        wstr(arena, itemId), KEY_RESOURCE_DEFAULT, STGM_READ, optimal, streamOut),
                    "IPortableDeviceResources::GetStream");
                var stream = streamOut.get(ADDRESS, 0);
                try {
                    return readStreamRange(stream, offset, maxBytes,
                        transferBufferSize(optimal.get(JAVA_INT, 0)));
                } finally {
                    release(stream);
                }
            } finally {
                release(resources);
            }
        }
    }

    private byte[] readPartialViaMtpPassThrough(DeviceHandle handle, String itemId, long offset, int maxBytes)
            throws IOException {
        var d = asDevice(handle);
        long objectHandle = parseObjectHandle(itemId);
        int cached = partialReadOpcode;
        int[] opcodes = cached == OP_GET_PARTIAL_OBJECT_64
            ? new int[] {OP_GET_PARTIAL_OBJECT_64, OP_GET_PARTIAL_OBJECT}
            : new int[] {OP_GET_PARTIAL_OBJECT, OP_GET_PARTIAL_OBJECT_64};
        IOException last = null;
        for (int opcode : opcodes) {
            if (opcode == OP_GET_PARTIAL_OBJECT && (offset >>> 32) != 0) continue;
            try {
                byte[] result = getPartialObject(d, objectHandle, offset, maxBytes, opcode);
                if (result != null) {
                    partialReadOpcode = opcode;
                    return result;
                }
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new IOException("device supports neither GetPartialObject nor GetPartialObject64 for id: " + itemId);
    }

    private byte[] getPartialObject(WpdDevice d, long objectHandle, long offset, int maxBytes, int opcode)
            throws IOException {
        var start = beginDataToRead(d.device(), objectHandle, offset, maxBytes, opcode);
        if (start.operationUnsupported()) return null;

        byte[] out = new byte[start.transferSize()];
        int read = 0;
        int response = -1;
        IOException readFailure = null;
        try {
            read = readDataPhase(d.device(), start.context(), out);
        } catch (IOException e) {
            readFailure = e;
            throw e;
        } finally {
            try {
                response = endDataTransfer(d.device(), start.context());
            } catch (IOException endFailure) {
                if (readFailure != null) {
                    readFailure.addSuppressed(endFailure);
                } else {
                    throw endFailure;
                }
            }
        }
        if (response == MTP_RESPONSE_OP_NOT_SUPPORTED) return null;
        if (response >= 0) checkMtpResponse(response, "GetPartialObject");
        return read == out.length ? out : Arrays.copyOf(out, read);
    }

    private record ReadTransfer(String context, int transferSize, boolean operationUnsupported) {
        static ReadTransfer unsupportedTransfer() {
            return new ReadTransfer("", 0, true);
        }
    }

    private ReadTransfer beginDataToRead(MemorySegment device, long objectHandle, long offset,
                                         int maxBytes, int opcode) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var command = createCommand(PID_EXECUTE_WITH_DATA_TO_READ);
            try {
                setU4(command, KEY_MTP_OP_CODE, opcode);
                setOpParams(command, arena, partialReadParams(objectHandle, offset, maxBytes, opcode));
                var results = sendCommand(device, command, arena);
                try {
                    checkDriverHr(results, "initiate GetPartialObject");
                    int response = getOptionalU4(results, KEY_MTP_RESPONSE_CODE);
                    if (response == MTP_RESPONSE_OP_NOT_SUPPORTED) return ReadTransfer.unsupportedTransfer();
                    if (response >= 0 && response != MTP_RESPONSE_OK) {
                        throw new IOException("GetPartialObject failed (MTP response 0x"
                            + Integer.toHexString(response) + ")");
                    }
                    String context = getString(results, KEY_MTP_TRANSFER_CONTEXT);
                    if (context.isBlank()) throw new IOException("GetPartialObject returned no transfer context");
                    long total = readUnsigned(results, KEY_MTP_TRANSFER_TOTAL_SIZE);
                    if (total < 0) total = maxBytes;
                    return new ReadTransfer(context, (int) Math.min(total, maxBytes), false);
                } finally {
                    release(results);
                }
            } finally {
                release(command);
            }
        }
    }

    private static int[] partialReadParams(long objectHandle, long offset, int maxBytes, int opcode) {
        if (opcode == OP_GET_PARTIAL_OBJECT) {
            return new int[] {(int) objectHandle, (int) offset, maxBytes};
        }
        return new int[] {(int) objectHandle, (int) offset, (int) (offset >>> 32), maxBytes};
    }

    private int readDataPhase(MemorySegment device, String context, byte[] out) throws IOException {
        int read = 0;
        while (read < out.length) {
            int want = Math.min(out.length - read, MTP_TRANSFER_CHUNK);
            try (var arena = Arena.ofConfined()) {
                var command = createCommand(PID_READ_DATA);
                try {
                    setString(command, KEY_MTP_TRANSFER_CONTEXT, wstr(arena, context));
                    setBuffer(command, KEY_MTP_TRANSFER_DATA, arena.allocate(want), want);
                    setU4(command, KEY_MTP_NUM_BYTES_TO_READ, want);
                    var results = sendCommand(device, command, arena);
                    try {
                        checkDriverHr(results, "READ_DATA");
                        int got = copyBufferValue(results, out, read);
                        if (got <= 0) break;
                        read += got;
                    } finally {
                        release(results);
                    }
                } finally {
                    release(command);
                }
            }
        }
        return read;
    }

    private int endDataTransfer(MemorySegment device, String context) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var command = createCommand(PID_END_DATA_TRANSFER);
            try {
                setString(command, KEY_MTP_TRANSFER_CONTEXT, wstr(arena, context));
                var results = sendCommand(device, command, arena);
                try {
                    checkDriverHr(results, "END_DATA_TRANSFER");
                    return getOptionalU4(results, KEY_MTP_RESPONSE_CODE);
                } finally {
                    release(results);
                }
            } finally {
                release(command);
            }
        }
    }

    @Override
    public boolean supportsObjectEditing(DeviceHandle handle) {
        try {
            return deviceSupportsOperation(asDevice(handle).device(), OP_BEGIN_EDIT_OBJECT);
        } catch (IOException queryUnsupported) {
            return true;
        }
    }

    @Override
    public void overwriteFile(DeviceHandle handle, String itemId, String localPath) throws IOException {
        var device = asDevice(handle).device();
        long objectHandle = parseObjectHandle(itemId);
        long size = Files.size(Path.of(localPath));
        if (size > 0xFFFFFFFFL) {
            throw new IOException("in-place edit exceeds SendPartialObject's 32-bit length: " + size);
        }

        checkMtpResponse(executeWithoutData(device, OP_BEGIN_EDIT_OBJECT, (int) objectHandle),
            "BeginEditObject");
        IOException pending = null;
        try {
            checkMtpResponse(executeWithoutData(device, OP_TRUNCATE_OBJECT, (int) objectHandle, 0, 0),
                "TruncateObject");
            if (size > 0) {
                try (var in = Files.newInputStream(Path.of(localPath))) {
                    sendPartialObject(device, objectHandle, size, in);
                }
            }
        } catch (IOException e) {
            pending = e;
            throw e;
        } finally {
            endEditObject(device, objectHandle, pending);
        }
    }

    private void endEditObject(MemorySegment device, long objectHandle, IOException pending) throws IOException {
        try {
            checkMtpResponse(executeWithoutData(device, OP_END_EDIT_OBJECT, (int) objectHandle),
                "EndEditObject");
        } catch (IOException endFailure) {
            if (pending != null) {
                pending.addSuppressed(endFailure);
            } else {
                throw endFailure;
            }
        }
    }

    private boolean deviceSupportsOperation(MemorySegment device, int opcode) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var command = createCommand(PID_GET_SUPPORTED_VENDOR_OPCODES);
            try {
                var results = sendCommand(device, command, arena);
                try {
                    checkDriverHr(results, "GET_SUPPORTED_VENDOR_OPCODES");
                    var out = arena.allocate(ADDRESS);
                    int hr = call(results, VAL_GET_PVCOLL,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                        KEY_MTP_OPERATION_CODES, out);
                    if (failed(hr)) return false;
                    var operations = out.get(ADDRESS, 0);
                    try {
                        return propVariantCollectionContainsU4(operations, opcode);
                    } finally {
                        release(operations);
                    }
                } finally {
                    release(results);
                }
            } finally {
                release(command);
            }
        }
    }

    private boolean propVariantCollectionContainsU4(MemorySegment collection, int value) {
        try (var arena = Arena.ofConfined()) {
            var countOut = arena.allocate(JAVA_INT);
            int countHr = call(collection, PVCOLL_GET_COUNT,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), countOut);
            if (failed(countHr)) return false;
            int count = countOut.get(JAVA_INT, 0);
            var variant = arena.allocate(PROPVARIANT_SIZE);
            for (int i = 0; i < count; i++) {
                int hr = call(collection, PVCOLL_GET_AT,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), i, variant);
                if (!failed(hr) && variant.get(JAVA_SHORT, 0) == VT_UI4
                        && variant.get(JAVA_INT, 8) == value) {
                    return true;
                }
            }
            return false;
        }
    }

    private int executeWithoutData(MemorySegment device, int opcode, int... params) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var command = createCommand(PID_EXECUTE_WITHOUT_DATA_PHASE);
            try {
                setU4(command, KEY_MTP_OP_CODE, opcode);
                setOpParams(command, arena, params);
                var results = sendCommand(device, command, arena);
                try {
                    checkDriverHr(results, "MTP op 0x" + Integer.toHexString(opcode));
                    return getOptionalU4(results, KEY_MTP_RESPONSE_CODE);
                } finally {
                    release(results);
                }
            } finally {
                release(command);
            }
        }
    }

    int sendRawMtpCommand(DeviceHandle handle, int opcode, int... params) throws IOException {
        return executeWithoutData(asDevice(handle).device(), opcode, params);
    }

    private void sendPartialObject(MemorySegment device, long objectHandle, long size, InputStream in)
            throws IOException {
        String context = beginDataToWrite(device, objectHandle, size);
        int response;
        try {
            writeDataPhase(device, context, in, size);
        } finally {
            response = endDataTransfer(device, context);
        }
        checkMtpResponse(response, "SendPartialObject");
    }

    private String beginDataToWrite(MemorySegment device, long objectHandle, long size) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var command = createCommand(PID_EXECUTE_WITH_DATA_TO_WRITE);
            try {
                setU4(command, KEY_MTP_OP_CODE, OP_SEND_PARTIAL_OBJECT);
                setOpParams(command, arena, (int) objectHandle, 0, 0, (int) size);
                setU8(command, KEY_MTP_TRANSFER_TOTAL_SIZE, size);
                var results = sendCommand(device, command, arena);
                try {
                    checkDriverHr(results, "initiate SendPartialObject");
                    String context = getString(results, KEY_MTP_TRANSFER_CONTEXT);
                    if (context.isBlank()) throw new IOException("SendPartialObject returned no transfer context");
                    return context;
                } finally {
                    release(results);
                }
            } finally {
                release(command);
            }
        }
    }

    private void writeDataPhase(MemorySegment device, String context, InputStream in, long total)
            throws IOException {
        byte[] heap = new byte[(int) Math.min(Math.max(total, 1), MTP_TRANSFER_CHUNK)];
        long remaining = total;
        while (remaining > 0) {
            int want = (int) Math.min(remaining, heap.length);
            int got = readFully(in, heap, want);
            if (got < want) throw new IOException("local file ended before declared transfer length");

            try (var arena = Arena.ofConfined()) {
                var command = createCommand(PID_WRITE_DATA);
                try {
                    setString(command, KEY_MTP_TRANSFER_CONTEXT, wstr(arena, context));
                    var buffer = arena.allocate(got);
                    MemorySegment.copy(heap, 0, buffer, JAVA_BYTE, 0, got);
                    setBuffer(command, KEY_MTP_TRANSFER_DATA, buffer, got);
                    setU4(command, KEY_MTP_NUM_BYTES_TO_WRITE, got);
                    var results = sendCommand(device, command, arena);
                    try {
                        checkDriverHr(results, "WRITE_DATA");
                    } finally {
                        release(results);
                    }
                } finally {
                    release(command);
                }
            }
            remaining -= got;
        }
    }

    private static int readFully(InputStream in, byte[] buffer, int want) throws IOException {
        int offset = 0;
        while (offset < want) {
            int read = in.read(buffer, offset, want - offset);
            if (read < 0) break;
            offset += read;
        }
        return offset;
    }

    private void setOpParams(MemorySegment values, Arena arena, int... params) throws IOException {
        var collection = createInstance(CLSID_PROPVARIANT_COLLECTION, IID_PROPVARIANT_COLLECTION,
            "create MTP operation params");
        try {
            for (int param : params) addU4(collection, arena, param);
            call(values, VAL_SET_PVCOLL,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), KEY_MTP_OP_PARAMS, collection);
        } finally {
            release(collection);
        }
    }

    private void addU4(MemorySegment collection, Arena arena, int value) {
        var variant = arena.allocate(PROPVARIANT_SIZE);
        variant.set(JAVA_SHORT, 0, VT_UI4);
        variant.set(JAVA_INT, 8, value);
        call(collection, PVCOLL_ADD, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), variant);
    }

    private MemorySegment createCommand(int commandPid) throws IOException {
        var values = createInstance(CLSID_VALUES, IID_VALUES, "create MTP command values");
        setGuid(values, KEY_COMMON_COMMAND_CATEGORY, MTP_EXT_CATEGORY);
        setU4(values, KEY_COMMON_COMMAND_ID, commandPid);
        return values;
    }

    private MemorySegment sendCommand(MemorySegment device, MemorySegment command, Arena arena)
            throws IOException {
        var out = arena.allocate(ADDRESS);
        checkHr(call(device, DEV_SEND_COMMAND,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                0, command, out),
            "IPortableDevice::SendCommand");
        return out.get(ADDRESS, 0);
    }

    private void checkDriverHr(MemorySegment results, String operation) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(JAVA_INT);
            int hr = call(results, VAL_GET_ERROR,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), KEY_COMMON_HRESULT, out);
            if (!failed(hr)) checkHr(out.get(JAVA_INT, 0), operation + " (driver HRESULT)");
        }
    }

    private void checkMtpResponse(int responseCode, String operation) throws IOException {
        if (responseCode != MTP_RESPONSE_OK) {
            throw new IOException(operation + " failed (MTP response 0x"
                + Integer.toHexString(responseCode) + ")");
        }
    }

    @Override
    public String sendFile(DeviceHandle handle, String localPath, String filename,
                           String parentId, String storageId, long filesize) throws IOException {
        var d = asDevice(handle);
        try (var arena = Arena.ofConfined()) {
            var created = openObjectWriteStream(d.content(), parentForWpd(parentId, storageId),
                filename, filesize, arena);
            boolean committed = false;
            try {
                try (var in = Files.newInputStream(Path.of(localPath))) {
                    copyFileToStream(in, created.stream(), created.bufferSize());
                }
                checkHr(call(created.stream(), STREAM_COMMIT,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), 0),
                    "IStream::Commit");
                committed = true;
                return readNewObjectId(created.stream());
            } finally {
                String partialId = "";
                if (!committed) {
                    try {
                        partialId = readNewObjectId(created.stream());
                    } catch (RuntimeException ignored) {
                    }
                }
                release(created.stream());
                if (!partialId.isBlank()) {
                    try {
                        deleteObject(handle, partialId);
                    } catch (IOException | RuntimeException ignored) {
                    }
                }
            }
        }
    }

    private record CreatedStream(MemorySegment stream, int bufferSize) {}

    private CreatedStream openObjectWriteStream(MemorySegment content, String parent, String filename,
                                                long filesize, Arena arena) throws IOException {
        var values = createInstance(CLSID_VALUES, IID_VALUES, "create file properties");
        try {
            setString(values, KEY_PARENT_ID, wstr(arena, parent));
            setString(values, KEY_NAME, wstr(arena, filename));
            setString(values, KEY_ORIGINAL_FILE_NAME, wstr(arena, filename));
            setU8(values, KEY_OBJECT_SIZE, filesize);
            var audioFormat = UPLOAD_AUDIO_AS_GENERIC ? null : audioFormatForFilename(filename);
            if (audioFormat == null) {
                setGuid(values, KEY_CONTENT_TYPE, CONTENT_TYPE_GENERIC_FILE);
                setGuid(values, KEY_OBJECT_FORMAT, FORMAT_UNSPECIFIED);
            } else {
                setGuid(values, KEY_CONTENT_TYPE, CONTENT_TYPE_AUDIO);
                setGuid(values, KEY_OBJECT_FORMAT, audioFormat);
            }
            var streamOut = arena.allocate(ADDRESS);
            var optimal = arena.allocate(JAVA_INT);
            checkHr(call(content, CONTENT_CREATE_DATA,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                    values, streamOut, optimal, MemorySegment.NULL),
                "CreateObjectWithPropertiesAndData");
            return new CreatedStream(streamOut.get(ADDRESS, 0),
                transferBufferSize(optimal.get(JAVA_INT, 0)));
        } finally {
            release(values);
        }
    }

    private static MemorySegment audioFormatForFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return switch (filename.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "mp3" -> FORMAT_MP3;
            case "wav" -> FORMAT_WAV;
            case "wma" -> FORMAT_WMA;
            case "ogg" -> FORMAT_OGG;
            case "aac" -> FORMAT_AAC;
            case "flac" -> FORMAT_FLAC;
            case "m4a" -> FORMAT_M4A;
            case "mp2" -> FORMAT_MP2;
            default -> null;
        };
    }

    @Override
    public void moveObject(DeviceHandle handle, String itemId, String storageId, String parentId)
            throws IOException {
        var d = asDevice(handle);
        var ids = objectIdCollection(itemId);
        try (var arena = Arena.ofConfined()) {
            checkHr(call(d.content(), CONTENT_MOVE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                    ids, wstr(arena, parentForWpd(parentId, storageId)), MemorySegment.NULL),
                "IPortableDeviceContent::Move");
        } finally {
            release(ids);
        }
    }

    @Override
    public void setFileName(DeviceHandle handle, String itemId, String newName) throws IOException {
        var d = asDevice(handle);
        try (var arena = Arena.ofConfined()) {
            var values = createInstance(CLSID_VALUES, IID_VALUES, "create rename properties");
            try {
                setString(values, KEY_NAME, wstr(arena, newName));
                setString(values, KEY_ORIGINAL_FILE_NAME, wstr(arena, newName));
                var resultsOut = arena.allocate(ADDRESS);
                int hr = call(d.properties(), PROPS_SET_VALUES,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                    wstr(arena, itemId), values, resultsOut);
                if (!failed(hr)) release(resultsOut.get(ADDRESS, 0));
                checkHr(hr, "IPortableDeviceProperties::SetValues");
            } finally {
                release(values);
            }
        }
    }

    private List<String> enumChildren(MemorySegment content, String parentObjectId) throws IOException {
        var ids = new ArrayList<String>();
        try (var arena = Arena.ofConfined()) {
            var enumOut = arena.allocate(ADDRESS);
            checkHr(call(content, CONTENT_ENUM,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    0, wstr(arena, parentObjectId), MemorySegment.NULL, enumOut),
                "IPortableDeviceContent::EnumObjects");
            var enumerator = enumOut.get(ADDRESS, 0);
            try {
                final int batch = 32;
                var fetched = arena.allocate(JAVA_INT);
                var idArray = arena.allocate(ADDRESS.byteSize() * batch);
                while (true) {
                    fetched.set(JAVA_INT, 0, 0);
                    checkHr(call(enumerator, ENUM_NEXT,
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                            batch, idArray, fetched),
                        "IEnumPortableDeviceObjectIDs::Next");
                    int n = fetched.get(JAVA_INT, 0);
                    for (int i = 0; i < n; i++) {
                        var ptr = idArray.getAtIndex(ADDRESS, i);
                        ids.add(readWstr(ptr));
                        coTaskMemFree(ptr);
                    }
                    if (n < batch) break;
                }
            } finally {
                release(enumerator);
            }
        }
        return ids;
    }

    private MemorySegment getValues(MemorySegment properties, String objectId, MemorySegment... keys) {
        var keyCollection = createInstanceQuiet(CLSID_KEY_COLLECTION, IID_KEY_COLLECTION);
        if (MemorySegment.NULL.equals(keyCollection)) return MemorySegment.NULL;
        try (var arena = Arena.ofConfined()) {
            for (var key : keys) {
                call(keyCollection, KEYCOLL_ADD, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), key);
            }
            var out = arena.allocate(ADDRESS);
            int hr = call(properties, PROPS_GET_VALUES,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                wstr(arena, objectId), keyCollection, out);
            return failed(hr) ? MemorySegment.NULL : out.get(ADDRESS, 0);
        } finally {
            release(keyCollection);
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
            try {
                return readWstr(ptr);
            } finally {
                coTaskMemFree(ptr);
            }
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
        int v = getOptionalU4(values, key);
        return v < 0 ? -1 : Integer.toUnsignedLong(v);
    }

    private int getOptionalU4(MemorySegment values, MemorySegment key) {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(JAVA_INT);
            int hr = call(values, VAL_GET_U4,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, out);
            return failed(hr) ? -1 : out.get(JAVA_INT, 0);
        }
    }

    private long readUnsigned(MemorySegment values, MemorySegment key) {
        long u8 = getU8(values, key);
        return u8 >= 0 ? u8 : getU4(values, key);
    }

    private long getDateEpochSeconds(MemorySegment values, MemorySegment key) {
        try (var arena = Arena.ofConfined()) {
            var variant = arena.allocate(PROPVARIANT_SIZE);
            int hr = call(values, VAL_GET_VALUE,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, variant);
            if (failed(hr)) return 0;
            try {
                if (variant.get(JAVA_SHORT, 0) != VT_DATE) return 0;
                double oaDate = variant.get(JAVA_DOUBLE, 8);
                return Math.round((oaDate - OA_EPOCH_DAYS) * SECONDS_PER_DAY);
            } finally {
                propVariantClear(variant);
            }
        }
    }

    private boolean getGuid(MemorySegment values, MemorySegment key, MemorySegment out) {
        return !failed(call(values, VAL_GET_GUID,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, out));
    }

    private void setString(MemorySegment values, MemorySegment key, MemorySegment value) {
        call(values, VAL_SET_STRING, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, value);
    }

    private void setU4(MemorySegment values, MemorySegment key, int value) {
        call(values, VAL_SET_U4, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), key, value);
    }

    private void setU8(MemorySegment values, MemorySegment key, long value) {
        call(values, VAL_SET_U8, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG), key, value);
    }

    private void setGuid(MemorySegment values, MemorySegment key, MemorySegment value) {
        call(values, VAL_SET_GUID, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), key, value);
    }

    private void setBuffer(MemorySegment values, MemorySegment key, MemorySegment buffer, int size) {
        call(values, VAL_SET_BUFFER,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT), key, buffer, size);
    }

    private MemorySegment objectIdCollection(String objectId) throws IOException {
        var collection = createInstance(CLSID_PROPVARIANT_COLLECTION, IID_PROPVARIANT_COLLECTION,
            "create object-id collection");
        try (var arena = Arena.ofConfined()) {
            var variant = arena.allocate(PROPVARIANT_SIZE);
            variant.set(JAVA_SHORT, 0, VT_LPWSTR);
            variant.set(ADDRESS, 8, wstr(arena, objectId));
            checkHr(call(collection, PVCOLL_ADD,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), variant),
                "IPortableDevicePropVariantCollection::Add");
            return collection;
        } catch (Throwable t) {
            release(collection);
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
            try {
                return readWstr(ptr);
            } finally {
                coTaskMemFree(ptr);
            }
        } finally {
            release(dataStream);
        }
    }

    private void copyStreamToFile(MemorySegment stream, OutputStream out, int bufferSize)
            throws IOException {
        try (var arena = Arena.ofConfined()) {
            var buffer = arena.allocate(bufferSize);
            var readOut = arena.allocate(JAVA_INT);
            var descriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
            while (true) {
                readOut.set(JAVA_INT, 0, 0);
                checkHr(call(stream, STREAM_READ, descriptor, buffer, bufferSize, readOut),
                    "IStream::Read");
                int read = readOut.get(JAVA_INT, 0);
                if (read <= 0) break;
                out.write(buffer.asSlice(0, read).toArray(JAVA_BYTE));
            }
        }
    }

    private byte[] readStreamRange(MemorySegment stream, long offset, int maxBytes, int bufferSize)
            throws IOException {
        byte[] out = new byte[maxBytes];
        int read = 0;
        long remainingSkip = offset;
        try (var arena = Arena.ofConfined()) {
            var buffer = arena.allocate(bufferSize);
            var readOut = arena.allocate(JAVA_INT);
            var descriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
            while (remainingSkip > 0 || read < maxBytes) {
                int want = remainingSkip > 0
                    ? (int) Math.min(remainingSkip, buffer.byteSize())
                    : Math.min(maxBytes - read, (int) buffer.byteSize());
                readOut.set(JAVA_INT, 0, 0);
                checkHr(call(stream, STREAM_READ, descriptor, buffer, want, readOut),
                    "IStream::Read");
                int got = readOut.get(JAVA_INT, 0);
                if (got <= 0) break;
                if (remainingSkip > 0) {
                    remainingSkip -= got;
                    continue;
                }
                int keep = Math.min(got, maxBytes - read);
                MemorySegment.copy(buffer, JAVA_BYTE, 0, out, read, keep);
                read += keep;
            }
        }
        return read == out.length ? out : Arrays.copyOf(out, read);
    }

    private void copyFileToStream(InputStream in, MemorySegment stream, int bufferSize)
            throws IOException {
        try (var arena = Arena.ofConfined()) {
            var nativeBuffer = arena.allocate(bufferSize);
            var writtenOut = arena.allocate(JAVA_INT);
            var descriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
            byte[] heap = new byte[bufferSize];
            int read;
            while ((read = in.read(heap)) > 0) {
                MemorySegment.copy(heap, 0, nativeBuffer, JAVA_BYTE, 0, read);
                int sent = 0;
                while (sent < read) {
                    writtenOut.set(JAVA_INT, 0, 0);
                    checkHr(call(stream, STREAM_WRITE, descriptor,
                            nativeBuffer.asSlice(sent), read - sent, writtenOut),
                        "IStream::Write");
                    int written = writtenOut.get(JAVA_INT, 0);
                    if (written <= 0) {
                        throw new IOException("IStream::Write accepted no bytes with "
                            + (read - sent) + " bytes remaining");
                    }
                    sent += written;
                }
            }
        }
    }

    private int copyBufferValue(MemorySegment results, byte[] out, int offset) {
        try (var arena = Arena.ofConfined()) {
            var ptrOut = arena.allocate(ADDRESS);
            var sizeOut = arena.allocate(JAVA_INT);
            int hr = call(results, VAL_GET_BUFFER,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                KEY_MTP_TRANSFER_DATA, ptrOut, sizeOut);
            if (failed(hr)) return 0;
            int size = sizeOut.get(JAVA_INT, 0);
            var ptr = ptrOut.get(ADDRESS, 0);
            if (size <= 0 || MemorySegment.NULL.equals(ptr)) return 0;
            int n = Math.min(size, out.length - offset);
            MemorySegment.copy(ptr.reinterpret(n), JAVA_BYTE, 0, out, offset, n);
            coTaskMemFree(ptr);
            return n;
        }
    }

    private static int transferBufferSize(int driverOptimal) {
        return driverOptimal > 0 ? driverOptimal : STREAM_FALLBACK_BUFFER;
    }

    private static String parentForWpd(String parentId, String storageId) {
        return parentId.equals(ROOT_PARENT) ? storageId : parentId;
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static long parseObjectHandle(String objectId) throws IOException {
        String hex = (!objectId.isEmpty() && (objectId.charAt(0) == 'o' || objectId.charAt(0) == 'O'))
            ? objectId.substring(1)
            : objectId;
        try {
            return Long.parseUnsignedLong(hex, 16) & 0xFFFFFFFFL;
        } catch (NumberFormatException e) {
            throw new IOException("cannot derive an MTP object handle from WPD id: " + objectId, e);
        }
    }
}
