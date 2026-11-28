package com.example.hms.service.pharmacy;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.service.pharmacy.FillPriceGate.Standing;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D4181 (r81): « Sirop Paracétamol 120 mg/5 mL », 900 F at the main pharmacy, was handed over at 0 F from the
 * second store, whose item had no price. The rule: priced, documented as free, or refused.
 */
@DisplayName("QA D4181: a fill is priced at its pharmacy, documented as free there, or refused")
class FillPriceGateTest {

    private final InventoryItemRepository items = mock(InventoryItemRepository.class);
    private Pharmacy mainPharmacy;
    private Pharmacy secondStore;
    private MedicationCatalogItem syrup;

    @BeforeEach
    void theSyrupAtTwoStores() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        mainPharmacy = Pharmacy.builder().name("Pharmacie principale").build();
        mainPharmacy.setId(UUID.randomUUID());
        secondStore = Pharmacy.builder().name("Réserve de service Médecine").build();
        secondStore.setId(UUID.randomUUID());
        syrup = new MedicationCatalogItem();
        syrup.setId(UUID.randomUUID());
        when(items.findByPharmacyIdAndMedicationCatalogItemId(mainPharmacy.getId(), syrup.getId()))
            .thenReturn(Optional.of(InventoryItem.builder().unitPrice(new BigDecimal("900")).build()));
        // What a transfer left at its destination before the fix: the item, with no price.
        when(items.findByPharmacyIdAndMedicationCatalogItemId(secondStore.getId(), syrup.getId()))
            .thenReturn(Optional.of(InventoryItem.builder().build()));
    }

    @AfterEach
    void reset() {
        MessageUtil.setMessageSource(null);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("the 900 F syrup is PRICED at the main pharmacy and refused at the store that never priced it")
    void theUnpricedStoreIsRefused() {
        assertThat(FillPriceGate.require(mainPharmacy, syrup, "Sirop Paracétamol 120 mg/5 mL", items))
            .isEqualTo(Standing.PRICED);

        assertThatThrownBy(() -> FillPriceGate.require(secondStore, syrup, "Sirop Paracétamol 120 mg/5 mL", items))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> {
                assertThat(refusal.getMessageKey()).isEqualTo(FillPriceGate.NO_PRICE_KEY);
                assertThat(refusal.getField()).isEqualTo("pharmacyId");
                assertThat(refusal.getMessage())
                    .isEqualTo("Sirop Paracétamol 120 mg/5 mL n’a pas de prix de vente à « Réserve de service Médecine » : "
                        + "la délivrance est refusée. Demandez au responsable du magasin ou à un administrateur de "
                        + "l’établissement de fixer le prix de vente de cet article dans l’inventaire, ou de le "
                        + "déclarer gratuit avec un motif.");
            });
    }

    @Test
    @DisplayName("a zero price, a store that does not hold the item and a fill naming no product are all unpriced")
    void everyWayToHaveNoPrice() {
        when(items.findByPharmacyIdAndMedicationCatalogItemId(secondStore.getId(), syrup.getId()))
            .thenReturn(Optional.of(InventoryItem.builder().unitPrice(BigDecimal.ZERO).build()));
        assertThat(FillPriceGate.standingOf(secondStore, syrup, items)).isEqualTo(Standing.UNPRICED);

        when(items.findByPharmacyIdAndMedicationCatalogItemId(secondStore.getId(), syrup.getId()))
            .thenReturn(Optional.empty());
        assertThat(FillPriceGate.standingOf(secondStore, syrup, items)).isEqualTo(Standing.UNPRICED);

        assertThat(FillPriceGate.standingOf(secondStore, null, items)).isEqualTo(Standing.UNPRICED);
        assertThat(FillPriceGate.standingOf(null, syrup, items)).isEqualTo(Standing.UNPRICED);
        assertThatThrownBy(() -> FillPriceGate.require(secondStore, null, null, items))
            .extracting("messageKey").isEqualTo(FillPriceGate.NO_PRICE_KEY);
    }

    @Test
    @DisplayName("an item documented as free passes with nothing to pay, and has no selling price even with a figure left on it")
    void aDocumentedFreeItemPasses() {
        InventoryItem free = InventoryItem.builder().unitPrice(new BigDecimal("900"))
            .freeOfChargeReason("Gratuité des soins des enfants de moins de 5 ans").build();
        when(items.findByPharmacyIdAndMedicationCatalogItemId(secondStore.getId(), syrup.getId()))
            .thenReturn(Optional.of(free));

        assertThat(FillPriceGate.require(secondStore, syrup, "Sirop", items)).isEqualTo(Standing.FREE);
        assertThat(PharmacyListPrice.of(free)).isEmpty();

        free.setFreeOfChargeReason("   ");
        assertThat(FillPriceGate.standingOf(secondStore, syrup, items))
            .as("a blank reason documents nothing: the item is sold at its price").isEqualTo(Standing.PRICED);
    }
}
