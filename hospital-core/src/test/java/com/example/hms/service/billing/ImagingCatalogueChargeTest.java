package com.example.hms.service.billing;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.PriceListPrice;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.PriceListPriceRepository;
import com.example.hms.service.billing.VisitChargeSource.Visit;
import com.example.hms.service.billing.VisitChargeSource.VisitCharge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D3254 (r70): « Radiographie du thorax (face) », the price list's own label, billed 0 F because the order's
 * free text was not the price key word for word. An order picked from the imaging catalogue is priced by its item,
 * whatever it is called; and whatever happens, every billable imaging order gives ONE line: priced, or posted
 * unpriced for the cashier (« à tarifer »), never left off the invoice.
 */
@DisplayName("D3254: an imaging order is priced by its catalogue exam, or posted unpriced; never dropped")
class ImagingCatalogueChargeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T09:00:00Z"), ZoneOffset.UTC);

    private final ImagingOrderRepository orders = mock(ImagingOrderRepository.class);
    private final PriceListItemRepository items = mock(PriceListItemRepository.class);
    private final PriceListPriceRepository prices = mock(PriceListPriceRepository.class);
    private final TariffResolver resolver = new TariffResolver(items, prices, CLOCK, "Africa/Ouagadougou");
    private final ImagingOrderChargeSource source = new ImagingOrderChargeSource(orders);

    private final UUID hospitalId = UUID.randomUUID();
    private Encounter encounter;
    private PriceListItem chestExam;

    @BeforeEach
    void setUp() {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setHospital(hospital);
        chestExam = PriceListItem.builder().hospitalId(hospitalId).code("RX-THORAX")
            .label("Radiographie du thorax (face)").category(PriceListCategory.IMAGING)
            .linkType(PriceListLinkType.IMAGING_STUDY).linkKey("XRAY:radiographie thorax face").build();
        chestExam.setId(UUID.randomUUID());
        PriceListPrice version = PriceListPrice.builder().hospitalId(hospitalId).item(chestExam)
            .unitPrice(new BigDecimal("7500")).effectiveFrom(TODAY.minusDays(30)).build();
        version.setId(UUID.randomUUID());
        when(items.findActiveByIdForCharge(chestExam.getId(), hospitalId)).thenReturn(Optional.of(chestExam));
        when(items.findActiveByLinkForCharge(hospitalId, PriceListLinkType.IMAGING_STUDY,
            "XRAY:radiographie thorax face")).thenReturn(List.of(chestExam));
        when(prices.findInForce(eq(chestExam.getId()), eq(hospitalId), any())).thenReturn(List.of(version));
    }

    private ImagingOrder order(String studyType, UUID catalogueItemId) {
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setModality(ImagingModality.XRAY);
        order.setStudyType(studyType);
        order.setCatalogueItemId(catalogueItemId);
        return order;
    }

    private List<VisitCharge> chargesOf(ImagingOrder... visitOrders) {
        when(orders.findByEncounterIdAndStatusNotIn(eq(encounter.getId()), any())).thenReturn(List.of(visitOrders));
        Visit visit = new Visit(encounter, LocalDateTime.of(2026, 11, 22, 9, 0), new VisitPriceList(List.of()),
            new TariffResolver.Tariffs(resolver, hospitalId, TODAY));
        return source.charges(visit);
    }

    @Test
    @DisplayName("the r70 case: the price list's own label, picked from the catalogue, is priced 7 500 (it billed 0 F as free text)")
    void aCatalogueOrderIsPricedByItsItem() {
        List<VisitCharge> lines = chargesOf(order("Radiographie du thorax (face)", chestExam.getId()));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).unitPrice()).isEqualByComparingTo("7500");
        assertThat(lines.get(0).tariff().itemId()).isEqualTo(chestExam.getId());
        assertThat(lines.get(0).description()).isEqualTo("Radiographie du thorax (face)");
    }

    @Test
    @DisplayName("the wording no longer matters: any study type on a catalogue order takes the item's price")
    void theWordingOfACatalogueOrderDoesNotMatter() {
        List<VisitCharge> lines = chargesOf(order("RX thorax F (au lit)", chestExam.getId()));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).unitPrice()).isEqualByComparingTo("7500");
    }

    @Test
    @DisplayName("a free-text order keeps the link lookup; without a match it is posted unpriced, not dropped")
    void aFreeTextOrderIsPricedByLinkOrPostedUnpriced() {
        List<VisitCharge> lines = chargesOf(order("Radiographie thorax face", null),
            order("Radiographie du thorax (face)", null));

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).unitPrice()).as("word for word: the link key").isEqualByComparingTo("7500");
        assertThat(lines.get(1).unitPrice()).as("no tariff, no service: for the cashier to price").isNull();
        assertThat(lines.get(1).tariff()).isNull();
        assertThat(lines.get(1).description()).isEqualTo("Radiographie du thorax (face)");
        verify(items, never()).findActiveByIdForCharge(any(), any());
    }

    @Test
    @DisplayName("an item that is gone, of another hospital or with no price in force prices nothing: the line is still posted")
    void anUnusableCatalogueItemPostsTheLineUnpriced() {
        UUID gone = UUID.randomUUID();
        when(items.findActiveByIdForCharge(gone, hospitalId)).thenReturn(Optional.empty());
        PriceListItem elsewhere = PriceListItem.builder().hospitalId(UUID.randomUUID()).code("X").label("Ailleurs")
            .category(PriceListCategory.IMAGING).build();
        elsewhere.setId(UUID.randomUUID());
        when(items.findActiveByIdForCharge(elsewhere.getId(), hospitalId)).thenReturn(Optional.of(elsewhere));
        PriceListItem unpriced = PriceListItem.builder().hospitalId(hospitalId).code("ECHO").label("Échographie")
            .category(PriceListCategory.IMAGING).build();
        unpriced.setId(UUID.randomUUID());
        when(items.findActiveByIdForCharge(unpriced.getId(), hospitalId)).thenReturn(Optional.of(unpriced));
        when(prices.findInForce(eq(unpriced.getId()), eq(hospitalId), any())).thenReturn(List.of());

        List<VisitCharge> lines = chargesOf(order("Scanner", gone), order("Ailleurs", elsewhere.getId()),
            order("Échographie", unpriced.getId()));

        assertThat(lines).hasSize(3).allSatisfy(line -> {
            assertThat(line.unitPrice()).isNull();
            assertThat(line.tariff()).isNull();
        });
    }

    @Test
    @DisplayName("guard: every billable imaging order of a visit gives exactly one line, priced or unpriced")
    void everyOrderGivesOneLine() {
        List<VisitCharge> lines = chargesOf(order("Radiographie du thorax (face)", chestExam.getId()),
            order("Radiographie thorax face", null), order("Examen jamais tarifé", null),
            order("Examen au catalogue disparu", UUID.randomUUID()));

        assertThat(lines).hasSize(4);
        assertThat(lines).extracting(VisitCharge::description).doesNotContainNull();
    }
}
