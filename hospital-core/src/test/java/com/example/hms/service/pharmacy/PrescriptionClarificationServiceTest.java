package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G5 — the clarification ceremony: in from a dispensable status with a
 * reason, out to SIGNED by a doctor at the hospital.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PrescriptionClarificationService")
class PrescriptionClarificationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 10, 30);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private PharmacyServiceSupport support;
    @Mock private PrescriberPharmacyNotifier prescriberNotifier;

    private PrescriptionClarificationService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID prescriptionId = UUID.randomUUID();
    private final UUID pharmacistId = UUID.randomUUID();
    private final UUID doctorUserId = UUID.randomUUID();

    private Hospital hospital;
    private Prescription prescription;

    @BeforeEach
    void setUp() {
        service = new PrescriptionClarificationService(prescriptionRepository, staffRepository,
                roleValidator, support, prescriberNotifier, CLOCK);
        hospital = new Hospital();
        hospital.setId(hospitalId);
        prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setHospital(hospital);
        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescription.setMedicationName("Amoxicilline 500 mg");
    }

    private Staff doctorAt(Hospital at) {
        User user = new User();
        user.setId(doctorUserId);
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);
        staff.setHospital(at);
        return staff;
    }

    @Nested
    @DisplayName("requestClarification")
    class Request {

        @Test
        @DisplayName("moves a SIGNED order to PENDING_CLARIFICATION with the reason, audits without it, and notifies the prescriber")
        void happyPath() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(pharmacistId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            service.requestClarification(prescriptionId, "  Dose au-dessus du plafond rénal ");

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PENDING_CLARIFICATION);
            assertThat(prescription.getClarificationReason()).isEqualTo("Dose au-dessus du plafond rénal");
            assertThat(prescription.getClarificationRequestedAt()).isEqualTo(NOW);
            assertThat(prescription.getClarificationRequestedByUserId()).isEqualTo(pharmacistId);
            assertThat(prescription.getClarificationResolvedAt()).isNull();
            verify(prescriptionRepository).save(prescription);

            ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
            verify(support).logAudit(eq(AuditEventType.PRESCRIPTION_CLARIFICATION_REQUESTED),
                    description.capture(), eq(prescriptionId.toString()), eq("PRESCRIPTION"));
            assertThat(description.getValue()).doesNotContain("rénal");

            verify(prescriberNotifier).notifyPrescriber(prescription, PrescriptionStatus.PENDING_CLARIFICATION);
        }

        @Test
        @DisplayName("a second request clears the previous answer")
        void clearsPreviousResolution() {
            prescription.setStatus(PrescriptionStatus.PARTIALLY_FILLED);
            prescription.setClarificationResponse("old answer");
            prescription.setClarificationResolvedAt(NOW.minusDays(1));
            prescription.setClarificationResolvedByUserId(doctorUserId);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(pharmacistId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            service.requestClarification(prescriptionId, "new question");

            assertThat(prescription.getClarificationResponse()).isNull();
            assertThat(prescription.getClarificationResolvedAt()).isNull();
            assertThat(prescription.getClarificationResolvedByUserId()).isNull();
        }

        @Test
        @DisplayName("refuses an order that is not awaiting a fill")
        void refusesNonDispensableStatus() {
            prescription.setStatus(PrescriptionStatus.DISPENSED);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.requestClarification(prescriptionId, "why"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("DISPENSED");
            verify(prescriptionRepository, never()).save(any());
            verify(prescriberNotifier, never()).notifyPrescriber(any(), any());
        }

        @Test
        @DisplayName("refuses a blank reason")
        void refusesBlankReason() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.requestClarification(prescriptionId, "   "))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("reason");
        }

        @Test
        @DisplayName("a prescription at another hospital is 404, not 403")
        void crossTenantIs404() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.requestClarification(prescriptionId, "why"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("resolveClarification")
    class Resolve {

        @BeforeEach
        void pending() {
            prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);
            prescription.setClarificationReason("why");
        }

        @Test
        @DisplayName("a doctor at the hospital returns the order to SIGNED with the answer on record")
        void happyPath() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(doctorUserId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(doctorUserId))
                    .thenReturn(Optional.of(doctorAt(hospital)));

            service.resolveClarification(prescriptionId, " Dose confirmée, clairance vérifiée ");

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
            assertThat(prescription.getClarificationResponse()).isEqualTo("Dose confirmée, clairance vérifiée");
            assertThat(prescription.getClarificationResolvedAt()).isEqualTo(NOW);
            assertThat(prescription.getClarificationResolvedByUserId()).isEqualTo(doctorUserId);
            assertThat(prescription.getClarificationReason()).isEqualTo("why");
            verify(prescriptionRepository).save(prescription);
            verify(support).logAudit(eq(AuditEventType.PRESCRIPTION_CLARIFICATION_RESOLVED),
                    anyString(), eq(prescriptionId.toString()), eq("PRESCRIPTION"));
        }

        @Test
        @DisplayName("the answer is optional when the order itself was edited")
        void answerIsOptional() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(doctorUserId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(doctorUserId))
                    .thenReturn(Optional.of(doctorAt(hospital)));

            service.resolveClarification(prescriptionId, null);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
            assertThat(prescription.getClarificationResponse()).isNull();
            assertThat(prescription.getClarificationResolvedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a doctor whose staff profile is at another hospital is refused (403)")
        void doctorElsewhereIsRefused() {
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(doctorUserId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(doctorUserId))
                    .thenReturn(Optional.of(doctorAt(other)));

            assertThatThrownBy(() -> service.resolveClarification(prescriptionId, "ok"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PENDING_CLARIFICATION);
        }

        @Test
        @DisplayName("a caller with no staff profile is refused (403)")
        void noStaffProfileIsRefused() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(doctorUserId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(doctorUserId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveClarification(prescriptionId, "ok"))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("an order that is not awaiting clarification cannot be resolved")
        void refusesWhenNotPending() {
            prescription.setStatus(PrescriptionStatus.SIGNED);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.resolveClarification(prescriptionId, "ok"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not awaiting clarification");
        }

        @Test
        @DisplayName("a prescription at another hospital is 404, not 403")
        void crossTenantIs404() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.resolveClarification(prescriptionId, "ok"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
