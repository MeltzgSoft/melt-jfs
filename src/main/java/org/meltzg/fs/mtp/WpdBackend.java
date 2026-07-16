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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.*;
import static org.meltzg.fs.mtp.MtpBackend.emptyToNull;
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
    private static final MemorySegment KEY_DEVICE_PROTOCOL;
    private static final MemorySegment KEY_DEVICE_SERIAL_NUMBER;

    // ---- SendCommand / MTP pass-through keys (readPartial) ----
    // The MTP-extension command category; SendCommand routes to it and picks the command by pid.
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
    private static final MemorySegment KEY_MTP_TRANSFER_DATA;

    // ---- content-type / category GUID values ----
    private static final MemorySegment CONTENT_TYPE_FOLDER;
    private static final MemorySegment CONTENT_TYPE_FUNCTIONAL_OBJECT;
    private static final MemorySegment CONTENT_TYPE_GENERIC_FILE;
    private static final MemorySegment CONTENT_TYPE_AUDIO;
    private static final MemorySegment FORMAT_UNSPECIFIED;
    private static final MemorySegment FORMAT_PROPERTIES_ONLY;
    // Audio object formats, keyed by upload extension in audioFormatForFilename.
    private static final MemorySegment FORMAT_MP3;
    private static final MemorySegment FORMAT_WAV;
    private static final MemorySegment FORMAT_WMA;
    private static final MemorySegment FORMAT_OGG;
    private static final MemorySegment FORMAT_AAC;
    private static final MemorySegment FORMAT_FLAC;
    private static final MemorySegment FUNCTIONAL_CATEGORY_STORAGE;

    // The well-known root from which a device's functional objects (storages) are enumerated.
    private static final String WPD_DEVICE_OBJECT_ID = "DEVICE";

    // ---- vtable indices (after IUnknown's QueryInterface=0, AddRef=1, Release=2) ----
    private static final int MGR_GET_DEVICES = 3, MGR_FRIENDLY_NAME = 5, MGR_DESCRIPTION = 6, MGR_MANUFACTURER = 7;
    private static final int DEV_OPEN = 3, DEV_SEND_COMMAND = 4, DEV_CONTENT = 5, DEV_CLOSE = 8;
    // IPortableDeviceContent vtable order (after IUnknown): EnumObjects(3), Properties(4), Transfer(5),
    // CreateObjectWithPropertiesOnly(6), CreateObjectWithPropertiesAndData(7), Delete(8),
    // GetObjectIDsFromPersistentUniqueIDs(9), Cancel(10), Move(11), Copy(12).
    private static final int CONTENT_ENUM = 3, CONTENT_PROPERTIES = 4, CONTENT_TRANSFER = 5,
        CONTENT_CREATE_PROPS = 6, CONTENT_CREATE_DATA = 7, CONTENT_DELETE = 8, CONTENT_MOVE = 11;
    private static final int ENUM_NEXT = 3;
    private static final int PROPS_GET_VALUES = 5, PROPS_SET_VALUES = 6;
    private static final int RES_GET_STREAM = 5;
    private static final int VAL_GET_VALUE = 6, VAL_SET_STRING = 7, VAL_GET_STRING = 8, VAL_SET_U4 = 9,
        VAL_GET_U4 = 10, VAL_SET_U8 = 13, VAL_GET_U8 = 14, VAL_GET_ERROR = 20, VAL_SET_GUID = 27,
        VAL_GET_GUID = 28, VAL_SET_BUFFER = 29, VAL_GET_BUFFER = 30, VAL_SET_PVCOLL = 33;
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
        // WPD_DEVICE_PROTOCOL (WPD_DEVICE_PROPERTIES_V1) — a string such as "MTP: 1.00", "PTP:",
        // "MSC:", read by isMtpDevice to filter out the non-MTP devices WPD also enumerates.
        KEY_DEVICE_PROTOCOL = propertyKey(a, "26d4979a-e643-4626-9e2b-736dc0c92fdc", 6);
        // WPD_DEVICE_SERIAL_NUMBER (same fmtid, pid 9) — the device's MTP serial, used as the identity's
        // serial so it matches the libmtp backend instead of a Windows-local PnP instance id.
        KEY_DEVICE_SERIAL_NUMBER = propertyKey(a, "26d4979a-e643-4626-9e2b-736dc0c92fdc", 9);

        // WPD_CATEGORY_COMMON keys used to address any SendCommand invocation (PortableDevice.h).
        String commonFmt = "f0422a9c-5dc8-4440-b5bd-5df28835658a";
        KEY_COMMON_COMMAND_CATEGORY = propertyKey(a, commonFmt, 1001);
        KEY_COMMON_COMMAND_ID = propertyKey(a, commonFmt, 1002);
        KEY_COMMON_HRESULT = propertyKey(a, commonFmt, 1003);
        // WPD_CATEGORY_MTP_EXT_VENDOR_OPERATIONS command/property keys (WpdMtpExtensions.h). The
        // category doubles as every MTP-ext command's fmtid; the command is selected by its pid.
        String mtpExt = "4d545058-1a2e-4106-a357-771e0819fc56";
        MTP_EXT_CATEGORY = guid(a, mtpExt);
        KEY_MTP_OP_CODE = propertyKey(a, mtpExt, 1001);
        KEY_MTP_OP_PARAMS = propertyKey(a, mtpExt, 1002);
        KEY_MTP_RESPONSE_CODE = propertyKey(a, mtpExt, 1003);
        KEY_MTP_TRANSFER_CONTEXT = propertyKey(a, mtpExt, 1006);
        KEY_MTP_TRANSFER_TOTAL_SIZE = propertyKey(a, mtpExt, 1007);
        KEY_MTP_NUM_BYTES_TO_READ = propertyKey(a, mtpExt, 1008);
        KEY_MTP_TRANSFER_DATA = propertyKey(a, mtpExt, 1012);

        CONTENT_TYPE_FOLDER = guid(a, "27e2e392-a111-48e0-ab0c-e17705a05f85");
        CONTENT_TYPE_FUNCTIONAL_OBJECT = guid(a, "99ed0160-17ff-4c44-9d98-1d7a6f941921");
        CONTENT_TYPE_GENERIC_FILE = guid(a, "0085e0a6-8d34-45d7-bc5c-447e59c73d48");
        CONTENT_TYPE_AUDIO = guid(a, "4ad2c85e-5e2d-45e5-8864-4f229e3c6cf0");
        FORMAT_UNSPECIFIED = guid(a, "30000000-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_PROPERTIES_ONLY = guid(a, "30010000-ae6c-4804-98ba-c57b46965fe7");
        // WPD object-format GUIDs are {0000<mtp-format-code>-ae6c-4804-98ba-c57b46965fe7}
        // (PortableDevice.h). Codes: WAV 0x3008, MP3 0x3009, WMA 0xB901, OGG 0xB902,
        // AAC 0xB903, FLAC 0xB906.
        FORMAT_MP3  = guid(a, "00003009-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_WAV  = guid(a, "00003008-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_WMA  = guid(a, "0000b901-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_OGG  = guid(a, "0000b902-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_AAC  = guid(a, "0000b903-ae6c-4804-98ba-c57b46965fe7");
        FORMAT_FLAC = guid(a, "0000b906-ae6c-4804-98ba-c57b46965fe7");
        FUNCTIONAL_CATEGORY_STORAGE = guid(a, "23f05bbc-15de-4c2a-a55b-a9af5ce412ef");
    }

    // Standard MTP GetPartialObject (0x101B, 32-bit offset) and the Android GetPartialObject64
    // extension (0x95C1, 64-bit offset). A device advertises one or the other; readPartial probes
    // 0x101B first, then 0x95C1, and caches whichever the device honoured. MTP response 0x2001 is OK,
    // 0x2005 is Operation_Not_Supported.
    private static final int OP_GET_PARTIAL_OBJECT = 0x101B, OP_GET_PARTIAL_OBJECT_64 = 0x95C1;
    private static final int MTP_RESPONSE_OK = 0x2001, MTP_RESPONSE_OP_NOT_SUPPORTED = 0x2005;
    // Chunk size for the READ_DATA phase; audio-tag reads are well under this, so it is one round trip.
    private static final int READ_DATA_CHUNK = 256 * 1024;

    private static final WpdBackend INSTANCE = new WpdBackend();

    static WpdBackend getInstance() {
        return INSTANCE;
    }

    private WpdBackend() {}

    // The GetPartialObject opcode this device honoured, cached after the first successful probe
    // (0 until then). Written at most once per opcode; a stale read only costs one extra probe.
    private volatile int partialReadOpcode = 0;

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

            // WPD enumerates every portable device (PTP cameras, mass-storage, phones in other modes),
            // not just MTP ones. Skip anything that isn't MTP so callers only ever see usable devices.
            if (!isMtpDevice(props)) {
                releaseDevice(new WpdDevice(device, content, props));
                return null;
            }

            var friendly = deviceStringProp(manager, deviceId, MGR_FRIENDLY_NAME, arena);
            var description = deviceStringProp(manager, deviceId, MGR_DESCRIPTION, arena);
            var manufacturer = deviceStringProp(manager, deviceId, MGR_MANUFACTURER, arena);

            var id = parseIdentifier(deviceId, deviceSerialNumber(props));
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

    /**
     * Whether the opened device speaks MTP, read from WPD_DEVICE_PROTOCOL on the device object. WPD
     * reports a transport-protocol string ("MTP: 1.00", "PTP:", "MSC:", ...); only a value that
     * clearly names a non-MTP protocol excludes the device. An absent or unreadable protocol keeps
     * the device (fail-open), so a quirky-but-usable MTP device is never dropped.
     */
    private boolean isMtpDevice(MemorySegment props) {
        var values = getValues(props, WPD_DEVICE_OBJECT_ID, KEY_DEVICE_PROTOCOL);
        if (MemorySegment.NULL.equals(values)) return true;
        try {
            String protocol = getString(values, KEY_DEVICE_PROTOCOL);
            return protocol.isEmpty() || protocol.regionMatches(true, 0, "MTP", 0, 3);
        } finally {
            release(values);
        }
    }

    private MTPDeviceIdentifier parseIdentifier(String deviceId, String wpdSerial) {
        String lower = deviceId.toLowerCase();
        int vendor = 0, product = 0;
        Matcher mv = VID.matcher(lower);
        if (mv.find()) vendor = Integer.parseInt(mv.group(1), 16);
        Matcher mp = PID.matcher(lower);
        if (mp.find()) product = Integer.parseInt(mp.group(1), 16);
        // Prefer the device-reported MTP serial (WPD_DEVICE_SERIAL_NUMBER) so the identity matches the
        // libmtp backend. Fall back to the Windows PnP instance id (3rd '#'-delimited segment) for a
        // device that reports no serial — that id is bus-generated (e.g. "b&27c98feb&0&0000").
        var segments = deviceId.split("#");
        String raw = !wpdSerial.isEmpty() ? wpdSerial
            : (segments.length >= 3 ? segments[2] : deviceId);
        // MTPDeviceIdentifier's serial must be word characters (it flows into a mtp:// URI and matches
        // \w+), so collapse any run of non-word characters to a single underscore.
        String serial = raw.replaceAll("[^0-9A-Za-z_]+", "_").replaceAll("^_+|_+$", "");
        if (serial.isEmpty()) serial = "unknown";
        return new MTPDeviceIdentifier(vendor, product, serial);
    }

    /** Reads WPD_DEVICE_SERIAL_NUMBER from the device object, or "" when the device reports none. */
    private String deviceSerialNumber(MemorySegment props) {
        var values = getValues(props, WPD_DEVICE_OBJECT_ID, KEY_DEVICE_SERIAL_NUMBER);
        if (MemorySegment.NULL.equals(values)) return "";
        try {
            return getString(values, KEY_DEVICE_SERIAL_NUMBER);
        } finally {
            release(values);
        }
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
            KEY_MUSIC_TRACK, KEY_MEDIA_DURATION, KEY_NAME);
        if (MemorySegment.NULL.equals(values)) return null;
        try {
            String title = emptyToNull(getString(values, KEY_MEDIA_TITLE));
            String artist = emptyToNull(getString(values, KEY_MEDIA_ARTIST));
            String album = emptyToNull(getString(values, KEY_MUSIC_ALBUM));
            String genre = emptyToNull(getString(values, KEY_MEDIA_GENRE));
            int trackNumber = (int) Math.max(getU4(values, KEY_MUSIC_TRACK), 0);
            long duration = Math.max(getU8(values, KEY_MEDIA_DURATION), 0);

            // Devices commonly index a track's ID3 title (TIT2) onto WPD_OBJECT_NAME and leave
            // WPD_MEDIA_TITLE empty. Fall back to the object name for the title — but only once
            // another media property confirms the device recognised this object as a track, so a
            // plain file's name can't masquerade as a title (WPD has no "is a track" gate).
            boolean recognisedTrack = artist != null || album != null || genre != null
                || trackNumber > 0 || duration > 0;
            if (title == null && recognisedTrack) {
                title = emptyToNull(getString(values, KEY_NAME));
            }

            var meta = new MTPTrackMetadata(title, artist, album, genre, trackNumber, duration);
            return meta.isEmpty() ? null : meta;
        } finally {
            release(values);
        }
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
    public boolean supportsPartialReads() {
        // Implemented through MTP GetPartialObject, sent as a raw command via IPortableDevice::SendCommand.
        // Device-level support (which GetPartialObject variant, if any) is probed on first use.
        return true;
    }

    /**
     * Ranged read via the MTP GetPartialObject operation, issued as a raw MTP command through
     * {@code IPortableDevice::SendCommand} (the WPD MTP pass-through). Unlike the whole-object resource
     * stream used by {@link #getFile}, this is a bounded request/data/response transaction, so it
     * transfers only the requested bytes and never leaves the device mid-transfer.
     */
    @Override
    public byte[] readPartial(DeviceHandle handle, String itemId, long offset, int maxBytes) throws IOException {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative: " + offset);
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must be non-negative: " + maxBytes);
        if (maxBytes == 0) return new byte[0];
        long objectHandle = parseObjectHandle(itemId);
        var d = dev(handle);

        // Try the cached opcode first, then the other; a 32-bit-offset op is skipped when the offset
        // needs more than 32 bits. getPartialObject returns null when the device reports the opcode
        // unsupported (MTP 0x2005), so we fall through to the next candidate.
        int cached = partialReadOpcode;
        int[] order = cached == OP_GET_PARTIAL_OBJECT_64
            ? new int[]{OP_GET_PARTIAL_OBJECT_64, OP_GET_PARTIAL_OBJECT}
            : new int[]{OP_GET_PARTIAL_OBJECT, OP_GET_PARTIAL_OBJECT_64};
        // An unsupported opcode can surface either as MTP 0x2005 (getPartialObject returns null) or as a
        // driver-level error; in both cases fall through to the other variant before giving up.
        IOException pending = null;
        for (int opcode : order) {
            if (opcode == OP_GET_PARTIAL_OBJECT && (offset >>> 32) != 0) continue; // needs 64-bit offset
            try {
                byte[] result = getPartialObject(d, objectHandle, offset, maxBytes, opcode);
                if (result != null) {
                    partialReadOpcode = opcode;
                    return result;
                }
            } catch (IOException e) {
                pending = e;
            }
        }
        if (pending != null) throw pending;
        throw new IOException("device supports neither GetPartialObject (0x101B) nor "
            + "GetPartialObject64 (0x95C1) for id: " + itemId);
    }

    /**
     * Runs one GetPartialObject transaction with {@code opcode}: initiate (WITH_DATA_TO_READ), read the
     * data phase in chunks (READ_DATA), then always close it (END_DATA_TRANSFER). Returns the bytes read
     * (possibly empty near/at end-of-object), or {@code null} when the device reports the opcode
     * unsupported so the caller can try the other variant.
     */
    private byte[] getPartialObject(WpdDevice d, long objectHandle, long offset, int maxBytes, int opcode)
            throws IOException {
        String context;
        long total;
        try (var arena = Arena.ofConfined()) {
            var initParams = createCommand(MTP_EXT_CATEGORY, PID_EXECUTE_WITH_DATA_TO_READ);
            try {
                setU4(initParams, KEY_MTP_OP_CODE, opcode);
                var params = createInstance(CLSID_PROPVARIANT_COLLECTION, IID_PROPVARIANT_COLLECTION,
                    "create MTP operation params");
                try {
                    addU4(params, arena, (int) objectHandle);
                    if (opcode == OP_GET_PARTIAL_OBJECT) {
                        addU4(params, arena, (int) offset);
                        addU4(params, arena, maxBytes);
                    } else { // GetPartialObject64: object handle, offset low, offset high, max bytes
                        addU4(params, arena, (int) offset);
                        addU4(params, arena, (int) (offset >>> 32));
                        addU4(params, arena, maxBytes);
                    }
                    call(initParams, VAL_SET_PVCOLL,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), KEY_MTP_OP_PARAMS, params);
                } finally {
                    release(params);
                }
                var initResults = sendCommand(d.device(), initParams, arena);
                try {
                    checkDriverHr(initResults, "initiate GetPartialObject");
                    context = getString(initResults, KEY_MTP_TRANSFER_CONTEXT);
                    total = Math.min(Math.max(getU4(initResults, KEY_MTP_TRANSFER_TOTAL_SIZE), 0), maxBytes);
                } finally {
                    release(initResults);
                }
            } finally {
                release(initParams);
            }
        }

        // Once initiated, the transfer must be closed even if reading fails, so the device is not left
        // mid-transaction. END_DATA_TRANSFER carries the MTP response code.
        byte[] out = new byte[(int) total];
        int read = 0;
        int responseCode;
        try {
            read = readDataPhase(d.device(), context, out);
        } finally {
            responseCode = endDataTransfer(d.device(), context);
        }
        if (responseCode == MTP_RESPONSE_OP_NOT_SUPPORTED) return null;
        return read == out.length ? out : Arrays.copyOf(out, read);
    }

    /** Reads the data phase into {@code out} in chunks, returning the number of bytes read. */
    private int readDataPhase(MemorySegment device, String context, byte[] out) throws IOException {
        int read = 0;
        while (read < out.length) {
            int chunk = Math.min(out.length - read, READ_DATA_CHUNK);
            try (var arena = Arena.ofConfined()) {
                var params = createCommand(MTP_EXT_CATEGORY, PID_READ_DATA);
                try {
                    setString(params, KEY_MTP_TRANSFER_CONTEXT, wstr(arena, context));
                    // WPD requires an input buffer of the requested size even though the data comes back
                    // through the results (a WDF quirk noted in the WPD docs).
                    call(params, VAL_SET_BUFFER,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
                        KEY_MTP_TRANSFER_DATA, arena.allocate(chunk), chunk);
                    setU4(params, KEY_MTP_NUM_BYTES_TO_READ, chunk);
                    var results = sendCommand(device, params, arena);
                    try {
                        checkDriverHr(results, "READ_DATA");
                        int got = copyBufferValue(results, out, read);
                        if (got <= 0) break; // device sent less than it promised; stop at what we have
                        read += got;
                    } finally {
                        release(results);
                    }
                } finally {
                    release(params);
                }
            }
        }
        return read;
    }

    /** Sends END_DATA_TRANSFER for {@code context}; returns the MTP response code (-1 if unavailable). */
    private int endDataTransfer(MemorySegment device, String context) {
        try (var arena = Arena.ofConfined()) {
            var params = createCommand(MTP_EXT_CATEGORY, PID_END_DATA_TRANSFER);
            try {
                setString(params, KEY_MTP_TRANSFER_CONTEXT, wstr(arena, context));
                var results = sendCommand(device, params, arena);
                try {
                    return (int) getU4(results, KEY_MTP_RESPONSE_CODE);
                } finally {
                    release(results);
                }
            } finally {
                release(params);
            }
        } catch (IOException | RuntimeException e) {
            return -1; // best-effort close; the read result is already in hand
        }
    }

    // MTP-ext command pids within MTP_EXT_CATEGORY (WpdMtpExtensions.h).
    private static final int PID_EXECUTE_WITH_DATA_TO_READ = 13, PID_READ_DATA = 15, PID_END_DATA_TRANSFER = 17;

    /** Builds an {@code IPortableDeviceValues} addressed to one MTP-ext command (category + pid). */
    private MemorySegment createCommand(MemorySegment category, int commandPid) throws IOException {
        var values = createInstance(CLSID_VALUES, IID_VALUES, "create command parameters");
        setGuid(values, KEY_COMMON_COMMAND_CATEGORY, category);
        setU4(values, KEY_COMMON_COMMAND_ID, commandPid);
        return values;
    }

    /** IPortableDevice::SendCommand; returns the results IPortableDeviceValues (caller releases). */
    private MemorySegment sendCommand(MemorySegment device, MemorySegment params, Arena arena) throws IOException {
        var out = arena.allocate(ADDRESS);
        checkHr(call(device, DEV_SEND_COMMAND,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), 0, params, out),
            "IPortableDevice::SendCommand");
        return out.get(ADDRESS, 0);
    }

    /** Throws when the driver failed to relay the command (WPD_PROPERTY_COMMON_HRESULT). */
    private void checkDriverHr(MemorySegment results, String op) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(JAVA_INT);
            int hr = call(results, VAL_GET_ERROR,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), KEY_COMMON_HRESULT, out);
            if (!failed(hr)) checkHr(out.get(JAVA_INT, 0), op + " (driver HRESULT)");
        }
    }

    private void setU4(MemorySegment values, MemorySegment key, int value) {
        call(values, VAL_SET_U4, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), key, value);
    }

    /** Appends a VT_UI4 value to an IPortableDevicePropVariantCollection. */
    private void addU4(MemorySegment coll, Arena arena, int value) {
        var pv = arena.allocate(PROPVARIANT_SIZE); // zero-filled
        pv.set(JAVA_SHORT, 0, VT_UI4);
        pv.set(JAVA_INT, 8, value);
        call(coll, PVCOLL_ADD, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), pv);
    }

    /**
     * Copies the byte buffer returned in {@code results} under WPD_PROPERTY_MTP_EXT_TRANSFER_DATA into
     * {@code out} at {@code dstOffset}, frees the device-allocated buffer, and returns the count copied.
     */
    private int copyBufferValue(MemorySegment results, byte[] out, int dstOffset) {
        try (var arena = Arena.ofConfined()) {
            var ptrOut = arena.allocate(ADDRESS);
            var cbOut = arena.allocate(JAVA_INT);
            int hr = call(results, VAL_GET_BUFFER,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                KEY_MTP_TRANSFER_DATA, ptrOut, cbOut);
            if (failed(hr)) return 0;
            int cb = cbOut.get(JAVA_INT, 0);
            var ptr = ptrOut.get(ADDRESS, 0);
            if (cb <= 0 || MemorySegment.NULL.equals(ptr)) return 0;
            int n = Math.min(cb, out.length - dstOffset);
            MemorySegment.copy(ptr.reinterpret(n), JAVA_BYTE, 0, out, dstOffset, n);
            coTaskMemFree(ptr);
            return n;
        }
    }

    /**
     * Derives the numeric MTP object handle from a WPD object-id string. The Microsoft WpdMtp driver
     * renders object ids as {@code "o"} followed by the hex object handle; the leading letter is
     * dropped and the rest parsed as hex.
     */
    private static long parseObjectHandle(String itemId) throws IOException {
        String hex = (!itemId.isEmpty() && (itemId.charAt(0) == 'o' || itemId.charAt(0) == 'O'))
            ? itemId.substring(1) : itemId;
        try {
            return Long.parseUnsignedLong(hex, 16) & 0xFFFFFFFFL;
        } catch (NumberFormatException e) {
            throw new IOException("cannot derive an MTP object handle from WPD id: " + itemId);
        }
    }

    /**
     * Returns the WPD audio object-format GUID for a filename's extension, or {@code null} when it
     * is not a recognised audio extension. Extensions without a standard WPD object format (e.g.
     * .m4a, .mp2) return null and are stored as generic files, unlike the libmtp backend.
     */
    private static MemorySegment audioFormatForFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return switch (filename.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "mp3"  -> FORMAT_MP3;
            case "wav"  -> FORMAT_WAV;
            case "wma"  -> FORMAT_WMA;
            case "ogg"  -> FORMAT_OGG;
            case "aac"  -> FORMAT_AAC;
            case "flac" -> FORMAT_FLAC;
            default     -> null;
        };
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
                // Storing a track as audio with its codec's object format lets the device index it
                // and expose tags via getTrackMetadata (parity with the libmtp backend's sendFile).
                // Anything else is stored as a generic, format-unspecified byte stream.
                var audioFormat = audioFormatForFilename(filename);
                if (audioFormat != null) {
                    setGuid(values, KEY_CONTENT_TYPE, CONTENT_TYPE_AUDIO);
                    setGuid(values, KEY_OBJECT_FORMAT, audioFormat);
                } else {
                    setGuid(values, KEY_CONTENT_TYPE, CONTENT_TYPE_GENERIC_FILE);
                    setGuid(values, KEY_OBJECT_FORMAT, FORMAT_UNSPECIFIED);
                }

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
