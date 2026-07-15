package org.meltzg.fs.mtp;

import org.junit.*;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.meltzg.fs.mtp.types.MTPDeviceIdentifier;
import org.meltzg.fs.mtp.types.MTPDeviceInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Integration tests that run against every physically connected MTP device: the parameterized runner
 * enumerates the attached devices and executes each test once per device, labelling each case with the
 * device's friendly name.
 * These are skipped automatically when no device is present or when libmtp is not installed.
 *
 * Usage:
 *   Connect the device(s), then: ./gradlew integrationTest --tests '*IntegrationTest*'
 */
@RunWith(Parameterized.class)
public class MTPDeviceBridgeIntegrationTest {

    /**
     * One parameter set per attached device, labelled with the device's friendly name. When the native
     * backend is unavailable or no device is connected, yields a single placeholder row so the class
     * still runs and self-skips in {@link #setUp()} rather than failing on an empty parameter list.
     */
    @Parameterized.Parameters(name = "{1}")
    public static Collection<Object[]> devices() throws IOException {
        return connectedDevices();
    }

    /** Logs a reason to stdout whenever a test is skipped, so skips aren't silent in the run output. */
    @Rule
    public final TestWatcher skipReasonLogger = skipReasonLogger();

    private final MTPDeviceIdentifier deviceId;
    private final String deviceName;

    public MTPDeviceBridgeIntegrationTest(MTPDeviceIdentifier deviceId, String deviceName) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
    }

    @Before
    public void setUp() throws IOException {
        assumeTrue("native MTP backend not available", isBackendAvailable());
        assumeTrue("no MTP device connected", deviceId != null);
        MTPDeviceBridge.INSTANCE.close();
        var bridge = MTPDeviceBridge.getInstance();
        assumeTrue("device no longer connected: " + deviceName,
            bridge.getDeviceConns().containsKey(deviceId));
    }

    @After
    public void releaseBridge() throws IOException {
        MTPDeviceBridge.INSTANCE.close();
    }

    @Test
    public void deviceIsDetected() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        assertTrue("Expected device to be connected: " + deviceName,
            bridge.getDeviceConns().containsKey(deviceId));
    }

    @Test
    public void deviceInfoIsPopulated() throws IOException {
        var bridge = MTPDeviceBridge.getInstance();
        MTPDeviceInfo info = bridge.getDeviceInfo().get(deviceId);
        assertNotNull("DeviceInfo missing for " + deviceName, info);
        assertFalse("Description should not be empty", info.description().isBlank());
        System.out.printf("Found device: vendor=%d product=%d serial=%s friendlyName=%s description=%s%n",
            deviceId.vendorId(), deviceId.productId(), deviceId.serial(), info.friendlyName(), info.description());
    }

    /**
     * Enumerates every attached device for the parameterized runner as {@code {identifier, friendlyName}}
     * rows. Returns a single placeholder row when no backend or device is available so the test class
     * still loads and self-skips instead of erroring on an empty parameter list.
     */
    static Collection<Object[]> connectedDevices() throws IOException {
        if (!isBackendAvailable()) {
            return List.<Object[]>of(new Object[]{null, "no device"});
        }
        MTPDeviceBridge.INSTANCE.close();
        var bridge = MTPDeviceBridge.getInstance();
        var infos = bridge.getDeviceInfo();
        var params = new ArrayList<Object[]>();
        for (var id : bridge.getDeviceConns().keySet()) {
            params.add(new Object[]{id, displayName(infos.get(id), id)});
        }
        MTPDeviceBridge.INSTANCE.close();
        if (params.isEmpty()) {
            return List.<Object[]>of(new Object[]{null, "no device"});
        }
        return params;
    }

    /**
     * The human-readable label for a device: its friendly name when the device reports one, falling back
     * to its description and finally to the raw {@code vendor:product:serial} identifier.
     */
    static String displayName(MTPDeviceInfo info, MTPDeviceIdentifier id) {
        if (info != null) {
            if (info.friendlyName() != null && !info.friendlyName().isBlank()) return info.friendlyName();
            if (info.description() != null && !info.description().isBlank()) return info.description();
        }
        return String.valueOf(id);
    }

    /** A {@link TestWatcher} that prints the assumption message behind every skipped test. */
    static TestWatcher skipReasonLogger() {
        return new TestWatcher() {
            @Override
            protected void skipped(org.junit.AssumptionViolatedException e, Description description) {
                System.out.println("[SKIPPED] " + description.getDisplayName() + " -> " + e.getMessage());
            }
        };
    }

    static boolean isBackendAvailable() {
        try {
            // Loads the platform's native backend: libmtp on Linux/macOS, WPD (ole32) on Windows.
            MtpBackend.defaultBackend();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
