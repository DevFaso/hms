package com.example.hms.service;

import java.util.Locale;
import java.util.Map;

public interface FeatureFlagService {

    Map<String, Boolean> listFlags(String environment, Locale locale);

    Map<String, Boolean> upsertOverride(
        String flagKey,
        boolean enabled,
        String description,
        String updatedBy,
        String environment,
        Locale locale
    );

    Map<String, Boolean> deleteOverride(
        String flagKey,
        String updatedBy,
        String environment,
        Locale locale
    );
}
