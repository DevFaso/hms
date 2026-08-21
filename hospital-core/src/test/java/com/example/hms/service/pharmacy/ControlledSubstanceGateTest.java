package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The controlled-substance safeguards, exercised directly (P2 #15).
 *
 * <p>{@code controlledSubstance}, {@code twoFactorVerifiedAt},
 * {@code requiresCosign} and {@code cosignedAt} have existed on Prescription
 * since the pharmacy module shipped, and NOTHING read them except display
 * mappers. A prescriber could flag a schedule-II opioid as controlled, declare
 * it needs a co-sign, complete neither step, and both the prescribe and dispense
 * paths would treat it exactly like paracetamol. The columns described a control
 * that did not exist.
 *
 * <p>The gate is invoked reflectively rather than through the full service: both
 * copies are private helpers on services whose happy path needs a dozen
 * collaborators, and what needs pinning is the decision itself — which
 * combinations pass and which are refused. Wiring a dozen mocks would test the
 * wiring, not the rule.
 */
class ControlledSubstanceGateTest {

    private static void invokeGate(Object target, Prescription prescription) throws Exception {
        Method gate = target.getClass()
            .getDeclaredMethod("enforceControlledSubstanceGates", Prescription.class);
        gate.setAccessible(true);
        try {
            gate.invoke(target, prescription);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        }
    }

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

    private static Object dispenseService() {
        return org.mockito.Mockito.mock(DispenseServiceImpl.class,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
    }

    @Test
    void dispenseRefusesAControlledSubstanceWithoutTwoFactor() {
        Prescription rx = controlled(PrescriptionStatus.SIGNED);

        assertThatThrownBy(() -> invokeGate(dispenseService(), rx))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CONTROLLED_SUBSTANCE");
    }

    @Test
    void dispenseAllowsAControlledSubstanceOnceVerified() {
        Prescription rx = controlled(PrescriptionStatus.SIGNED);
        rx.setTwoFactorVerifiedAt(LocalDateTime.now());

        assertThatCode(() -> invokeGate(dispenseService(), rx)).doesNotThrowAnyException();
    }

    @Test
    void dispenseRefusesAnUncosignedPrescriptionThatDeclaredItNeedsOne() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setRequiresCosign(true);

        assertThatThrownBy(() -> invokeGate(dispenseService(), rx))
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

        assertThatCode(() -> invokeGate(dispenseService(), rx)).doesNotThrowAnyException();
    }

    @Test
    void dispenseLeavesOrdinaryPrescriptionsAlone() {
        // Nothing changes for the 99% case, and nothing retroactively blocks
        // existing prescriptions that were never flagged.
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(PrescriptionStatus.SIGNED);

        assertThatCode(() -> invokeGate(dispenseService(), rx)).doesNotThrowAnyException();
    }

    // ── Prescribe gate: keyed on status, not on save ──────────────────────

    private static Object prescriptionService() {
        return org.mockito.Mockito.mock(com.example.hms.service.PrescriptionServiceImpl.class,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
    }

    @Test
    void aControlledSubstanceMayStillBeDrafted() {
        // A paper prescription can be written before it is signed. What it
        // cannot do is reach a state that authorises anybody to act on it.
        Prescription draft = controlled(PrescriptionStatus.DRAFT);

        assertThatCode(() -> invokeGate(prescriptionService(), draft)).doesNotThrowAnyException();

        Prescription pending = controlled(PrescriptionStatus.PENDING_SIGNATURE);
        assertThatCode(() -> invokeGate(prescriptionService(), pending)).doesNotThrowAnyException();
    }

    @Test
    void aControlledSubstanceCannotReachSignedWithoutTwoFactor() {
        Prescription rx = controlled(PrescriptionStatus.SIGNED);

        assertThatThrownBy(() -> invokeGate(prescriptionService(), rx))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CONTROLLED_SUBSTANCE");
    }

    @Test
    void aControlledSubstanceCannotReachTransmittedWithoutTwoFactor() {
        // TRANSMITTED skips past SIGNED; gating only on SIGNED would leave the
        // obvious way around the check.
        Prescription rx = controlled(PrescriptionStatus.TRANSMITTED);

        assertThatThrownBy(() -> invokeGate(prescriptionService(), rx))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CONTROLLED_SUBSTANCE");
    }

    @Test
    void theRefusalNamesTheStatusItRefused() {
        Prescription rx = controlled(PrescriptionStatus.SIGNED);

        assertThatThrownBy(() -> invokeGate(prescriptionService(), rx))
            .hasMessageContaining("SIGNED");
    }

    @Test
    void bothGatesExistSoNeitherPathIsTheOnlyOne() {
        // Status can be set by paths that never reach PrescriptionServiceImpl —
        // refill approval writes SIGNED directly, for one. The dispense gate is
        // what makes that safe, so both must stay.
        assertThat(java.util.Arrays.stream(DispenseServiceImpl.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("enforceControlledSubstanceGates"))).isTrue();
        assertThat(java.util.Arrays.stream(
                com.example.hms.service.PrescriptionServiceImpl.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("enforceControlledSubstanceGates"))).isTrue();
    }
}
