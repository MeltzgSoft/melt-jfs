package org.meltzg.fs.mtp;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Minimal COM/Win32 plumbing for {@link WpdBackend}, built on the Java FFM API. Provides:
 * <ul>
 *   <li>{@code ole32} entry points (CoInitializeEx, CoCreateInstance, CoTaskMemFree, PropVariantClear);</li>
 *   <li>GUID / PROPERTYKEY / PROPVARIANT allocation and reads;</li>
 *   <li>virtual-method-table dispatch for calling COM interface methods;</li>
 *   <li>UTF-16 (LPWSTR) conversion.</li>
 * </ul>
 *
 * <p>This class loads {@code ole32} and is only usable on Windows. It is referenced lazily, so it is
 * never class-loaded on other platforms.
 */
final class WpdCom {

    static final Linker LINKER = Linker.nativeLinker();
    static final Arena GLOBAL = Arena.global();

    // COM constants
    static final int CLSCTX_INPROC_SERVER = 0x1;
    static final int COINIT_MULTITHREADED = 0x0;
    static final int S_OK = 0;
    static final int S_FALSE = 1;
    static final int RPC_E_CHANGED_MODE = 0x80010106;
    static final int STGM_READ = 0x0;

    // PROPVARIANT variant types we use
    static final short VT_DATE = 7;
    static final short VT_UI4 = 19;
    static final short VT_UI8 = 21;
    static final short VT_LPWSTR = 31;

    // Layout sizes
    static final long GUID_SIZE = 16;
    static final long PROPERTYKEY_SIZE = 20;   // GUID(16) + DWORD pid(4)
    static final long PROPVARIANT_SIZE = 24;   // vt(2) + reserved(6) + union(16: BLOB is ULONG+ptr on 64-bit)

    private static final MethodHandle CO_INITIALIZE_EX;
    private static final MethodHandle CO_CREATE_INSTANCE;
    private static final MethodHandle CO_TASK_MEM_FREE;
    private static final MethodHandle PROP_VARIANT_CLEAR;

    // CoInitializeEx must run once per thread before any COM call on that thread.
    private static final ThreadLocal<Boolean> INITIALIZED = ThreadLocal.withInitial(() -> false);

    static {
        var ole32 = SymbolLookup.libraryLookup("ole32", GLOBAL);
        CO_INITIALIZE_EX = LINKER.downcallHandle(ole32.find("CoInitializeEx").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        CO_CREATE_INSTANCE = LINKER.downcallHandle(ole32.find("CoCreateInstance").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        CO_TASK_MEM_FREE = LINKER.downcallHandle(ole32.find("CoTaskMemFree").orElseThrow(),
            FunctionDescriptor.ofVoid(ADDRESS));
        PROP_VARIANT_CLEAR = LINKER.downcallHandle(ole32.find("PropVariantClear").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }

    private WpdCom() {}

    static boolean failed(int hr) {
        return hr < 0;
    }

    static void checkHr(int hr, String op) throws IOException {
        if (failed(hr)) {
            throw new WpdException(op, hr);
        }
    }

    /** Initializes COM on the current thread (multithreaded apartment). Idempotent per thread. */
    static void ensureInitialized() {
        if (INITIALIZED.get()) return;
        int hr;
        try {
            hr = (int) CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
        } catch (Throwable t) {
            throw new RuntimeException("CoInitializeEx failed", t);
        }
        // S_FALSE: already initialized on this thread. RPC_E_CHANGED_MODE: initialized with a
        // different apartment by code we don't control; COM is still usable, so tolerate it.
        if (hr != S_OK && hr != S_FALSE && hr != RPC_E_CHANGED_MODE) {
            throw new RuntimeException("CoInitializeEx failed (HRESULT 0x" + Integer.toHexString(hr) + ")");
        }
        INITIALIZED.set(true);
    }

    /**
     * Creates a COM object and returns its interface pointer.
     *
     * @param clsid pointer to the class id (16 bytes)
     * @param iid   pointer to the interface id (16 bytes)
     */
    static MemorySegment createInstance(MemorySegment clsid, MemorySegment iid, String op) throws IOException {
        ensureInitialized();
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(ADDRESS);
            int hr = (int) CO_CREATE_INSTANCE.invokeExact(
                clsid, MemorySegment.NULL, CLSCTX_INPROC_SERVER, iid, out);
            checkHr(hr, op);
            return out.get(ADDRESS, 0);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(op + " failed", t);
        }
    }

    static void coTaskMemFree(MemorySegment ptr) {
        if (MemorySegment.NULL.equals(ptr)) return;
        try {
            CO_TASK_MEM_FREE.invokeExact(ptr);
        } catch (Throwable t) {
            throw new RuntimeException("CoTaskMemFree failed", t);
        }
    }

    static void propVariantClear(MemorySegment pv) {
        try {
            int ignored = (int) PROP_VARIANT_CLEAR.invokeExact(pv);
        } catch (Throwable t) {
            throw new RuntimeException("PropVariantClear failed", t);
        }
    }

    // ---- COM vtable dispatch ----

    /**
     * Builds a callable handle for a COM method at vtable {@code index} on the object {@code thisPtr}.
     * {@code desc} must include the implicit {@code this} pointer as its first parameter.
     */
    static MethodHandle method(MemorySegment thisPtr, int index, FunctionDescriptor desc) {
        // COM interface pointers returned from native calls have byteSize 0 in FFM; reinterpret so
        // we can read the vtable pointer at offset 0.
        if (thisPtr.byteSize() == 0) thisPtr = thisPtr.reinterpret(Long.MAX_VALUE);
        var vtbl = thisPtr.get(ADDRESS, 0).reinterpret((long) (index + 1) * ADDRESS.byteSize());
        var fn = vtbl.getAtIndex(ADDRESS, index);
        return LINKER.downcallHandle(fn, desc);
    }

    /** IUnknown::Release (vtable index 2). Safe on NULL. */
    static void release(MemorySegment obj) {
        if (obj == null || MemorySegment.NULL.equals(obj)) return;
        try {
            int ignored = (int) method(obj, 2, FunctionDescriptor.of(JAVA_INT, ADDRESS)).invokeExact(obj);
        } catch (Throwable t) {
            throw new RuntimeException("IUnknown::Release failed", t);
        }
    }

    /** IUnknown::QueryInterface (vtable index 0). Returns the queried pointer, or NULL on failure. */
    static MemorySegment queryInterface(MemorySegment obj, MemorySegment iid) {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(ADDRESS);
            int hr = (int) method(obj, 0,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)).invokeExact(obj, iid, out);
            return failed(hr) ? MemorySegment.NULL : out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw new RuntimeException("IUnknown::QueryInterface failed", t);
        }
    }

