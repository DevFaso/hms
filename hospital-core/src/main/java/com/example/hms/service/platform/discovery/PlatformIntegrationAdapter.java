package com.example.hms.service.platform.discovery;

import com.example.hms.enums.platform.PlatformServiceType;
import java.util.Locale;

public interface PlatformIntegrationAdapter {

    PlatformServiceType getServiceType();

    IntegrationDescriptor describe(Locale locale);

    default boolean isEnabled(Locale locale) {
        return describe(locale).isEnabled();
    }
}
