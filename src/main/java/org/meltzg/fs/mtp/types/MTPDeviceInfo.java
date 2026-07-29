package org.meltzg.fs.mtp.types;

public record MTPDeviceInfo(
        MTPDeviceIdentifier deviceId,
        String friendlyName,
        String description,
        String manufacturer,
        long busLocation,
        long devNum) {
}
