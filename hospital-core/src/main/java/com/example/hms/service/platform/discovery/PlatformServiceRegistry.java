package com.example.hms.service.platform.discovery;

import com.example.hms.enums.platform.PlatformServiceType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public interface PlatformServiceRegistry {

    List<IntegrationDescriptor> listIntegrations(boolean includeDisabled, Locale locale);

    default List<IntegrationDescriptor> listEnabledIntegrations(Locale locale) {
        return listIntegrations(false, locale);
    }

    Optional<IntegrationDescriptor> findIntegration(PlatformServiceType serviceType, Locale locale);
}
