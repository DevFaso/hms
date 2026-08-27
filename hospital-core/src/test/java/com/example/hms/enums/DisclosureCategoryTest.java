package com.example.hms.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DisclosureCategory} — what a patient is shown (Tier 2 item 39).
 *
 * <p>Two things are worth pinning here and they pull in opposite directions.
 * The whitelist must not silently widen, or a future event type reaches
 * patients without anyone deciding it should; and it must not silently
 * narrow relative to the switch, or the counts query returns rows the
 * classifier drops on the floor.
 */
@DisplayName("DisclosureCategory")
class DisclosureCategoryTest {

    @Test
    @DisplayName("PATIENT_ACCESS is an insurance disclosure or a chart open depending on entity type")
    void patientAccessSplitsOnEntityType() {
        // The whole reason classify() takes an entity type. An eligibility
        // check sends identity and coverage to an outside scheme; a doctor
        // opening the chart does not. Both arrive as PATIENT_ACCESS, and
        // labelling them the same tells a patient their insurer read their
        // notes, or hides that it did.
        assertThat(DisclosureCategory.classify(AuditEventType.PATIENT_ACCESS, "EligibilityCheck"))
            .isEqualTo(DisclosureCategory.INSURANCE);
        assertThat(DisclosureCategory.classify(AuditEventType.PATIENT_ACCESS, "PATIENT"))
            .isEqualTo(DisclosureCategory.TREATMENT_ACCESS);
        // Case is not the discriminator — the emitter's constant could be
        // re-cased without changing meaning.
        assertThat(DisclosureCategory.classify(AuditEventType.PATIENT_ACCESS, "eligibilitycheck"))
            .isEqualTo(DisclosureCategory.INSURANCE);
    }

    @Test
    @DisplayName("break-the-glass classifies as emergency access")
    void breakGlassIsEmergencyAccess() {
        // The category this feature exists for. Its audit rows carry
        // entityType=BREAK_GLASS_SESSION, so the classifier must not depend
        // on the entity type saying PATIENT.
        assertThat(DisclosureCategory.classify(
                AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION"))
            .isEqualTo(DisclosureCategory.EMERGENCY_ACCESS);
    }

    @Test
    @DisplayName("the whitelist and the switch agree in both directions")
    void whitelistMatchesSwitch() {
        // Left drift: an accountable type the switch cannot classify would be
        // fetched by the query and then dropped, so a count would be short
        // with no error anywhere.
        Set<AuditEventType> unclassifiable = DisclosureCategory.accountableEventTypes().stream()
            .filter(t -> DisclosureCategory.classify(t, null) == null)
            .collect(Collectors.toSet());
        assertThat(unclassifiable)
            .as("accountable but unclassifiable — the counts query would silently drop these")
            .isEmpty();

        // Right drift: a type the switch classifies but the whitelist omits
        // never reaches a patient, which is the safe direction but still a
        // bug — somebody wrote a category for it expecting it to show.
        Set<AuditEventType> classifiedButNotFetched = Set.of(AuditEventType.values()).stream()
            .filter(t -> DisclosureCategory.classify(t, null) != null)
            .filter(t -> !DisclosureCategory.accountableEventTypes().contains(t))
            .collect(Collectors.toSet());
        assertThat(classifiedButNotFetched)
            .as("classified but never fetched — these would never reach a patient")
            .isEmpty();
    }

    @Test
    @DisplayName("event types nobody classified do not reach patients")
    void unclassifiedTypesAreNotAccountable() {
        // The whitelist is the guard. If this ever fails because somebody
        // added a case to the switch, that is the point: adding a category
        // is a decision to publish something to patients, and it should not
        // be possible to make it by accident.
        assertThat(DisclosureCategory.classify(AuditEventType.LOGIN, null)).isNull();
        assertThat(DisclosureCategory.classify(AuditEventType.STOCK_RECEIPT, null)).isNull();
        assertThat(DisclosureCategory.classify(AuditEventType.ROLE_ASSIGNED, null)).isNull();
        assertThat(DisclosureCategory.classify(null, "PATIENT")).isNull();

        assertThat(DisclosureCategory.accountableEventTypes())
            .doesNotContain(AuditEventType.LOGIN, AuditEventType.PATIENT_CREATE,
                AuditEventType.CONSENT_GRANTED);
    }

    @Test
    @DisplayName("external disclosure means it left the treating team")
    void externalDisclosureIsAboutLeavingTheTeam() {
        assertThat(DisclosureCategory.SHARED_WITH_PROVIDER.isExternalDisclosure()).isTrue();
        assertThat(DisclosureCategory.INSURANCE.isExternalDisclosure()).isTrue();
        assertThat(DisclosureCategory.COPY_RELEASED.isExternalDisclosure()).isTrue();

        // Emergency access is not external — it is the treating team reading
        // the chart without a consent, which is a different complaint and
        // should not be counted under the same heading.
        assertThat(DisclosureCategory.EMERGENCY_ACCESS.isExternalDisclosure()).isFalse();
        assertThat(DisclosureCategory.TREATMENT_ACCESS.isExternalDisclosure()).isFalse();
        assertThat(DisclosureCategory.IDENTITY_CHANGE.isExternalDisclosure()).isFalse();
    }
}
