package com.example.hms.cdshooks.terminology;

import com.example.hms.cdshooks.terminology.ProblemLoincBindings.LoincCoding;
import com.example.hms.terminology.TerminologyCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemLoincBindingsTest {

    @Test
    void resolvesByThreeCharPrefixForChronicConditions() {
        // Each anchor below covers a distinct lane in the seed table —
        // adding a row to ICD10_TO_LOINC should not require touching
        // this test, but breaking an existing lane must.
        assertThat(ProblemLoincBindings.bindingFor("I10"))
            .map(LoincCoding::code).contains("85354-9");
        assertThat(ProblemLoincBindings.bindingFor("E11"))
            .map(LoincCoding::code).contains("4548-4");
        assertThat(ProblemLoincBindings.bindingFor("J45"))
            .map(LoincCoding::code).contains("19868-9");
        assertThat(ProblemLoincBindings.bindingFor("D57"))
            .map(LoincCoding::code).contains("4624-3");
        assertThat(ProblemLoincBindings.bindingFor("B54"))
            .map(LoincCoding::code).contains("32700-7");
        assertThat(ProblemLoincBindings.bindingFor("B20"))
            .map(LoincCoding::code).contains("25836-8");
        assertThat(ProblemLoincBindings.bindingFor("N18"))
            .map(LoincCoding::code).contains("33914-3");
    }

    @Test
    void subdivisionPreservesPrefixMatch() {
        // I10.0 should still resolve to the I10 binding — the table
        // is keyed on the 3-char prefix on purpose.
        assertThat(ProblemLoincBindings.bindingFor("I10.0"))
            .map(LoincCoding::code).contains("85354-9");
        assertThat(ProblemLoincBindings.bindingFor("J45.901"))
            .map(LoincCoding::code).contains("19868-9");
        assertThat(ProblemLoincBindings.bindingFor("E11.9"))
            .map(LoincCoding::code).contains("4548-4");
    }

    @Test
    void caseAndWhitespaceTolerated() {
        assertThat(ProblemLoincBindings.bindingFor(" i10 "))
            .map(LoincCoding::code).contains("85354-9");
        assertThat(ProblemLoincBindings.bindingFor("e11"))
            .map(LoincCoding::code).contains("4548-4");
    }

    @Test
    void unknownCodeReturnsEmpty() {
        assertThat(ProblemLoincBindings.bindingFor("Z00")).isEmpty();
        assertThat(ProblemLoincBindings.bindingFor("K35")).isEmpty();
    }

    @Test
    void invalidIcdShapeReturnsEmpty() {
        assertThat(ProblemLoincBindings.bindingFor("not-icd")).isEmpty();
        assertThat(ProblemLoincBindings.bindingFor("123")).isEmpty();
        assertThat(ProblemLoincBindings.bindingFor("")).isEmpty();
        assertThat(ProblemLoincBindings.bindingFor(null)).isEmpty();
    }

    @Test
    void everySeededLoincPassesTheGlobalShapeValidator() {
        // Defence in depth: an entry typo'd into the table that didn't
        // match LOINC's n{1,7}-d shape would silently propagate to wire
        // payloads. This iterates the table and validates each value
        // against the same regex TerminologyCodes uses everywhere else.
        ProblemLoincBindings.ICD10_TO_LOINC.values().forEach(coding -> {
            assertThat(TerminologyCodes.isValidLoinc(coding.code()))
                .as("seeded LOINC %s must pass TerminologyCodes.isValidLoinc",
                    coding.code())
                .isTrue();
            assertThat(coding.display())
                .as("LOINC display for %s must be present", coding.code())
                .isNotNull()
                .isNotBlank();
        });
    }
}
