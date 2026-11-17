package com.example.hms.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1187 / D1152 / D1186 (round 22): the refusals this round keyed read in
 * the reader's language - French and Spanish differ from English, and the
 * placeholders are filled - exactly as the QA scripts saw them on the FR and
 * ES reception screens ("This recall is already CANCELLED." on both).
 */
@DisplayName("Round-22 refusals are French and Spanish on French and Spanish screens")
class RoundTwentyTwoRefusalsLocalizedTest {

    private static final MessageSource BUNDLES = TestMessageSources.bundles();

    @ParameterizedTest
    @ValueSource(strings = {
        "recall.patient.notRegistered", "recall.close.alreadyClosed", "recall.close.alreadyCancelled",
        "recall.cancel.notOpen", "recall.link.notOpen", "recall.link.otherPatient",
        "waitlist.close.alreadyClosed", "instrumentOutbox.retry.notInError", "instrumentOutbox.status.unknown",
        "patient.refill.cancel.notPending", "proxyGrant.alreadyRevoked", "advanceDirective.revoke.alreadyRevoked",
        "patient.r22.diagnosisAlreadyRemoved", "transfusion.unit.alreadyDiscarded", "assignment.resend.alreadyVerified",
        "phoneVerification.code.invalid", "phoneVerification.code.expired", "registration.minor.guardianRequired",
        "guarantor.alreadyInactive", "patient.photo.tooLarge", "eligibility.provider.none",
        "program.enrollment.visitBeforeLast", "pharmacy.clarification.resolve.staffProfileRequired",
        "appointment.staffAssignment.missing"})
    void keyIsTranslated(String key) {
        Object[] args = {"X-1"};
        String en = BUNDLES.getMessage(key, args, Locale.ENGLISH);
        String fr = BUNDLES.getMessage(key, args, Locale.FRENCH);
        String es = BUNDLES.getMessage(key, args, Locale.forLanguageTag("es"));
        assertThat(en).isNotBlank().doesNotContain(key);
        assertThat(fr).isNotEqualTo(en).doesNotContain("{0}");
        assertThat(es).isNotEqualTo(en).doesNotContain("{0}");
        if (en.contains("X-1")) {
            assertThat(fr).contains("X-1");
            assertThat(es).contains("X-1");
        }
    }
}
