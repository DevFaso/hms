package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID MORPHINE_ITEM = UUID.randomUUID();

    private final MedicationCatalogItemRepository catalog = mock(MedicationCatalogItemRepository.class);
    private final ControlledSubstanceGuard guard =
        new ControlledSubstanceGuard(new PrescriptionControlledResolver(catalog));

    /**
     * QA D922 (round-18 merge): morphine picked from a catalogue item marked
     * controlled, with the prescriber's tick left unset.
     */
    private Prescription catalogueControlledWithoutTick(PrescriptionStatus status) {
        MedicationCatalogItem morphine = new MedicationCatalogItem();
        morphine.setControlled(true);
        when(catalog.findByIdAndHospital_Id(MORPHINE_ITEM, HOSPITAL)).thenReturn(Optional.of(morphine));
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL);
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setHospital(hospital);
        rx.setMedicationCatalogItemId(MORPHINE_ITEM);
        rx.setControlledSubstance(false);
        rx.setStatus(status);
        return rx;
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

    @Test
    void dispenseRefusesAControlledSubstanceWithoutTwoFactor() {
        Prescription rx = controlled(PrescriptionStatus.SIGNED);

        assertThatThrownBy(() -> guard.requireDispensable(rx))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("prescription.safeguard.CONTROLLED_SUBSTANCE.dispense");
    }

    @Test
    void dispenseAllowsAControlledSubstanceOnceVerified() {
        Prescription rx = controlled(PrescriptionStatus.SIGNED);
        rx.setTwoFactorVerifiedAt(LocalDateTime.now());

        assertThatCode(() -> guard.requireDispensable(rx)).doesNotThrowAnyException();
    }

    /**
     * The user's rule (QA D922, 2026-10-28): "a drug the catalogue marks as
     * controlled is always treated as controlled, whatever the prescriber
     * ticked". Before the round-18 merge the dispense gate read the tick
     * alone, so a catalogue-controlled order signed without the prescriber's
     * proof was handed over as an ordinary drug.
     */
    @Test
    void dispenseRefusesACatalogueControlledOrderWithoutTwoFactorWhateverTheTick() {
        Prescription rx = catalogueControlledWithoutTick(PrescriptionStatus.SIGNED);

        assertThatThrownBy(() -> guard.requireDispensable(rx))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("prescription.safeguard.CONTROLLED_SUBSTANCE.dispense");
    }

    @Test
    void dispenseAllowsACatalogueControlledOrderOnceThePrescriberVerified() {
        Prescription rx = catalogueControlledWithoutTick(PrescriptionStatus.SIGNED);
        rx.setTwoFactorVerifiedAt(LocalDateTime.now());

        assertThatCode(() -> guard.requireDispensable(rx)).doesNotThrowAnyException();
    }

    @Test
    void aCatalogueControlledOrderCannotReachAnActionableStatusWithoutTwoFactor() {
        Prescription rx = catalogueControlledWithoutTick(PrescriptionStatus.DRAFT);

        assertThatThrownBy(() -> guard.requireSafeguardsFor(rx, PrescriptionStatus.TRANSMITTED))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("prescription.safeguard.CONTROLLED_SUBSTANCE.status");
        assertThatCode(() -> guard.requireSafeguardsFor(rx, PrescriptionStatus.DRAFT))
            .doesNotThrowAnyException();
    }

    @Test
    void dispenseRefusesAnUncosignedPrescriptionThatDeclaredItNeedsOne() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setRequiresCosign(true);

        assertThatThrownBy(() -> guard.requireDispensable(rx))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("prescription.safeguard.COSIGN_REQUIRED.dispense");
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
            .extracting("messageKey").isEqualTo("prescription.safeguard.CONTROLLED_SUBSTANCE.status");
    }

    @Test
    void aControlledSubstanceCannotReachTransmittedWithoutTwoFactor() {
        // TRANSMITTED skips past SIGNED; gating only on SIGNED would leave the
        // obvious way around the check.
        Prescription rx = controlled(PrescriptionStatus.DRAFT);

        assertThatThrownBy(() -> guard.requireSafeguardsFor(rx, PrescriptionStatus.TRANSMITTED))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("prescription.safeguard.CONTROLLED_SUBSTANCE.status");
    }

    @Test
    void theRefusalNamesTheStatusItRefused() {
        Prescription rx = controlled(PrescriptionStatus.DRAFT);

        assertThatThrownBy(() -> guard.requireSafeguardsFor(rx, PrescriptionStatus.SIGNED))
            .isInstanceOf(BusinessException.class)
            .extracting("args").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.ARRAY)
            .containsExactly(PrescriptionStatus.SIGNED.localizedLabel());
    }

    @Test
    void anUncosignedCosignRequiredPrescriptionCannotReachSigned() {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(PrescriptionStatus.DRAFT);
        rx.setRequiresCosign(true);

        assertThatThrownBy(() -> guard.requireSafeguardsFor(rx, PrescriptionStatus.SIGNED))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("prescription.safeguard.COSIGN_REQUIRED.status");
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
