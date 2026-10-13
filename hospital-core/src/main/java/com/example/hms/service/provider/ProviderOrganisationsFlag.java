package com.example.hms.service.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code provider.organisations.enabled} (provider plan §6.11, AC-14), default
 * OFF. It gates the provider directory and every picker that offers a
 * provider, and nothing else: onboarding, the provider admin pages, the
 * confinement and the record-access refusal run whatever it says. Security is
 * never flag-gated.
 *
 * <p>One reader of the property, so the directory and {@code GET /provider/settings}
 * cannot disagree.
 */
@Component
public class ProviderOrganisationsFlag {

    @Value("${provider.organisations.enabled:false}")
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }
}
