package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateMedicationOrderRuleTest {

    private final DuplicateMedicationOrderRule rule = new DuplicateMedicationOrderRule();

    private static Prescription priorRx(String name, PrescriptionStatus status, int daysAgo) {
        Prescription p = Prescription.builder()
            .medicationName(name)
            .status(status)
            .build();
        p.setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
        return p;
    }

    private static CdsRuleContext context(String proposedName, String proposedRx,
                                          List<Prescription> priors, List<String> rxnorms) {
        return new CdsRuleContext(
            null, UUID.randomUUID(),
            proposedName, null, proposedRx, null,
            null, null,
            priors, rxnorms
        );
    }

    @Test
    void rxnormMatchEmitsWarning() {
        CdsRuleContext ctx = context("Amoxicillin 500mg", "723",
            List.of(priorRx("Amox 500", PrescriptionStatus.SIGNED, 2)),
            List.of("723"));

        List<CdsCard> cards = rule.evaluate(ctx);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
        assertThat(cards.get(0).summary()).contains("Amox 500");
    }

    /** QA D1049 (round 18): the date reads in the reader's language, never "2026-10-30". */
    @Test
    void theDuplicateDateIsWrittenForTheReader() {
        org.springframework.context.support.ReloadableResourceBundleMessageSource source =
            new org.springframework.context.support.ReloadableResourceBundleMessageSource();
        source.setBasenames(com.example.hms.config.MessageBundles.basenames());
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
        rule.setCardText(new com.example.hms.cdshooks.CdsCardText(source));
        Prescription prior = priorRx("Paracétamol", PrescriptionStatus.SIGNED, 1);
        prior.setCreatedAt(java.time.LocalDate.now().minusDays(1).atTime(9, 0));
        String iso = prior.getCreatedAt().toLocalDate().toString();
        try {
            for (java.util.Locale locale : List.of(java.util.Locale.FRENCH, java.util.Locale.forLanguageTag("es"))) {
                org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
                CdsCard card = rule.evaluate(context("paracétamol", null, List.of(prior),
                    Arrays.asList((String) null))).get(0);
                String expected = prior.getCreatedAt().toLocalDate().format(java.time.format.DateTimeFormatter
                    .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale));
                assertThat(card.summary()).as(locale.toString()).contains(expected).doesNotContain(iso);
            }
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void substringFallbackMatchesFreetextHistory() {
        CdsRuleContext ctx = context("amoxicillin 250mg", null,
            List.of(priorRx("Amoxicillin", PrescriptionStatus.DISPENSED, 3)),
            Arrays.asList((String) null));

        assertThat(rule.evaluate(ctx)).hasSize(1);
    }

    @Test
    void priorBeyondLookbackIgnored() {
        CdsRuleContext ctx = context("Amoxicillin", null,
            List.of(priorRx("Amoxicillin", PrescriptionStatus.SIGNED, 30)),
            Arrays.asList((String) null));

        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void unrelatedMedicationIsSilent() {
        CdsRuleContext ctx = context("Paracetamol", null,
            List.of(priorRx("Amoxicillin", PrescriptionStatus.SIGNED, 1)),
            Arrays.asList((String) null));

        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void emptyProposedNameAndRxnormIsSilent() {
        CdsRuleContext ctx = context("", null,
            List.of(priorRx("Amoxicillin", PrescriptionStatus.SIGNED, 1)),
            Arrays.asList((String) null));

        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void hasStableId() {
        assertThat(rule.id()).isEqualTo("duplicate-medication-order");
    }

    private static CdsRuleContext withItem(String name, com.example.hms.model.medication.MedicationCatalogItem item,
                                           ParacetamolFacts facts, Prescription prior) {
        return new CdsRuleContext(null, UUID.randomUUID(), name, null, null, null, item, null, List.of(prior),
            Arrays.asList((String) null), null, null, null, null, facts);
    }

    private static com.example.hms.model.medication.MedicationCatalogItem paracetamolItem() {
        com.example.hms.model.medication.MedicationCatalogItem item = com.example.hms.model.medication.MedicationCatalogItem
            .builder().genericName("Paracétamol").brandName("Doliprane").nameFr("Paracétamol").build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private static final ParacetamolFacts APPLIES = new ParacetamolFacts(true, List.of(), java.util.Set.of());

    /** QA D1478: Doliprane sent with the Paracétamol item, over an active Paracétamol. */
    @Test
    void aBrandOverItsOwnDrugIsADuplicate() {
        com.example.hms.model.medication.MedicationCatalogItem item = paracetamolItem();
        assertThat(rule.evaluate(withItem("Doliprane 1 g", item, APPLIES, priorRx("Paracétamol 500 mg", PrescriptionStatus.SIGNED, 1))))
            .hasSize(1);
    }

    @Test
    void theSameItemIsADuplicateWhateverTheOrderIsCalled() {
        com.example.hms.model.medication.MedicationCatalogItem item = paracetamolItem();
        Prescription prior = priorRx("Antalgique du soir", PrescriptionStatus.SIGNED, 1);
        prior.setMedicationCatalogItemId(item.getId());
        assertThat(rule.evaluate(withItem("Doliprane", item, ParacetamolFacts.NONE, prior))).hasSize(1);
    }

    @Test
    void twoFreeTextNamesOfParacetamolAreADuplicate() {
        assertThat(rule.evaluate(withItem("Efferalgan 1 g", null, APPLIES, priorRx("Paracétamol", PrescriptionStatus.SIGNED, 1))))
            .hasSize(1);
    }

    /** Its paracetamol content is another question, on the total's card. */
    @Test
    void aCombinationIsNotADuplicateOfTheDrugAlone() {
        assertThat(rule.evaluate(withItem("Doliprane 1 g", paracetamolItem(), APPLIES,
            priorRx("Paracétamol + codéine", PrescriptionStatus.SIGNED, 1)))).isEmpty();
    }

    @Test
    void anotherDrugIsNoDuplicate() {
        assertThat(rule.evaluate(withItem("Doliprane 1 g", paracetamolItem(), APPLIES,
            priorRx("Amoxicilline", PrescriptionStatus.SIGNED, 1)))).isEmpty();
        assertThat(rule.evaluate(withItem("Ibuprofène", null, ParacetamolFacts.NONE,
            priorRx("Paracétamol", PrescriptionStatus.SIGNED, 1)))).isEmpty();
    }
}
