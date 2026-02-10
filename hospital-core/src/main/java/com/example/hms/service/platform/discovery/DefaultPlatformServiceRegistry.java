package com.example.hms.service.platform.discovery;

import com.example.hms.enums.platform.PlatformServiceType;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultPlatformServiceRegistry implements PlatformServiceRegistry {

    private final List<PlatformIntegrationAdapter> adapters;

    @Override
    public List<IntegrationDescriptor> listIntegrations(boolean includeDisabled, Locale locale) {
        Locale safeLocale = locale == null ? Locale.getDefault() : locale;

        return adapters.stream()
            .map(adapter -> adapter.describe(safeLocale))
            .filter(Objects::nonNull)
            .filter(descriptor -> includeDisabled || descriptor.isEnabled())
            .sorted(Comparator.comparing(IntegrationDescriptor::getDisplayName, Comparator.nullsLast(String::compareToIgnoreCase)))
            .toList();
    }

    @Override
    public Optional<IntegrationDescriptor> findIntegration(PlatformServiceType serviceType, Locale locale) {
        if (serviceType == null) {
            return Optional.empty();
        }
        Locale safeLocale = locale == null ? Locale.getDefault() : locale;

        return adapters.stream()
            .filter(adapter -> adapter.getServiceType() == serviceType)
            .findFirst()
            .map(adapter -> adapter.describe(safeLocale));
    }
}
