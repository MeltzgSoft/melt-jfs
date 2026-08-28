package org.meltzg.fs.mtp;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

final class WpdException extends IOException {
    static final int E_WPD_DEVICE_IS_HUNG = 0x802A0006;

    private final int hresult;

    WpdException(String operation, int hresult) {
        super(operation + " failed (HRESULT 0x" + Integer.toHexString(hresult) + ")");
        this.hresult = hresult;
    }

    int hresult() {
        return hresult;
    }

    boolean isDeviceHung() {
        return hresult == E_WPD_DEVICE_IS_HUNG;
    }

    static boolean causedByDeviceHung(Throwable t) {
        return hasHresult(t, E_WPD_DEVICE_IS_HUNG);
    }

    static boolean hasHresult(Throwable t, int hresult) {
        return hasHresult(t, hresult, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean hasHresult(Throwable t, int hresult, Set<Throwable> seen) {
        if (t == null || !seen.add(t)) return false;
        if (t instanceof WpdException wpd && wpd.hresult() == hresult) return true;
        if (hasHresult(t.getCause(), hresult, seen)) return true;
        for (var suppressed : t.getSuppressed()) {
            if (hasHresult(suppressed, hresult, seen)) return true;
        }
        return false;
    }
}
