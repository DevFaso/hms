package com.example.hms.config;

public final class PasswordRotationPolicy {

    private PasswordRotationPolicy() {
    }

    public static final int MAX_PASSWORD_AGE_DAYS = 90;
    public static final int WARNING_WINDOW_DAYS = 21;
}
