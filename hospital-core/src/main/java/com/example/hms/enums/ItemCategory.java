package com.example.hms.enums;

public enum ItemCategory {
    SERVICE("Service"),
    MEDICATION("Medication"),
    LAB_TEST("Lab Test"),
    GENERAL("General");

    private final String displayName;

    ItemCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
