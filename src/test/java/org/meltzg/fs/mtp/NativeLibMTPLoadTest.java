package org.meltzg.fs.mtp;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeFalse;

/**
 * Device-free load smoke test for the native libmtp binding. Building {@link NativeLibMTP} resolves
 * the platform's libmtp shared library, binds every {@code LIBMTP_*} symbol and calls
 * {@code LIBMTP_Init} — so this catches per-OS library-name resolution and symbol-name mismatches
 * without needing a connected MTP device. It does NOT exercise struct layouts, which require a
 * device.
 *
 * <p>libmtp is a hard requirement for developing this binding, so the test fails (rather than skips)
 * when the library is absent. The sole exception is Windows, whose backend is WPD, not libmtp.
 */
public class NativeLibMTPLoadTest {

    @Test
    public void loadsAndBindsNativeLibmtp() {
        assumeFalse("Windows uses the WPD backend, not libmtp",
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        // Throws (UnsatisfiedLinkError / ExceptionInInitializerError) if libmtp cannot be found or a
        // symbol fails to bind — the cross-platform load contract this test exists to protect.
        assertNotNull(NativeLibMTP.getInstance());
    }
}
