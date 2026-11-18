package com.example.hms.service.billing;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.PriceListPrice;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.PriceListPriceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 3: the tariff a charge takes - the hospital's own active item on the link, and the version in
 * force on the service day; never another hospital's, never a global read for a missing hospital.
 */
class TariffResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"), ZoneOffset.UTC);

    private final PriceListItemRepository items = mock(PriceListItemRepository.class);
    private final PriceListPriceRepository prices = mock(PriceListPriceRepository.class);
    private final TariffResolver resolver = new TariffResolver(items, prices, CLOCK, "Africa/Ouagadougou");
    private final UUID hospital = UUID.randomUUID();

    private PriceListItem item(UUID hospitalId, String label) {
        PriceListItem item = PriceListItem.builder().hospitalId(hospitalId).code("CS-GEN").label(label)
            .category(PriceListCategory.CONSULTATION).build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private static PriceListPrice version(PriceListItem item, String price, LocalDate from, LocalDate to) {
        PriceListPrice version = PriceListPrice.builder().hospitalId(item.getHospitalId()).item(item)
            .unitPrice(new BigDecimal(price)).effectiveFrom(from).effectiveTo(to).build();
        version.setId(UUID.randomUUID());
        return version;
    }

    @Test
    @DisplayName("#1179 HIGH class: a null hospital finds nothing and reads no price list at all")
    void aNullHospitalNeverReadsAnyList() {
        assertThat(resolver.resolve(null, PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT", TODAY)).isEmpty();
        assertThat(new TariffResolver.Tariffs(resolver, null, TODAY)
            .find(PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT")).isEmpty();
        verifyNoInteractions(items, prices);
    }

    @Test
    @DisplayName("the version in force on the service day prices the line, with the label as typed")
    void theVersionInForceThatDay() {
        PriceListItem item = item(hospital, "Consultation générale");
        PriceListPrice inForce = version(item, "2000", TODAY.minusDays(10), null);
        when(items.findActiveByLinkForCharge(hospital, PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT"))
            .thenReturn(List.of(item));
        when(prices.findInForce(item.getId(), hospital, TODAY)).thenReturn(List.of(inForce));

        TariffResolver.Tariff tariff = resolver.resolve(hospital, PriceListLinkType.SYSTEM, "consultation_default",
            TODAY).orElseThrow();
        assertThat(tariff.unitPrice()).isEqualByComparingTo("2000");
        assertThat(tariff.label()).isEqualTo("Consultation générale");
        assertThat(tariff.itemId()).isEqualTo(item.getId());
        assertThat(tariff.priceId()).isEqualTo(inForce.getId());
    }

    @Test
    @DisplayName("D-4: a price that starts tomorrow never prices today's visit, and a replaced one never prices at all")
    void tomorrowsPriceAndReplacedVersionsDoNotApply() {
        PriceListItem item = item(hospital, "Consultation");
        PriceListPrice tomorrow = version(item, "2500", TODAY.plusDays(1), null);
        PriceListPrice replaced = version(item, "9999", TODAY, TODAY.minusDays(1));
        when(items.findActiveByLinkForCharge(any(), any(), any())).thenReturn(List.of(item));
        // Even a repository that answered too much: only a version covering the day is taken.
        when(prices.findInForce(item.getId(), hospital, TODAY)).thenReturn(List.of(tomorrow, replaced));

        assertThat(resolver.resolve(hospital, PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT", TODAY)).isEmpty();
    }

    @Test
    @DisplayName("another hospital's item never prices this hospital's charge, even on the same link")
    void anotherHospitalsItemIsIgnored() {
        PriceListItem foreign = item(UUID.randomUUID(), "Consultation B");
        when(items.findActiveByLinkForCharge(any(), any(), any())).thenReturn(List.of(foreign));
        when(prices.findInForce(any(), any(), any()))
            .thenReturn(List.of(version(foreign, "1500", TODAY.minusDays(1), null)));

        assertThat(resolver.resolve(hospital, PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT", TODAY)).isEmpty();
    }

    @Test
    @DisplayName("the procedure code is looked up trimmed, single-spaced and in upper case, as it was stored")
    void theProcedureKeyIsNormalisedOnLookup() {
        PriceListItem item = item(hospital, "Suture simple");
        when(items.findActiveByLinkForCharge(hospital, PriceListLinkType.PROCEDURE_CODE, "SUT 01"))
            .thenReturn(List.of(item));
        when(prices.findInForce(item.getId(), hospital, TODAY))
            .thenReturn(List.of(version(item, "3000", TODAY, null)));

        assertThat(resolver.resolve(hospital, PriceListLinkType.PROCEDURE_CODE, "  sut   01 ", TODAY)).isPresent();
    }

    @Test
    @DisplayName("a key that cannot be one (blank, no modality) is no tariff, never an error at checkout")
    void anImpossibleKeyIsNoTariff() {
        assertThat(resolver.resolve(hospital, PriceListLinkType.PROCEDURE_CODE, "  ", TODAY)).isEmpty();
        assertThat(resolver.resolve(hospital, PriceListLinkType.IMAGING_STUDY, "radio thorax", TODAY)).isEmpty();
        assertThat(resolver.resolve(hospital, PriceListLinkType.LAB_TEST_DEFINITION, "TDR", TODAY)).isEmpty();
        verifyNoInteractions(items, prices);
    }

    @Test
    @DisplayName("D-14/D-16: the service day is the Africa/Ouagadougou day of the checkout")
    void theBusinessDay() {
        assertThat(resolver.businessDayOf(null)).isEqualTo(TODAY);
        assertThat(resolver.today()).isEqualTo(TODAY);
        assertThat(resolver.businessDayOf(java.time.LocalDateTime.of(2026, 11, 14, 12, 0)))
            .isEqualTo(LocalDate.of(2026, 11, 14));
    }

    @Test
    @DisplayName("review #1206 (deadlock class): a checkout share-locks the hospital's linked items once, in code "
        + "order, before it prices any charge")
    void aCheckoutLocksItsItemsUpFrontInCodeOrder() {
        TariffResolver.Tariffs tariffs = new TariffResolver.Tariffs(resolver, hospital, TODAY);
        tariffs.find(PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT");
        tariffs.find(PriceListLinkType.SYSTEM, "BED_DAY");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(items);
        order.verify(items).lockActiveLinkedForCheckout(hospital);
        order.verify(items, times(2)).findActiveByLinkForCharge(any(), any(), any());
        verify(items, times(1)).lockActiveLinkedForCheckout(hospital);
    }

    @Test
    @DisplayName("review #1206: the up-front lock query orders by code (the import's order)")
    void theUpFrontLockOrdersByCode() throws NoSuchMethodException {
        String query = PriceListItemRepository.class.getMethod("lockActiveLinkedForCheckout", UUID.class)
            .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
        assertThat(query.replaceAll("\\s+", " ")).contains("ORDER BY i.code");
        String importQuery = PriceListItemRepository.class
            .getMethod("lockByHospitalIdAndCodes", UUID.class, java.util.Collection.class)
            .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
        assertThat(importQuery.replaceAll("\\s+", " ")).contains("ORDER BY i.code");
    }
}
