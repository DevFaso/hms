package com.example.hms.mapper;

final class MapperDefaults {
    private MapperDefaults() {
    }

    static boolean booleanOrDefault(Boolean value, boolean defaultValue) {
        return value != null ? value : defaultValue;
    }
}
