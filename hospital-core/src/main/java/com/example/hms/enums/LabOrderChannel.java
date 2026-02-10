package com.example.hms.enums;

import java.util.Locale;

public enum LabOrderChannel {
    ELECTRONIC,
    PORTAL,
    PHONE,
    FAX,
    EMAIL,
    WRITTEN,
    WALK_IN,
    OTHER;

    public static LabOrderChannel fromCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (LabOrderChannel channel : values()) {
            if (channel.name().equals(normalized)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unsupported lab order channel: " + value);
    }
}
