package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.CareTeamDTO;
import com.example.hms.payload.dto.portal.HealthSummaryDTO;
import com.example.hms.payload.dto.portal.PatientProfileDTO;
import com.example.hms.payload.dto.portal.PatientProfileUpdateDTO;
import com.example.hms.payload.dto.portal.PortalConsentRequestDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.BillingInvoiceService;
import com.example.hms.service.ConsultationService;
import com.example.hms.service.DischargeSummaryService;
import com.example.hms.service.EncounterService;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.service.ImmunizationService;
import com.example.hms.service.PatientConsentService;
import com.example.hms.service.PatientLabResultService;
import com.example.hms.service.PatientMedicationService;
import com.example.hms.service.PatientPrimaryCareService;
import com.example.hms.service.PatientVitalSignService;
import com.example.hms.service.PrescriptionService;
import com.example.hms.service.TreatmentPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Phase 1 patient portal service methods.
 * Tests cover: profile read/update, health summary, lab results, medications,
 * prescriptions, vitals, encounters, appointments, invoices, consents,
 * immunizations, consultations, treatment plans, referrals,
 * care team, access log, after-visit summaries, and IDOR rejection paths
 * for grantMyConsent / revokeMyConsent.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class PatientPortalServiceImplPhase1Test {

    @Mock private PatientRepository patientRepository;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private PatientLabResultService labResultService;
    @Mock private PatientMedicationService medicationService;
    @Mock private PatientVitalSignService vitalSignService;
    @Mock private PatientConsentService consentService;
    @Mock private ImmunizationService immunizationService;
    @Mock private BillingInvoiceService billingInvoiceService;
    @Mock private EncounterService encounterService;
    @Mock private PrescriptionService prescriptionService;
    @Mock private AppointmentService appointmentService;
    @Mock private ConsultationService consultationService;
    @Mock private TreatmentPlanService treatmentPlanService;
    @Mock private GeneralReferralService referralService;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private AppointmentMapper appointmentMapper;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private RefillRequestRepository refillRequestRepository;
    @Mock private DischargeSummaryService dischargeSummaryService;
    @Mock private PatientPrimaryCareService primaryCareService;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;

    @InjectMocks
    private PatientPortalServiceImpl service;

    @Mock private Authentication auth;

    private UUID userId;
    private UUID patientId;
    private Patient patient;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        user = new User();
        user.setId(userId);
        user.setUsername("patient.john");

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("John");
        patient.setLastName("Smith");
        patient.setAllergies("Penicillin,Aspirin");
        patient.setChronicConditions("Hypertension,Diabetes");
        patient.setUser(user);
    }

    /** Common stub: both resolvePatientId() and findPatient() succeed. */
    private void stubPatientResolution() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
    }

    /** Common stub: userId resolves but no patient record exists. */
    private void stubPatientNotFound() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.empty());
    }

    /** Common stub: auth cannot be resolved to a userId. */
    private void stubAuthNotResolvable() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.empty());
    }

    // ══════════════════════════════════════════════════════════════════════
    // resolvePatientId
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolvePatientId")
    class ResolvePatientId {

        @Test
        @DisplayName("should return patientId when user and patient both exist")
        void resolvePatientId_success() {
            stubPatientResolution();
            UUID result = service.resolvePatientId(auth);
            assertThat(result).isEqualTo(patientId);
        }

        @Test
        @DisplayName("should throw BusinessException when userId cannot be resolved")
        void resolvePatientId_noUser_throwsBusinessException() {
            stubAuthNotResolvable();
            assertThatThrownBy(() -> service.resolvePatientId(auth))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Unable to resolve user");
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when no patient linked to user")
        void resolvePatientId_noPatient_throwsResourceNotFoundException() {
            stubPatientNotFound();
            assertThatThrownBy(() -> service.resolvePatientId(auth))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("No patient record");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Profile
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyProfile")
    class GetMyProfile {

        @Test
        @DisplayName("should return PatientProfileDTO with correct fields")
        void getMyProfile_success() {
            patient.setEmail("john@example.com");
            patient.setCity("Paris");
            stubPatientResolution();

            PatientProfileDTO result = service.getMyProfile(auth);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(patientId);
            assertThat(result.getFirstName()).isEqualTo("John");
            assertThat(result.getLastName()).isEqualTo("Smith");
            assertThat(result.getEmail()).isEqualTo("john@example.com");
            assertThat(result.getCity()).isEqualTo("Paris");
            assertThat(result.getUsername()).isEqualTo("patient.john");
        }

        @Test
        @DisplayName("should propagate ResourceNotFoundException when patient not found")
        void getMyProfile_noPatient_throws() {
            stubPatientNotFound();
            assertThatThrownBy(() -> service.getMyProfile(auth))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("updateMyProfile")
    class UpdateMyProfile {

        @Test
        @DisplayName("should update only non-null fields and save")
        void updateMyProfile_updatesNonNullFields() {
            stubPatientResolution();
            when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

            PatientProfileUpdateDTO dto = PatientProfileUpdateDTO.builder()
                    .phoneNumberPrimary("+33600000001")
                    .email("updated@example.com")
                    .city("Lyon")
                    .emergencyContactName("Marie Dupont")
                    .build();

            PatientProfileDTO result = service.updateMyProfile(auth, dto);

            assertThat(result.getPhoneNumberPrimary()).isEqualTo("+33600000001");
            assertThat(result.getEmail()).isEqualTo("updated@example.com");
            assertThat(result.getCity()).isEqualTo("Lyon");
            assertThat(result.getEmergencyContactName()).isEqualTo("Marie Dupont");
            verify(patientRepository).save(any(Patient.class));
        }

        @Test
        @DisplayName("should skip null fields and not overwrite existing values")
        void updateMyProfile_nullFieldsSkipped() {
            patient.setEmail("original@example.com");
            patient.setCity("Bordeaux");
            stubPatientResolution();
            when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

            // Only updating phone — email and city should stay
            PatientProfileUpdateDTO dto = PatientProfileUpdateDTO.builder()
                    .phoneNumberPrimary("+33600000099")
                    .build();

            PatientProfileDTO result = service.updateMyProfile(auth, dto);

            assertThat(result.getEmail()).isEqualTo("original@example.com");
            assertThat(result.getCity()).isEqualTo("Bordeaux");
            assertThat(result.getPhoneNumberPrimary()).isEqualTo("+33600000099");
        }

        @Test
        @DisplayName("should update all allowed fields when all provided")
        void updateMyProfile_allFields() {
            stubPatientResolution();
            when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

            PatientProfileUpdateDTO dto = PatientProfileUpdateDTO.builder()
                    .phoneNumberPrimary("+1111111111")
                    .phoneNumberSecondary("+2222222222")
                    .email("all@example.com")
                    .addressLine1("1 Rue de la Paix")
                    .addressLine2("Apt 3")
                    .city("Nice")
                    .state("PACA")
                    .zipCode("06000")
                    .country("France")
                    .emergencyContactName("Alice")
                    .emergencyContactPhone("+3333333333")
                    .emergencyContactRelationship("Sister")
                    .preferredPharmacy("Pharmacie Centrale")
                    .build();

            PatientProfileDTO result = service.updateMyProfile(auth, dto);

            assertThat(result.getPhoneNumberPrimary()).isEqualTo("+1111111111");
            assertThat(result.getPhoneNumberSecondary()).isEqualTo("+2222222222");
            assertThat(result.getEmail()).isEqualTo("all@example.com");
            assertThat(result.getAddressLine1()).isEqualTo("1 Rue de la Paix");
            assertThat(result.getAddressLine2()).isEqualTo("Apt 3");
            assertThat(result.getCity()).isEqualTo("Nice");
            assertThat(result.getState()).isEqualTo("PACA");
            assertThat(result.getZipCode()).isEqualTo("06000");
            assertThat(result.getCountry()).isEqualTo("France");
            assertThat(result.getEmergencyContactName()).isEqualTo("Alice");
            assertThat(result.getEmergencyContactPhone()).isEqualTo("+3333333333");
            assertThat(result.getEmergencyContactRelationship()).isEqualTo("Sister");
            assertThat(result.getPreferredPharmacy()).isEqualTo("Pharmacie Centrale");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Health Summary
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getHealthSummary")
    class GetHealthSummary {

        @Test
        @DisplayName("should return aggregated health summary")
        void getHealthSummary_success() {
            stubPatientResolution();

            when(labResultService.getLabResultsForPatient(eq(patientId), isNull(), eq(5)))
                    .thenReturn(List.of(new PatientLabResultResponseDTO()));
            when(medicationService.getMedicationsForPatient(eq(patientId), isNull(), eq(10)))
                    .thenReturn(List.of(new PatientMedicationResponseDTO()));
            when(vitalSignService.getRecentVitals(eq(patientId), isNull(), eq(5)))
                    .thenReturn(List.of(new PatientVitalSignResponseDTO()));
            when(immunizationService.getImmunizationsByPatientId(patientId))
                    .thenReturn(List.of(new ImmunizationResponseDTO()));

            HealthSummaryDTO result = service.getHealthSummary(auth, Locale.ENGLISH);

            assertThat(result).isNotNull();
            assertThat(result.getProfile()).isNotNull();
            assertThat(result.getProfile().getId()).isEqualTo(patientId);
            assertThat(result.getRecentLabResults()).hasSize(1);
            assertThat(result.getCurrentMedications()).hasSize(1);
            assertThat(result.getRecentVitals()).hasSize(1);
            assertThat(result.getImmunizations()).hasSize(1);
            assertThat(result.getAllergies()).containsExactly("Penicillin", "Aspirin");
            assertThat(result.getChronicConditions()).containsExactly("Hypertension", "Diabetes");
        }

        @Test
        @DisplayName("should return empty lists when sub-services throw (partial availability)")
        void getHealthSummary_subServiceFailure_returnsEmptyLists() {
            stubPatientResolution();

            when(labResultService.getLabResultsForPatient(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("lab service down"));
            when(medicationService.getMedicationsForPatient(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("med service down"));
            when(vitalSignService.getRecentVitals(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("vital service down"));
            when(immunizationService.getImmunizationsByPatientId(any()))
                    .thenThrow(new RuntimeException("immunization service down"));

            HealthSummaryDTO result = service.getHealthSummary(auth, Locale.ENGLISH);

            assertThat(result.getRecentLabResults()).isEmpty();
            assertThat(result.getCurrentMedications()).isEmpty();
            assertThat(result.getRecentVitals()).isEmpty();
            assertThat(result.getImmunizations()).isEmpty();
        }

        @Test
        @DisplayName("should return empty allergies and chronicConditions when fields are null")
        void getHealthSummary_nullCsvFields_returnsEmptyLists() {
            patient.setAllergies(null);
            patient.setChronicConditions(null);
            stubPatientResolution();

            when(labResultService.getLabResultsForPatient(any(), any(), anyInt())).thenReturn(List.of());
            when(medicationService.getMedicationsForPatient(any(), any(), anyInt())).thenReturn(List.of());
            when(vitalSignService.getRecentVitals(any(), any(), anyInt())).thenReturn(List.of());
            when(immunizationService.getImmunizationsByPatientId(any())).thenReturn(List.of());

            HealthSummaryDTO result = service.getHealthSummary(auth, Locale.ENGLISH);

            assertThat(result.getAllergies()).isEmpty();
            assertThat(result.getChronicConditions()).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lab Results
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyLabResults")
    class GetMyLabResults {

        @Test
        @DisplayName("should delegate to labResultService with correct patientId and limit")
        void getMyLabResults_delegates() {
            stubPatientResolution();
            List<PatientLabResultResponseDTO> expected = List.of(new PatientLabResultResponseDTO());
            when(labResultService.getLabResultsForPatient(patientId, null, 10)).thenReturn(expected);

            List<PatientLabResultResponseDTO> result = service.getMyLabResults(auth, 10);

            assertThat(result).isEqualTo(expected);
            verify(labResultService).getLabResultsForPatient(patientId, null, 10);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Medications
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyMedications")
    class GetMyMedications {

        @Test
        @DisplayName("should delegate to medicationService with correct patientId and limit")
        void getMyMedications_delegates() {
            stubPatientResolution();
            List<PatientMedicationResponseDTO> expected = List.of(new PatientMedicationResponseDTO());
            when(medicationService.getMedicationsForPatient(patientId, null, 20)).thenReturn(expected);

            List<PatientMedicationResponseDTO> result = service.getMyMedications(auth, 20);

            assertThat(result).isEqualTo(expected);
            verify(medicationService).getMedicationsForPatient(patientId, null, 20);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Prescriptions
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyPrescriptions")
    class GetMyPrescriptions {

        @Test
        @DisplayName("should delegate to prescriptionService with correct patientId and locale")
        void getMyPrescriptions_delegates() {
            stubPatientResolution();
            List<PrescriptionResponseDTO> expected = List.of(new PrescriptionResponseDTO());
            when(prescriptionService.getPrescriptionsByPatientId(patientId, Locale.ENGLISH))
                    .thenReturn(expected);

            List<PrescriptionResponseDTO> result = service.getMyPrescriptions(auth, Locale.ENGLISH);

            assertThat(result).isEqualTo(expected);
            verify(prescriptionService).getPrescriptionsByPatientId(patientId, Locale.ENGLISH);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Vital Signs
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyVitals")
    class GetMyVitals {

        @Test
        @DisplayName("should delegate to vitalSignService with correct patientId and limit")
        void getMyVitals_delegates() {
            stubPatientResolution();
            List<PatientVitalSignResponseDTO> expected = List.of(new PatientVitalSignResponseDTO());
            when(vitalSignService.getRecentVitals(patientId, null, 5)).thenReturn(expected);

            List<PatientVitalSignResponseDTO> result = service.getMyVitals(auth, 5);

            assertThat(result).isEqualTo(expected);
            verify(vitalSignService).getRecentVitals(patientId, null, 5);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Encounters
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyEncounters")
    class GetMyEncounters {

        @Test
        @DisplayName("should delegate to encounterService with correct patientId and locale")
        void getMyEncounters_delegates() {
            stubPatientResolution();
            List<EncounterResponseDTO> expected = List.of(new EncounterResponseDTO());
            when(encounterService.getEncountersByPatientId(patientId, Locale.ENGLISH))
                    .thenReturn(expected);

            List<EncounterResponseDTO> result = service.getMyEncounters(auth, Locale.ENGLISH);

            assertThat(result).isEqualTo(expected);
            verify(encounterService).getEncountersByPatientId(patientId, Locale.ENGLISH);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Appointments
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyAppointments")
    class GetMyAppointments {

        @Test
        @DisplayName("should delegate with patientId, locale and username from auth")
        void getMyAppointments_delegates() {
            stubPatientResolution();
            when(auth.getName()).thenReturn("patient.john");
            List<AppointmentResponseDTO> expected = List.of(new AppointmentResponseDTO());
            when(appointmentService.getAppointmentsByPatientId(patientId, Locale.ENGLISH, "patient.john"))
                    .thenReturn(expected);

            List<AppointmentResponseDTO> result = service.getMyAppointments(auth, Locale.ENGLISH);

            assertThat(result).isEqualTo(expected);
            verify(appointmentService).getAppointmentsByPatientId(patientId, Locale.ENGLISH, "patient.john");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Invoices
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyInvoices")
    class GetMyInvoices {

        @Test
        @DisplayName("should delegate to billingInvoiceService with patientId, pageable and locale")
        void getMyInvoices_delegates() {
            stubPatientResolution();
            Pageable pageable = PageRequest.of(0, 10);
            Page<BillingInvoiceResponseDTO> expected = new PageImpl<>(List.of(new BillingInvoiceResponseDTO()));
            when(billingInvoiceService.getInvoicesByPatientId(patientId, pageable, Locale.ENGLISH))
                    .thenReturn(expected);

            Page<BillingInvoiceResponseDTO> result = service.getMyInvoices(auth, pageable, Locale.ENGLISH);

            assertThat(result).isEqualTo(expected);
            verify(billingInvoiceService).getInvoicesByPatientId(patientId, pageable, Locale.ENGLISH);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Consents (read)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyConsents")
    class GetMyConsents {

        @Test
        @DisplayName("should delegate to consentService with patientId and pageable")
        void getMyConsents_delegates() {
            stubPatientResolution();
            Pageable pageable = PageRequest.of(0, 5);
            Page<PatientConsentResponseDTO> expected = new PageImpl<>(List.of(new PatientConsentResponseDTO()));
            when(consentService.getConsentsByPatient(patientId, pageable)).thenReturn(expected);

            Page<PatientConsentResponseDTO> result = service.getMyConsents(auth, pageable);

            assertThat(result).isEqualTo(expected);
            verify(consentService).getConsentsByPatient(patientId, pageable);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Immunizations
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyImmunizations")
    class GetMyImmunizations {

        @Test
        @DisplayName("should delegate to immunizationService with patientId")
        void getMyImmunizations_delegates() {
            stubPatientResolution();
            List<ImmunizationResponseDTO> expected = List.of(new ImmunizationResponseDTO());
            when(immunizationService.getImmunizationsByPatientId(patientId)).thenReturn(expected);

            List<ImmunizationResponseDTO> result = service.getMyImmunizations(auth);

            assertThat(result).isEqualTo(expected);
            verify(immunizationService).getImmunizationsByPatientId(patientId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Consultations
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyConsultations")
    class GetMyConsultations {

        @Test
        @DisplayName("should delegate to consultationService with patientId")
        void getMyConsultations_delegates() {
            stubPatientResolution();
            List<ConsultationResponseDTO> expected = List.of(new ConsultationResponseDTO());
            when(consultationService.getConsultationsForPatient(patientId)).thenReturn(expected);

            List<ConsultationResponseDTO> result = service.getMyConsultations(auth);

            assertThat(result).isEqualTo(expected);
            verify(consultationService).getConsultationsForPatient(patientId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Treatment Plans
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyTreatmentPlans")
    class GetMyTreatmentPlans {

        @Test
        @DisplayName("should delegate to treatmentPlanService with patientId and pageable")
        void getMyTreatmentPlans_delegates() {
            stubPatientResolution();
            Pageable pageable = PageRequest.of(0, 10);
            Page<TreatmentPlanResponseDTO> expected = new PageImpl<>(List.of(new TreatmentPlanResponseDTO()));
            when(treatmentPlanService.listByPatient(patientId, pageable)).thenReturn(expected);

            Page<TreatmentPlanResponseDTO> result = service.getMyTreatmentPlans(auth, pageable);

            assertThat(result).isEqualTo(expected);
            verify(treatmentPlanService).listByPatient(patientId, pageable);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Referrals
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyReferrals")
    class GetMyReferrals {

        @Test
        @DisplayName("should delegate to referralService with patientId")
        void getMyReferrals_delegates() {
            stubPatientResolution();
            List<GeneralReferralResponseDTO> expected = List.of(new GeneralReferralResponseDTO());
            when(referralService.getReferralsByPatient(patientId)).thenReturn(expected);

            List<GeneralReferralResponseDTO> result = service.getMyReferrals(auth);

            assertThat(result).isEqualTo(expected);
            verify(referralService).getReferralsByPatient(patientId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // After-Visit Summaries
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyAfterVisitSummaries")
    class GetMyAfterVisitSummaries {

        @Test
        @DisplayName("should delegate to dischargeSummaryService with patientId and locale")
        void getMyAfterVisitSummaries_delegates() {
            stubPatientResolution();
            List<DischargeSummaryResponseDTO> expected = List.of(new DischargeSummaryResponseDTO());
            when(dischargeSummaryService.getDischargeSummariesByPatient(patientId, Locale.ENGLISH))
                    .thenReturn(expected);

            List<DischargeSummaryResponseDTO> result = service.getMyAfterVisitSummaries(auth, Locale.ENGLISH);

            assertThat(result).isEqualTo(expected);
            verify(dischargeSummaryService).getDischargeSummariesByPatient(patientId, Locale.ENGLISH);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Care Team
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyCareTeam")
    class GetMyCareTeam {

        @Test
        @DisplayName("should return care team with current PCP and history")
        void getMyCareTeam_withCurrentPcp() {
            stubPatientResolution();

            PatientPrimaryCareResponseDTO currentPcp = new PatientPrimaryCareResponseDTO();
            currentPcp.setId(UUID.randomUUID());
            currentPcp.setCurrent(true);

            PatientPrimaryCareResponseDTO historyEntry = new PatientPrimaryCareResponseDTO();
            historyEntry.setId(UUID.randomUUID());
            historyEntry.setCurrent(false);

            when(primaryCareService.getCurrentPrimaryCare(patientId))
                    .thenReturn(Optional.of(currentPcp));
            when(primaryCareService.getPrimaryCareHistory(patientId))
                    .thenReturn(List.of(historyEntry));

            CareTeamDTO result = service.getMyCareTeam(auth);

            assertThat(result.getPrimaryCare()).isNotNull();
            assertThat(result.getPrimaryCare().getId()).isEqualTo(currentPcp.getId());
            assertThat(result.getPrimaryCareHistory()).hasSize(1);
        }

        @Test
        @DisplayName("should return null primaryCare when no current PCP assigned")
        void getMyCareTeam_noPcp() {
            stubPatientResolution();

            when(primaryCareService.getCurrentPrimaryCare(patientId))
                    .thenReturn(Optional.empty());
            when(primaryCareService.getPrimaryCareHistory(patientId))
                    .thenReturn(List.of());

            CareTeamDTO result = service.getMyCareTeam(auth);

            assertThat(result.getPrimaryCare()).isNull();
            assertThat(result.getPrimaryCareHistory()).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Access Log
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyAccessLog")
    class GetMyAccessLog {

        @Test
        @DisplayName("should map audit log entries to AccessLogEntryDTOs")
        void getMyAccessLog_mapsEntries() {
            stubPatientResolution();
            Pageable pageable = PageRequest.of(0, 10);

            AuditEventLogResponseDTO auditEntry = new AuditEventLogResponseDTO();
            auditEntry.setUserName("dr.martin");
            auditEntry.setEventType("PATIENT_RECORD_VIEWED");
            auditEntry.setEntityType("PATIENT");
            auditEntry.setResourceId(patientId.toString());
            auditEntry.setEventDescription("Doctor viewed patient record");
            auditEntry.setStatus("SUCCESS");
            auditEntry.setEventTimestamp(LocalDateTime.now());

            Page<AuditEventLogResponseDTO> auditPage = new PageImpl<>(List.of(auditEntry));
            when(auditEventLogService.getAuditLogsByTarget("PATIENT", patientId.toString(), pageable))
                    .thenReturn(auditPage);

            Page<AccessLogEntryDTO> result = service.getMyAccessLog(auth, pageable);

            assertThat(result.getContent()).hasSize(1);
            AccessLogEntryDTO entry = result.getContent().get(0);
            assertThat(entry.getActor()).isEqualTo("dr.martin");
            assertThat(entry.getEventType()).isEqualTo("PATIENT_RECORD_VIEWED");
            assertThat(entry.getDescription()).isEqualTo("Doctor viewed patient record");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // IDOR rejection — grantMyConsent / revokeMyConsent
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IDOR rejection — consent endpoints")
    class IdorRejection {

        @Test
        @DisplayName("grantMyConsent — should throw BusinessException when patient not registered at source hospital")
        void grantMyConsent_notRegisteredAtHospital_throws() {
            stubPatientResolution();
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();

            when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, from))
                    .thenReturn(Optional.empty());

            PortalConsentRequestDTO dto = PortalConsentRequestDTO.builder()
                    .fromHospitalId(from)
                    .toHospitalId(to)
                    .purpose("Treatment")
                    .build();

            assertThatThrownBy(() -> service.grantMyConsent(auth, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not registered at the specified source hospital");
        }

        @Test
        @DisplayName("revokeMyConsent — should throw BusinessException when patient not registered at source hospital")
        void revokeMyConsent_notRegisteredAtHospital_throws() {
            stubPatientResolution();
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();

            when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, from))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.revokeMyConsent(auth, from, to))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not registered at the specified source hospital");
        }
    }
}
