package com.example.hms.service.platform.discovery.adapter;

import com.example.hms.config.PlatformIntegrationProperties;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** D12: the catalog's own text is resolved in the caller's language; configured overrides are shown as written. */
class CatalogAdapterLocalizationTest {

    private final MessageSource messages = TestMessageSources.bundles();
    private final PlatformIntegrationProperties properties = new PlatformIntegrationProperties();

    private List<AbstractToggleableIntegrationAdapter> adapters() {
        return List.of(
            new EhrIntegrationAdapter(properties, messages),
            new BillingIntegrationAdapter(properties, messages),
            new InventoryIntegrationAdapter(properties, messages));
    }

    @Test
    @DisplayName("every adapter renders its text in EN, FR and ES with no key left unresolved")
    void everyAdapterIsTranslated() {
        for (AbstractToggleableIntegrationAdapter adapter : adapters()) {
            IntegrationDescriptor en = adapter.describe(Locale.ENGLISH);
            for (Locale locale : List.of(Locale.FRENCH, Locale.forLanguageTag("es"))) {
                IntegrationDescriptor other = adapter.describe(locale);
                assertThat(other.getDisplayName()).as("%s displayName in %s", adapter.getServiceType(), locale)
                    .isNotBlank().doesNotStartWith("platform.catalog.");
                assertThat(other.getDescription()).as("%s description in %s", adapter.getServiceType(), locale)
                    .isNotEqualTo(en.getDescription());
                assertThat(other.getCapabilities()).hasSize(3)
                    .doesNotContainAnyElementsOf(en.getCapabilities());
                assertThat(other.getDefaultMetadata().getIntegrationNotes())
                    .isNotEqualTo(en.getDefaultMetadata().getIntegrationNotes());
            }
        }
    }

    @Test
    @DisplayName("the EHR entry reads in French for a French super-admin")
    void ehrInFrench() {
        IntegrationDescriptor fr = new EhrIntegrationAdapter(properties, messages).describe(Locale.FRENCH);

        assertThat(fr.getServiceType()).isEqualTo(PlatformServiceType.EHR);
        assertThat(fr.getDisplayName()).isEqualTo("Interopérabilité DPI (EHR Core)");
        assertThat(fr.getCapabilities()).contains("Réplication des événements ADT HL7 v2");
        assertThat(fr.getDefaultOwnership().getServiceLevel()).isEqualTo("24 h/24, 7 j/7");
    }

    @Test
    @DisplayName("a display name set in configuration wins over the translated default")
    void configuredOverrideIsShownAsWritten() {
        properties.getBilling().setDisplayName("Facturation CNAMU");

        IntegrationDescriptor es = new BillingIntegrationAdapter(properties, messages).describe(Locale.forLanguageTag("es"));

        assertThat(es.getDisplayName()).isEqualTo("Facturation CNAMU");
        assertThat(es.getDescription()).startsWith("Simulador");
    }

    @Test
    @DisplayName("inventory stays disabled by default — the catalog entry D13 refuses to provision")
    void inventoryIsDisabledByDefault() {
        IntegrationDescriptor inventory = new InventoryIntegrationAdapter(properties, messages).describe(Locale.ENGLISH);

        assertThat(inventory.isEnabled()).isFalse();
        assertThat(inventory.isAutoProvision()).isFalse();
    }
}
