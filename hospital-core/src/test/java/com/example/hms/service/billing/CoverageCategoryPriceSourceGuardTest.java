package com.example.hms.service.billing;

import com.example.hms.enums.InvoiceLinePriceSource;
import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.PayerPlanRule;
import com.example.hms.model.PayerPlanTerms;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3603 (r71), the whole class: an imaging line priced by hand at the till (posted unpriced, no price-list item)
 * was billed at the plan's general rate (75 %) instead of its IMAGING rule (50 %), so the insurer's claim on
 * TWP-2026-000022 was overstated by 1 875 F. A payer plan's category rule must apply to every billable order type
 * that has a category, whatever the line's price source.
 *
 * <p>For each {@link BillableOrderType} on the visit invoice that names a category, and each
 * {@link InvoiceLinePriceSource} (LEGACY excepted: written before V303, nothing to read), the line is posted as the
 * checkout posts it, then priced the way that source prices it - including the cashier's edit sending the line back
 * as a plain SERVICE - and the plan's category rate must win over its default rate. A new price source or a new
 * order type fails here until it is covered.
 */
class CoverageCategoryPriceSourceGuardTest {

    private static final int DEFAULT_PERCENT = 75;
    private static final int CATEGORY_PERCENT = 50;
    private static final BigDecimal PRICE = new BigDecimal("7500.00");

    /** How each price source leaves a line; the category map is the hospital's price-list items read by id. */
    private record Priced(InvoiceItem line, Map<UUID, PriceListCategory> itemCategories) {
    }

    private static Map<InvoiceLinePriceSource, Function<BillableOrderType, Priced>> priceSources() {
        Map<InvoiceLinePriceSource, Function<BillableOrderType, Priced>> sources =
            new EnumMap<>(InvoiceLinePriceSource.class);
        // From the hospital's price list: the item's own category.
        sources.put(InvoiceLinePriceSource.TARIFF, type -> {
            UUID itemId = UUID.randomUUID();
            InvoiceItem line = posted(type, VisitChargeSource.VisitCharge.tariffed(
                new TariffResolver.Tariff(itemId, UUID.randomUUID(), "Libellé", PRICE), ItemCategory.SERVICE, 1));
            return new Priced(line, Map.of(itemId, type.coverageCategory()));
        });
        // By a service of the same name: no item.
        sources.put(InvoiceLinePriceSource.SERVICE_NAME, type -> {
            Treatment service = new Treatment();
            service.setName("Service");
            service.setPrice(PRICE);
            InvoiceItem line = posted(type, VisitChargeSource.VisitCharge.of("Service", ItemCategory.SERVICE, 1,
                Optional.of(service)));
            assertThat(line.getPriceSource()).isEqualTo(InvoiceLinePriceSource.SERVICE_NAME);
            return new Priced(line, Map.of());
        });
        // Posted unpriced, then priced by hand at the till (the D3603 case): no item, and the cashier's edit sends
        // the line back as a plain SERVICE.
        sources.put(InvoiceLinePriceSource.MANUAL, type -> {
            InvoiceItem line = posted(type, VisitChargeSource.VisitCharge.of("Radiographie du poignet gauche",
                ItemCategory.SERVICE, 1, Optional.empty()));
            assertThat(line.getPriceSource()).isEqualTo(InvoiceLinePriceSource.UNPRICED);
            pricedByHand(line);
            return new Priced(line, Map.of());
        });
        // Still unpriced: the category is already the order's.
        sources.put(InvoiceLinePriceSource.UNPRICED, type -> {
            InvoiceItem line = posted(type, VisitChargeSource.VisitCharge.of("Acte", ItemCategory.SERVICE, 1,
                Optional.empty()));
            assertThat(line.getPriceSource()).isEqualTo(InvoiceLinePriceSource.UNPRICED);
            line.setUnitPrice(PRICE);
            line.setTotalPrice(PRICE);
            return new Priced(line, Map.of());
        });
        return sources;
    }

    /** A tariff line overridden by hand (MANUAL with the tariff kept): its item still says its category. */
    private static Priced overriddenTariff(BillableOrderType type) {
        Priced tariff = priceSources().get(InvoiceLinePriceSource.TARIFF).apply(type);
        tariff.line().setTariffUnitPrice(tariff.line().getUnitPrice());
        tariff.line().setOverrideReason("Remise convenue");
        pricedByHand(tariff.line());
        return tariff;
    }

    private static void pricedByHand(InvoiceItem line) {
        line.setUnitPrice(PRICE);
        line.setTotalPrice(PRICE);
        line.setPriceSource(InvoiceLinePriceSource.MANUAL);
        line.setItemCategory(ItemCategory.SERVICE);
    }

    private static InvoiceItem posted(BillableOrderType type, VisitChargeSource.VisitCharge charge) {
        InvoiceItem line = EncounterCheckoutChargeListener.line(charge, type, new UserRoleHospitalAssignment());
        line.setId(UUID.randomUUID());
        return line;
    }

