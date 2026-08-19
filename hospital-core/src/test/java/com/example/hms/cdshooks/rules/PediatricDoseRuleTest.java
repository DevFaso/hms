package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.model.Patient;
import com.example.hms.model.medication.MedicationCatalogItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PediatricDoseRuleTest {

    private final PediatricDoseRule rule = new PediatricDoseRule();

    private static Patient infant(int ageMonths) {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        p.setDateOfBirth(LocalDate.now().minusMonths(ageMonths));
        return p;
    }

    private static Patient adult() {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        p.setDateOfBirth(LocalDate.now().minusYears(35));
        return p;
    }

    private static MedicationCatalogItem catalogWithCeiling(double mgPerKg) {
        MedicationCatalogItem item = MedicationCatalogItem.builder()
            .nameFr("amoxicilline")
            .genericName("amoxicillin")
            .pediatricMaxDoseMgPerKg(BigDecimal.valueOf(mgPerKg))
            .build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private static CdsRuleContext context(Patient patient, MedicationCatalogItem item,
                                          Double doseMg, Double weightKg) {
        return new CdsRuleContext(
            patient, UUID.randomUUID(),
            "amoxicillin", null, null, doseMg,
            item, weightKg,
            List.of(), List.of()
        );
    }

    @Test
    void adultPatientProducesNoCard() {
        assertThat(rule.evaluate(context(adult(), null, 500.0, 70.0))).isEmpty();
    }

    @Test
    void hardCeilingExceededEmitsCritical() {
        Patient infant = infant(18); // 1.5 years
        MedicationCatalogItem item = catalogWithCeiling(50.0);
        // 600 mg / 10 kg = 60 mg/kg > 50 mg/kg ceiling
        List<CdsCard> cards = rule.evaluate(context(infant, item, 600.0, 10.0));

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.CRITICAL);
        assertThat(cards.get(0).summary()).contains("60.00 mg/kg");
    }

    @Test
    void hardCeilingWithinLimitIsSilent() {
        Patient infant = infant(18);
        MedicationCatalogItem item = catalogWithCeiling(50.0);
        // 400 mg / 10 kg = 40 mg/kg < 50 mg/kg ceiling
        assertThat(rule.evaluate(context(infant, item, 400.0, 10.0))).isEmpty();
    }

    @Test
    void softAdvisoryWhenCeilingMissing() {
        Patient child = infant(60); // 5 years
        List<CdsCard> cards = rule.evaluate(context(child, null, 250.0, 18.0));

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
        assertThat(cards.get(0).detail()).contains("18.00 kg");
    }

    @Test
    void softAdvisoryWhenWeightMissing() {
        Patient child = infant(60);
        MedicationCatalogItem item = catalogWithCeiling(50.0);
        List<CdsCard> cards = rule.evaluate(context(child, item, 250.0, null));

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
        assertThat(cards.get(0).detail()).contains("weight not on file");
    }

    @Test
    void nullPatientProducesNoCard() {
        assertThat(rule.evaluate(context(null, null, 500.0, 70.0))).isEmpty();
    }

    @Test
    void hasStableId() {
        assertThat(rule.id()).isEqualTo("pediatric-dose");
    }
}
