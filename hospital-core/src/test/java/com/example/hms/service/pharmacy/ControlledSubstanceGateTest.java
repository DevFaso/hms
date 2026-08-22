package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The controlled-substance safeguards, exercised directly (P2 #15).
 *
 * <p>{@code controlledSubstance}, {@code twoFactorVerifiedAt},
 * {@code requiresCosign} and {@code cosignedAt} existed on Prescription since
 * the pharmacy module shipped, and NOTHING read them except display mappers.
 * PRs #454/#455 added the gates — as two private copies on parallel branches,
 * which the 2026-08-21 reassessment found already drifting. The rule now lives
 * once, in {@link ControlledSubstanceGuard}, and this test pins it there
 * instead of reflecting into private helpers that no longer exist.
 */
class ControlledSubstanceGateTest {

    private final ControlledSubstanceGuard guard = new ControlledSubstanceGuard();

    private static Prescription controlled(PrescriptionStatus status) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(status);
        rx.setControlledSubstance(true);
        return rx;
    }

    // ── Dispense gate: the irreversible step ──────────────────────────────
    // A wrongly-signed prescription can be cancelled; medication handed to a
    // patient cannot be recalled.

    @Test
    void dispenseRefusesAControlledSubstanceWithoutTwoFactor() {
        Prescription rx = controlled(PrescriptionStatus.SIGNED);

        assertThatThrownBy(() -> guard.requireDispensable(rx))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CONTROLLED_SUBSTANCE");
    }

    @Test
    void dispenseAllowsAControlledSubstanceOnceVerified() {
        Prescription rx = controlled(PrescriptionStatus.SIGNED);
        rx.setTwoFactorVerifiedAt(LocalDateTime.now());

        assertThatCode(() -> guard.requireDispensable(rx)).doesNotThrowAnyException();
    }

    @Test
    void dispenseRefusesAnUncosignedPrescriptionThatDeclaredItNeedsOne() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setRequiresCosign(true);

        assertThatThrownBy(() -> guard.requireDispensable(rx))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("COSIGN_REQUIRED");
    }

    @Test
    void dispenseAllowsACosignedPrescription() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setRequiresCosign(true);
        rx.setCosignedAt(LocalDateTime.now());
        Staff cosigner = Staff.builder().build();
        cosigner.setId(UUID.randomUUID());
        rx.setCosignedBy(cosigner);

        assertThatCode(() -> guard.requireDispensable(rx)).doesNotThrowAnyException();
    }

    @Test
    void dispenseLeavesOrdinaryPrescriptionsAlone() {
        // Nothing changes for the 99% case, and nothing retroactively blocks
        // existing prescriptions that were never flagged.
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(PrescriptionStatus.SIGNED);

        assertThatCode(() -> guard.requireDispensable(rx)).doesNotThrowAnyException();
    }

    // ── Prescribe gate: keyed on the TARGET status, not on save ───────────

    @Test
    void aControlledSubstanceMayStillBeDrafted() {
        // A paper prescription can be written before it is signed. What it
        // cannot do is reach a state that authorises anybody to act on it.
        Prescription draft = controlled(PrescriptionStatus.DRAFT);
        assertThatCode(() -> guard.requireSafeguardsFor(draft, PrescriptionStatus.DRAFT))
            .doesNotThrowAnyException();

        Prescription pending = controlled(PrescriptionStatus.PENDING_SIGNATURE);
        assertThatCode(() -> guard.requireSafeguardsFor(pending, PrescriptionStatus.PENDING_SIGNATURE))
            .doesNotThrowAnyException();
    }

    @Test
    void aControlledSubstanceCannotReachSignedWithoutTwoFactor() {
        Prescription rx = controlled(PrescriptionStatus.DRAFT);

        assertThatThrownBy(() -> guard.requireSafeguardsFor(rx, PrescriptionStatus.SIGNED))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CONTROLLED_SUBSTANCE");
    }

    @Test
    void aControlledSubstanceCannotReachTransmittedWithoutTwoFactor() {
        // TRANSMITTED skips past SIGNED; gating only on SIGNED would leave the
        // obvious way around the check.
        Prescription rx = controlled(PrescriptionStatus.DRAFT);

        assertThatThrownBy(() -> guard.requireSafeguardsFor(rx, PrescriptionStatus.TRANSMITTED))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CONTROLLED_SUBSTANCE");
    }

    @Test
    void theRefusalNamesTheStatusItRefused() {
        Prescription rx = controlled(PrescriptionStatus.DRAFT);

        assertThatThrownBy(() -> guard.requireSafeguardsFor(rx, PrescriptionStatus.SIGNED))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("SIGNED");
    }

    @Test
    void anUncosignedCosignRequiredPrescriptionCannotReachSigned() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(PrescriptionStatus.DRAFT);
        rx.setRequiresCosign(true);

        assertThatThrownBy(() -> guard.requireSafeguardsFor(rx, PrescriptionStatus.SIGNED))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("COSIGN_REQUIRED");
    }

    @Test
    void aCosignedPrescriptionMayReachSigned() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(PrescriptionStatus.DRAFT);
        rx.setRequiresCosign(true);
        rx.setCosignedAt(LocalDateTime.now());
        Staff cosigner = Staff.builder().build();
        cosigner.setId(UUID.randomUUID());
        rx.setCosignedBy(cosigner);

        assertThatCode(() -> guard.requireSafeguardsFor(rx, PrescriptionStatus.SIGNED))
            .doesNotThrowAnyException();
    }
}
