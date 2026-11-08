package com.example.hms.enums;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1515: every status answers whether its order is in force, so a new one has to be decided here. A
 * draft or an order waiting for its signature is a proposal; a withdrawn or rejected order is over.
 */
class PrescriptionStatusInForceTest {

    private static final Set<PrescriptionStatus> NOT_IN_FORCE = EnumSet.of(PrescriptionStatus.DRAFT,
        PrescriptionStatus.PENDING_SIGNATURE, PrescriptionStatus.CANCELLED, PrescriptionStatus.DISCONTINUED,
        PrescriptionStatus.PARTNER_REJECTED);

    @Test
    void everyStatusIsDecided() {
        for (PrescriptionStatus status : PrescriptionStatus.values()) {
            assertThat(status.isInForce()).as(status.name()).isEqualTo(!NOT_IN_FORCE.contains(status));
        }
        // A new status lands in the "in force" default: this count makes its author decide.
        assertThat(PrescriptionStatus.values()).hasSize(16);
    }

    @Test
    void aWithdrawnOrderAndAnUnsignedOneAreNeverInForce() {
        for (PrescriptionStatus status : PrescriptionStatus.values()) {
            if (status.isWithdrawn() || !status.isRefillable()) {
                assertThat(status.isInForce()).as(status.name()).isFalse();
            }
        }
    }
}
