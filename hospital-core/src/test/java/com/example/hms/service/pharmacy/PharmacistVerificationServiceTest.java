package com.example.hms.service.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.RoleValidator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * The pharmacist-verification gate (Tier 2 item 33).
 *
 * <p>Fixed clock: a ceremony that stamps the wall clock is a test that
 * cannot assert what it stamped.
 */
@ExtendWith(MockitoExtension.class)
class PharmacistVerificationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 22, 30);

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleValidator roleValidator;

    private PharmacistVerificationService service;

    private UUID hospitalId;
    private UUID pharmacistId;
    private UUID prescriberUserId;
    private Hospital hospital;
    private Prescription rx;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new PharmacistVerificationService(
            prescriptionRepository, userRepository, roleValidator, clock);

        hospitalId = UUID.randomUUID();
        pharmacistId = UUID.randomUUID();
        prescriberUserId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        User prescriberUser = new User();
        prescriberUser.setId(prescriberUserId);
        Staff prescriber = new Staff();
        prescriber.setId(UUID.randomUUID());
        prescriber.setUser(prescriberUser);

        rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setHospital(hospital);
        rx.setStaff(prescriber);
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setMedicationName("Morphine");
        rx.setControlledSubstance(true);
    }

    private void callerIsPharmacist() {
        User pharmacist = new User();
        pharmacist.setId(pharmacistId);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(pharmacistId);
        when(userRepository.findById(pharmacistId)).thenReturn(Optional.of(pharmacist));
        when(prescriptionRepository.findById(rx.getId())).thenReturn(Optional.of(rx));
        when(prescriptionRepository.save(any(Prescription.class))).thenAnswer(i -> i.getArgument(0));
    }

    // ── Scope: what the gate covers ─────────────────────────────────────
    //
    // Deliberately narrow. A universal gate would have nothing to fail open
    // against, because no dispensary staffing is modelled.

    @Test
    void aControlledSubstanceNeedsVerification() {
        assertThat(service.requiresVerification(rx)).isTrue();
    }

    @Test
    void aCosignRequiredPrescriptionNeedsVerification() {
        rx.setControlledSubstance(false);
        rx.setRequiresCosign(true);

        assertThat(service.requiresVerification(rx)).isTrue();
    }

    @Test
    void anOrdinaryPrescriptionDoesNotNeedVerification() {
        // The whole point of the scoped decision: routine meds keep flowing.
        rx.setControlledSubstance(false);
        rx.setRequiresCosign(false);

        assertThat(service.requiresVerification(rx)).isFalse();
        assertThat(service.blocksAdministration(rx)).isFalse();
    }

    @Test
    void anUnverifiedHighRiskPrescriptionBlocksAdministration() {
        assertThat(service.blocksAdministration(rx)).isTrue();
    }

    @Test
    void averifiedHighRiskPrescriptionDoesNotBlock() {
        rx.setPharmacistVerifiedAt(NOW.minusHours(1));

        assertThat(service.blocksAdministration(rx)).isFalse();
    }

    // ── The ceremony ────────────────────────────────────────────────────

    @Test
    void verifyingStampsServerIdentityAndServerClock() {
        callerIsPharmacist();

        Prescription saved = service.verify(rx.getId(), "  Dose checked against weight  ");

        assertThat(saved.getPharmacistVerifiedAt()).isEqualTo(NOW);
        assertThat(saved.getPharmacistVerifiedBy().getId()).isEqualTo(pharmacistId);
        assertThat(saved.getPharmacistVerificationNote()).isEqualTo("Dose checked against weight");
    }

    @Test
    void aBlankNoteIsStoredAsNullRatherThanWhitespace() {
        callerIsPharmacist();

        Prescription saved = service.verify(rx.getId(), "   ");

        assertThat(saved.getPharmacistVerificationNote()).isNull();
    }

    @Test
    void thePrescriberCannotVerifyTheirOwnPrescription() {
        // A second pair of eyes that belongs to the same person is not a
        // second pair of eyes.
        User self = new User();
        self.setId(prescriberUserId);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(prescriberUserId);
        when(userRepository.findById(prescriberUserId)).thenReturn(Optional.of(self));
        when(prescriptionRepository.findById(rx.getId())).thenReturn(Optional.of(rx));
        UUID id = rx.getId();

        assertThatThrownBy(() -> service.verify(id, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be verified by the clinician who prescribed it");
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void anUnsignedPrescriptionCannotBeVerified() {
        // It would be verifying something the prescriber can still freely
        // rewrite — and the invalidation rule would clear it the moment
        // they did.
        rx.setStatus(PrescriptionStatus.DRAFT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(rx.getId())).thenReturn(Optional.of(rx));
        UUID id = rx.getId();

        assertThatThrownBy(() -> service.verify(id, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only a signed prescription");
    }

    @Test
    void doubleVerificationIsRefused() {
        rx.setPharmacistVerifiedAt(NOW.minusHours(2));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(rx.getId())).thenReturn(Optional.of(rx));
        UUID id = rx.getId();

        assertThatThrownBy(() -> service.verify(id, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been verified");
    }

    @Test
    void anotherHospitalsPrescriptionReadsAsNotFound() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        rx.setHospital(other);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(rx.getId())).thenReturn(Optional.of(rx));
        UUID id = rx.getId();

        assertThatThrownBy(() -> service.verify(id, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anUnresolvableCallerIsRefused() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(null);
        when(prescriptionRepository.findById(rx.getId())).thenReturn(Optional.of(rx));
        UUID id = rx.getId();

        assertThatThrownBy(() -> service.verify(id, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ── Invalidation: the load-bearing rule ─────────────────────────────
    //
    // updatePrescription has NO status guard, so a SIGNED prescription's
    // medication and dosage stay mutable. A stamp that survived an edit
    // would assert a check of a drug the pharmacist never saw — a false
    // assurance, which is worse than the absent one we started with.

    @Test
    void editingAVerifiedPrescriptionClearsTheVerification() {
        User pharmacist = new User();
        pharmacist.setId(pharmacistId);
        rx.setPharmacistVerifiedAt(NOW.minusHours(3));
        rx.setPharmacistVerifiedBy(pharmacist);
        rx.setPharmacistVerificationNote("Checked");

        service.invalidateOnChange(rx);

        assertThat(rx.getPharmacistVerifiedAt()).isNull();
        assertThat(rx.getPharmacistVerifiedBy()).isNull();
        assertThat(rx.getPharmacistVerificationNote()).isNull();
    }

    @Test
    void anEditedHighRiskPrescriptionBlocksAdministrationAgain() {
        // The whole point: after an edit the gate closes again until
        // somebody re-checks the NEW drug and dose.
        rx.setPharmacistVerifiedAt(NOW.minusHours(3));
        assertThat(service.blocksAdministration(rx)).isFalse();

        rx.setMedicationName("Hydromorphone");
        service.invalidateOnChange(rx);

        assertThat(service.blocksAdministration(rx)).isTrue();
    }

    @Test
    void invalidatingAnUnverifiedPrescriptionIsAHarmlessNoOp() {
        service.invalidateOnChange(rx);

        assertThat(rx.getPharmacistVerifiedAt()).isNull();
    }

    @Test
    void invalidatingNullIsTolerated() {
        service.invalidateOnChange(null);
    }
}