    // ---- GUID / PROPERTYKEY ----

    /** Parses a canonical GUID string into a freshly allocated 16-byte segment in {@code arena}. */
    static MemorySegment guid(Arena arena, String canonical) {
        var hex = canonical.replace("-", "");
        if (hex.length() != 32) {
            throw new IllegalArgumentException("Bad GUID: " + canonical);
        }
        var seg = arena.allocate(GUID_SIZE);
        long data1 = Long.parseLong(hex.substring(0, 8), 16);
        seg.set(JAVA_INT, 0, (int) data1);
        seg.set(JAVA_SHORT, 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        seg.set(JAVA_SHORT, 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) {
            seg.set(JAVA_BYTE, 8 + i, (byte) Integer.parseInt(hex.substring(16 + i * 2, 18 + i * 2), 16));
        }
        return seg;
    }

    /** Allocates a PROPERTYKEY (GUID fmtid + DWORD pid) in {@code arena}. */
    static MemorySegment propertyKey(Arena arena, String fmtid, int pid) {
        var seg = arena.allocate(PROPERTYKEY_SIZE);
        var g = guid(arena, fmtid);
        MemorySegment.copy(g, 0, seg, 0, GUID_SIZE);
        seg.set(JAVA_INT, GUID_SIZE, pid);
        return seg;
    }

    static boolean guidEquals(MemorySegment a, MemorySegment b) {
        for (int i = 0; i < GUID_SIZE; i++) {
            if (a.get(JAVA_BYTE, i) != b.get(JAVA_BYTE, i)) return false;
        }
        return true;
    }

    // ---- Wide strings ----

    /** Allocates a null-terminated UTF-16 copy of {@code s} in {@code arena}. */
    static MemorySegment wstr(Arena arena, String s) {
        var seg = arena.allocate(JAVA_CHAR, s.length() + 1L);
        for (int i = 0; i < s.length(); i++) {
            seg.setAtIndex(JAVA_CHAR, i, s.charAt(i));
        }
        seg.setAtIndex(JAVA_CHAR, s.length(), '\0');
        return seg;
    }

    /** Reads a null-terminated UTF-16 string from {@code ptr}; returns "" for NULL. */
    static String readWstr(MemorySegment ptr) {
        if (ptr == null || MemorySegment.NULL.equals(ptr)) return "";
        var seg = ptr.reinterpret(Long.MAX_VALUE);
        var sb = new StringBuilder();
        for (long i = 0; ; i++) {
            char c = seg.getAtIndex(JAVA_CHAR, i);
            if (c == '\0') break;
            sb.append(c);
        }
        return sb.toString();
    }
}
