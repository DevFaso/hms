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
}
