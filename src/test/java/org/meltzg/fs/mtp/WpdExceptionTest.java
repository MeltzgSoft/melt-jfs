package org.meltzg.fs.mtp;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WpdExceptionTest {

    @Test
    public void causedByDeviceHungFindsNestedCause() {
        var cause = new WpdException("IStream::Write", WpdException.E_WPD_DEVICE_IS_HUNG);
        var wrapped = new RuntimeException("outer", new IOException("middle", cause));

        assertTrue(WpdException.causedByDeviceHung(wrapped));
    }

    @Test
    public void causedByDeviceHungIgnoresOtherHresults() {
        var cause = new WpdException("IStream::Write", 0x80004005);

        assertFalse(WpdException.causedByDeviceHung(cause));
    }
}