    @Test
    void everyPriceSourceIsCovered() {
        EnumSet<InvoiceLinePriceSource> covered = EnumSet.copyOf(priceSources().keySet());
        covered.add(InvoiceLinePriceSource.LEGACY);
        assertThat(covered).containsExactlyInAnyOrder(InvoiceLinePriceSource.values());
    }

    @Test
    void everyBillableOrderOnAnInvoiceDeclaresItsCategory() {
        for (BillableOrderType type : BillableOrderType.values()) {
            if (type.route() == BillableOrderType.Route.PHARMACY_CHECKOUT || type == BillableOrderType.CONSULTATION) {
                // A medicine is never an invoice line; the visit's services are a mix, and the default consultation
                // line names CONSULTATION itself (ConsultationChargeSource).
                assertThat(type.coverageCategory()).as(type.name()).isNull();
            } else {
                assertThat(type.coverageCategory()).as(type.name()).isNotNull();
            }
        }
    }

    @Test
    void theCategoryRuleWinsWhateverThePriceSource() {
        List<String> checked = new ArrayList<>();
        for (BillableOrderType type : BillableOrderType.values()) {
            if (!type.onVisitInvoice() || type.coverageCategory() == null) {
                continue;
            }
            Map<String, Priced> cases = new java.util.LinkedHashMap<>();
            priceSources().forEach((source, price) -> cases.put(source.name(), price.apply(type)));
            cases.put("MANUAL (tariff overridden)", overriddenTariff(type));
            for (Map.Entry<String, Priced> each : cases.entrySet()) {
                InvoiceItem line = each.getValue().line();
                String what = type + " priced " + each.getKey();
                assertThat(InvoiceCoverageService.categoryOf(line, each.getValue().itemCategories()))
                    .as(what).isEqualTo(type.coverageCategory());
                assertThat(insurerShare(line, each.getValue().itemCategories(), type.coverageCategory()))
                    .as(what).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(CATEGORY_PERCENT))
                        .divide(BigDecimal.valueOf(100)));
                checked.add(what);
            }
        }
        assertThat(checked).hasSizeGreaterThanOrEqualTo(5 * 5);
    }

    /** The visit's default consultation is a consultation to the plan, however priced. */
    @Test
    void theDefaultConsultationIsAConsultationHoweverPriced() {
        InvoiceItem line = posted(BillableOrderType.CONSULTATION, VisitChargeSource.VisitCharge
            .worded(BillingText.of("billing.checkout.consultation"), ItemCategory.SERVICE, 1, Optional.empty())
            .coveredAs(PriceListCategory.CONSULTATION));
        pricedByHand(line);
        assertThat(InvoiceCoverageService.categoryOf(line, Map.of())).isEqualTo(PriceListCategory.CONSULTATION);
    }

    private static BigDecimal insurerShare(InvoiceItem line, Map<UUID, PriceListCategory> itemCategories,
                                           PriceListCategory ruled) {
        PayerPlanTerms terms = PayerPlanTerms.builder()
            .defaultCoveragePercent(DEFAULT_PERCENT)
            .effectiveFrom(LocalDate.of(2026, 1, 1))
            .build();
        PayerPlanRule rule = PayerPlanRule.builder()
            .category(ruled)
            .coveragePercent(CATEGORY_PERCENT)
            .effectiveFrom(LocalDate.of(2026, 1, 1))
            .build();
        CoverageCalculator.Line input = new CoverageCalculator.Line(line.getId(), line.getTotalPrice(),
            line.getQuantity(), InvoiceCoverageService.categoryOf(line, itemCategories), line.getPriceListItemId());
        return CoverageCalculator.compute(List.of(input), terms, List.of(rule), BigDecimal.ZERO).total();
    }

    /**
     * QA D3980: a hospital's own service billed with no tariff says what it bills (a line with no category is never
     * covered): a consultation when the hospital named it one, in any of its words, else another service.
     */
    @Test
    void anUntariffedServiceIsAConsultationWhenNamedOneElseAnotherService() {
        Treatment tele = new Treatment();
        tele.setName("Téléconsultation de suivi");
        Treatment consulta = new Treatment();
        consulta.setName("Consulta general");
        Treatment dressing = new Treatment();
        dressing.setName("Pansement simple");
        Treatment unnamed = new Treatment();

        assertThat(ConsultationChargeSource.serviceCategoryOf(tele)).isEqualTo(PriceListCategory.CONSULTATION);
        assertThat(ConsultationChargeSource.serviceCategoryOf(consulta)).isEqualTo(PriceListCategory.CONSULTATION);
        assertThat(ConsultationChargeSource.serviceCategoryOf(dressing)).isEqualTo(PriceListCategory.OTHER_SERVICE);
        assertThat(ConsultationChargeSource.serviceCategoryOf(unnamed)).isEqualTo(PriceListCategory.OTHER_SERVICE);
    }
}
