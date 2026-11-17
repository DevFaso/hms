package com.example.hms.service.emar;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D1392: every status a prescription can have is decided: a new one fails
 * here until someone says whether it still allows a dose.
 */
@DisplayName("D1392: an order's own status decides whether it takes a dose")
class MarOrderStandingTest {

    private static final Set<PrescriptionStatus> WITHDRAWN =
        EnumSet.of(PrescriptionStatus.CANCELLED, PrescriptionStatus.DISCONTINUED);
    private static final Set<PrescriptionStatus> NEVER_SIGNED =
        EnumSet.of(PrescriptionStatus.DRAFT, PrescriptionStatus.PENDING_SIGNATURE);
    /** Say where the medicine is, or that the order is live: the prescriber still stands behind it. */
    private static final Set<PrescriptionStatus> STANDS = EnumSet.of(
        PrescriptionStatus.SIGNED, PrescriptionStatus.TRANSMITTED, PrescriptionStatus.TRANSMISSION_FAILED,
        PrescriptionStatus.PENDING_CLARIFICATION, PrescriptionStatus.DISPENSED, PrescriptionStatus.PARTIALLY_FILLED,
        PrescriptionStatus.PENDING_STOCK, PrescriptionStatus.SENT_TO_PARTNER, PrescriptionStatus.PARTNER_ACCEPTED,
        PrescriptionStatus.PARTNER_REJECTED, PrescriptionStatus.PARTNER_DISPENSED, PrescriptionStatus.PRINTED_FOR_PATIENT);

    private static Prescription order(PrescriptionStatus status) {
        Prescription rx = new Prescription();
        rx.setStatus(status);
        return rx;
    }

    @ParameterizedTest
    @EnumSource(PrescriptionStatus.class)
    void everyStatusIsDecided(PrescriptionStatus status) {
        Prescription rx = order(status);
        if (WITHDRAWN.contains(status)) {
            assertThatThrownBy(() -> MarOrderStanding.requireStands(rx))
                .isInstanceOf(ConflictException.class).hasMessage("nurse.mar.orderWithdrawn");
        } else if (NEVER_SIGNED.contains(status)) {
            assertThatThrownBy(() -> MarOrderStanding.requireStands(rx))
                .isInstanceOf(ConflictException.class).hasMessage("nurse.mar.orderNotSigned");
        } else if (STANDS.contains(status)) {
            assertThatCode(() -> MarOrderStanding.requireStands(rx)).doesNotThrowAnyException();
        } else {
            throw new AssertionError(status + " is new: decide whether it still allows a dose (MarOrderStanding)");
        }
    }

    @Test
    void aRowWhoseOrderIsGoneIsLeftToTheCallersOwnChecks() {
        assertThatCode(() -> MarOrderStanding.requireStands(null)).doesNotThrowAnyException();
    }
}
