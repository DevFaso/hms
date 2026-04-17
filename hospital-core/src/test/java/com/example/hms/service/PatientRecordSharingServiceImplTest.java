package com.example.hms.service;

import com.example.hms.enums.ConsentType;
import com.example.hms.enums.ShareScope;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdvanceDirectiveMapper;
import com.example.hms.mapper.EncounterHistoryMapper;
import com.example.hms.mapper.EncounterMapper;
import com.example.hms.mapper.EncounterTreatmentMapper;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.mapper.PatientAllergyMapper;
import com.example.hms.mapper.PatientInsuranceMapper;
import com.example.hms.mapper.PatientProblemMapper;
import com.example.hms.mapper.PatientSurgicalHistoryMapper;
import com.example.hms.mapper.PrescriptionMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientConsent;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.PatientRecordDTO;
import com.example.hms.payload.dto.RecordShareResultDTO;
import com.example.hms.repository.AdvanceDirectiveRepository;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.EncounterHistoryRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientConsentRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientSurgicalHistoryRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientRecordSharingServiceImplTest {

    // ── Repositories ────────────────────────────────────────────────────────
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientConsentRepository consentRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private EncounterHistoryRepository encounterHistoryRepository;
    @Mock private EncounterTreatmentRepository encounterTreatmentRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PatientInsuranceRepository patientInsuranceRepository;
    @Mock private PatientProblemRepository patientProblemRepository;
    @Mock private PatientSurgicalHistoryRepository patientSurgicalHistoryRepository;
    @Mock private AdvanceDirectiveRepository advanceDirectiveRepository;
    @Mock private PatientAllergyRepository patientAllergyRepository;
    @Mock private AuditEventLogRepository auditRepository;

    // ── Mappers ─────────────────────────────────────────────────────────────
    @Mock private EncounterMapper encounterMapper;
    @Mock private EncounterHistoryMapper encounterHistoryMapper;
    @Mock private EncounterTreatmentMapper encounterTreatmentMapper;
    @Mock private LabOrderMapper labOrderMapper;
    @Mock private LabResultMapper labResultMapper;
    @Mock private PrescriptionMapper prescriptionMapper;
    @Mock private PatientInsuranceMapper patientInsuranceMapper;
    @Mock private PatientProblemMapper patientProblemMapper;
    @Mock private PatientSurgicalHistoryMapper patientSurgicalHistoryMapper;
    @Mock private AdvanceDirectiveMapper advanceDirectiveMapper;
    @Mock private PatientAllergyMapper patientAllergyMapper;

    // ── Other dependencies ──────────────────────────────────────────────────
    @Mock private ObjectMapper objectMapper;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ConsentResolutionService consentResolutionService;

    @InjectMocks
    private PatientRecordSharingServiceImpl service;

    // ── Test fixtures ───────────────────────────────────────────────────────
    private final UUID patientId = UUID.randomUUID();
    private final UUID fromHospitalId = UUID.randomUUID();
    private final UUID toHospitalId = UUID.randomUUID();

    private Patient patient;
    private Hospital fromHospital;
    private Hospital toHospital;
    private PatientConsent activeConsent;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "qrBaseUrl", "https://test.example.com/patient/");

        User user = new User();
        user.setId(UUID.randomUUID());

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setUser(user);
        patient.setHospitalRegistrations(Set.of());

        fromHospital = new Hospital();
        fromHospital.setId(fromHospitalId);
        fromHospital.setName("Source Hospital");

        toHospital = new Hospital();
        toHospital.setId(toHospitalId);
        toHospital.setName("Target Hospital");

        activeConsent = new PatientConsent();
        activeConsent.setId(UUID.randomUUID());
        activeConsent.setPatient(patient);
        activeConsent.setFromHospital(fromHospital);
        activeConsent.setToHospital(toHospital);
        activeConsent.setConsentGiven(true);
        activeConsent.setConsentTimestamp(LocalDateTime.now().minusDays(1));
        activeConsent.setConsentExpiration(LocalDateTime.now().plusDays(30));
        activeConsent.setPurpose("Treatment");
        activeConsent.setConsentType(ConsentType.TREATMENT);
    }

    /** Make all table-availability checks return true so safeFetchFromTable actually queries repos. */
    private void allTablesAvailable() {
        lenient().when(jdbcTemplate.queryForObject(
            anyString(), eq(Boolean.class), anyString(), anyString()
        )).thenReturn(true);
    }

    /** Stub all clinical repositories to return empty so the flow doesn't NPE. */
    private void stubEmptyClinicalData() throws Exception {
        allTablesAvailable();
        lenient().when(encounterRepository.findAllByPatient_IdAndHospital_Id(patientId, fromHospitalId))
            .thenReturn(List.of());
        lenient().when(labOrderRepository.findByPatient_IdAndHospital_Id(patientId, fromHospitalId))
            .thenReturn(List.of());
        lenient().when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(fromHospitalId), any()))
            .thenReturn(List.of());
        lenient().when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, fromHospitalId))
            .thenReturn(List.of());
        lenient().when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, fromHospitalId))
            .thenReturn(List.of());
        lenient().when(patientInsuranceRepository.findByPatient_Id(patientId))
            .thenReturn(List.of());
        lenient().when(patientProblemRepository.findByPatient_IdAndHospital_Id(patientId, fromHospitalId))
            .thenReturn(List.of());
        lenient().when(patientSurgicalHistoryRepository.findByPatient_IdAndHospital_Id(patientId, fromHospitalId))
            .thenReturn(List.of());
        lenient().when(advanceDirectiveRepository.findByPatient_IdAndHospital_Id(patientId, fromHospitalId))
            .thenReturn(List.of());
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    }

    // ════════════════════════════════════════════════════════════════════════
    // getPatientRecord
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getPatientRecord")
    class GetPatientRecord {

        @Test
        @DisplayName("returns patient record when consent is active")
        void returnsRecord_whenConsentActive() throws Exception {
            stubEmptyClinicalData();
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.of(activeConsent));

            PatientRecordDTO result = service.getPatientRecord(patientId, fromHospitalId, toHospitalId);

            assertThat(result).isNotNull();
            assertThat(result.getPatientId()).isEqualTo(patientId);
            assertThat(result.getFirstName()).isEqualTo("Jane");
            assertThat(result.getFromHospitalName()).isEqualTo("Source Hospital");
            assertThat(result.getToHospitalName()).isEqualTo("Target Hospital");
        }

        @Test
        @DisplayName("throws BusinessException when consent not found")
        void throws_whenNoConsent() {
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPatientRecord(patientId, fromHospitalId, toHospitalId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("consent");
        }

        @Test
        @DisplayName("throws BusinessException when consent is expired")
        void throws_whenConsentExpired() {
            activeConsent.setConsentExpiration(LocalDateTime.now().minusDays(1));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.of(activeConsent));

            assertThatThrownBy(() -> service.getPatientRecord(patientId, fromHospitalId, toHospitalId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Consent scope filtering
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("consent scope filtering")
    class ScopeFiltering {

        @Test
        @DisplayName("null scope returns all clinical domains")
        void nullScope_returnsAllDomains() throws Exception {
            activeConsent.setScope(null);
            stubEmptyClinicalData();

            Encounter enc = buildEncounter();
            when(encounterRepository.findAllByPatient_IdAndHospital_Id(patientId, fromHospitalId))
                .thenReturn(List.of(enc));
            when(encounterMapper.toEncounterResponseDTO(enc))
                .thenReturn(EncounterResponseDTO.builder().id(enc.getId()).build());

            LabOrder labOrder = buildLabOrder();
            when(labOrderRepository.findByPatient_IdAndHospital_Id(patientId, fromHospitalId))
                .thenReturn(List.of(labOrder));
            when(labOrderMapper.toLabOrderResponseDTO(labOrder))
                .thenReturn(LabOrderResponseDTO.builder().id(labOrder.getId().toString()).build());

            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.of(activeConsent));

            PatientRecordDTO result = service.getPatientRecord(patientId, fromHospitalId, toHospitalId);

            assertThat(result.getEncounters()).hasSize(1);
            assertThat(result.getLabOrders()).hasSize(1);
        }

        @Test
        @DisplayName("scope=ENCOUNTERS excludes labs, prescriptions, etc.")
        void restrictedScope_excludesOtherDomains() throws Exception {
            activeConsent.setScope("ENCOUNTERS");
            stubEmptyClinicalData();

            Encounter enc = buildEncounter();
            when(encounterRepository.findAllByPatient_IdAndHospital_Id(patientId, fromHospitalId))
                .thenReturn(List.of(enc));
            when(encounterMapper.toEncounterResponseDTO(enc))
                .thenReturn(EncounterResponseDTO.builder().id(enc.getId()).build());

            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.of(activeConsent));

            PatientRecordDTO result = service.getPatientRecord(patientId, fromHospitalId, toHospitalId);

            assertThat(result.getEncounters()).hasSize(1);
            // Excluded by scope
            assertThat(result.getLabOrders()).isEmpty();
            assertThat(result.getPrescriptions()).isEmpty();
            assertThat(result.getProblems()).isEmpty();

            // Lab repos should never be called
            verify(labOrderRepository, never()).findByPatient_IdAndHospital_Id(any(), any());
        }

        @Test
        @DisplayName("scope=LAB_ORDERS,LAB_RESULTS excludes encounters")
        void labOnlyScope_excludesEncounters() throws Exception {
            activeConsent.setScope("LAB_ORDERS,LAB_RESULTS");
            stubEmptyClinicalData();

            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.of(activeConsent));

            PatientRecordDTO result = service.getPatientRecord(patientId, fromHospitalId, toHospitalId);

            assertThat(result.getEncounters()).isEmpty();
            verify(encounterRepository, never()).findAllByPatient_IdAndHospital_Id(any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // N+1 / hospital-scoped queries
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("hospital-scoped queries")
    class HospitalScopedQueries {

        @Test
        @DisplayName("encounters use hospital-scoped repo method")
        void encountersUseHospitalScopedQuery() throws Exception {
            activeConsent.setScope(null);
            stubEmptyClinicalData();

            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.of(activeConsent));

            service.getPatientRecord(patientId, fromHospitalId, toHospitalId);

            verify(encounterRepository).findAllByPatient_IdAndHospital_Id(patientId, fromHospitalId);
        }

        @Test
        @DisplayName("lab orders use hospital-scoped repo method")
        void labOrdersUseHospitalScopedQuery() throws Exception {
            activeConsent.setScope(null);
            stubEmptyClinicalData();

            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.of(activeConsent));

            service.getPatientRecord(patientId, fromHospitalId, toHospitalId);

            verify(labOrderRepository).findByPatient_IdAndHospital_Id(patientId, fromHospitalId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // exportPatientRecord
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("exportPatientRecord")
    class ExportPatientRecord {

        @Test
        @DisplayName("CSV export returns non-empty byte[]")
        void csvExport() throws Exception {
            stubEmptyClinicalData();
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.of(activeConsent));

            byte[] result = service.exportPatientRecord(patientId, fromHospitalId, toHospitalId, "csv");

            assertThat(result).isNotEmpty();
            String csv = new String(result);
            assertThat(csv).contains("Jane");
        }

        @Test
        @DisplayName("unsupported format throws IllegalArgumentException")
        void unsupportedFormat() throws Exception {
            stubEmptyClinicalData();
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.of(activeConsent));

            assertThatThrownBy(() ->
                service.exportPatientRecord(patientId, fromHospitalId, toHospitalId, "xml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // resolveAndShare
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveAndShare")
    class ResolveAndShare {

        @Test
        @DisplayName("delegates to ConsentResolutionService and returns result")
        void delegatesToConsentResolution() throws Exception {
            stubEmptyClinicalData();

            ConsentResolutionService.ConsentContext ctx =
                new ConsentResolutionService.ConsentContext(
                    ShareScope.SAME_HOSPITAL,
                    fromHospital,
                    fromHospital,
                    null,  // no consent for SAME_HOSPITAL
                    patient
                );

            when(consentResolutionService.resolve(patientId, fromHospitalId)).thenReturn(ctx);

            RecordShareResultDTO result = service.resolveAndShare(patientId, fromHospitalId);

            assertThat(result).isNotNull();
            assertThat(result.getShareScope()).isEqualTo(ShareScope.SAME_HOSPITAL);
            assertThat(result.getResolvedFromHospitalId()).isEqualTo(fromHospitalId);
            assertThat(result.getPatientRecord()).isNotNull();
            assertThat(result.getPatientRecord().getFirstName()).isEqualTo("Jane");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Audit logging
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("audit logging")
    class AuditLogging {

        @Test
        @DisplayName("saves audit event on successful record fetch")
        void savesAuditEvent() throws Exception {
            stubEmptyClinicalData();
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, fromHospitalId, toHospitalId))
                .thenReturn(Optional.of(activeConsent));

            service.getPatientRecord(patientId, fromHospitalId, toHospitalId);

            verify(auditRepository).save(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // getAggregatedPatientRecord
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAggregatedPatientRecord")
    class GetAggregatedPatientRecord {

        private final UUID hospitalBId = UUID.randomUUID();
        private Hospital hospitalB;
        private PatientConsent consentFromB;

        @BeforeEach
        void setUpHospitalB() {
            hospitalB = new Hospital();
            hospitalB.setId(hospitalBId);
            hospitalB.setName("Hospital B");

            consentFromB = new PatientConsent();
            consentFromB.setId(UUID.randomUUID());
            consentFromB.setPatient(patient);
            consentFromB.setFromHospital(hospitalB);
            consentFromB.setToHospital(toHospital);
            consentFromB.setConsentGiven(true);
            consentFromB.setConsentTimestamp(LocalDateTime.now().minusDays(1));
            consentFromB.setConsentExpiration(LocalDateTime.now().plusDays(30));
            consentFromB.setPurpose("Treatment");
            consentFromB.setConsentType(ConsentType.TREATMENT);
        }

        private void stubEmptyClinicalDataForHospital(UUID hospitalId) {
            lenient().when(encounterRepository.findAllByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of());
            lenient().when(labOrderRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of());
            lenient().when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any()))
                .thenReturn(List.of());
            lenient().when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of());
            lenient().when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of());
            lenient().when(patientProblemRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of());
            lenient().when(patientSurgicalHistoryRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of());
            lenient().when(advanceDirectiveRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of());
        }

        @Test
        @DisplayName("aggregates records from two consented hospitals")
        void aggregatesFromMultipleHospitals() throws Exception {
            allTablesAvailable();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(toHospitalId)).thenReturn(Optional.of(toHospital));

            // Consent from Hospital A (fromHospital) → toHospital
            activeConsent.setScope(null);
            // Consent from Hospital B → toHospital
            consentFromB.setScope(null);

            when(consentRepository.findAllByPatientIdAndToHospitalId(patientId, toHospitalId))
                .thenReturn(List.of(activeConsent, consentFromB));

            stubEmptyClinicalDataForHospital(fromHospitalId);
            stubEmptyClinicalDataForHospital(hospitalBId);
            lenient().when(patientInsuranceRepository.findByPatient_Id(patientId)).thenReturn(List.of());
            lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            // Hospital A has 1 encounter
            Encounter encA = buildEncounter();
            when(encounterRepository.findAllByPatient_IdAndHospital_Id(patientId, fromHospitalId))
                .thenReturn(List.of(encA));
            when(encounterMapper.toEncounterResponseDTO(encA))
                .thenReturn(EncounterResponseDTO.builder().id(encA.getId()).build());

            // Hospital B has 1 encounter
            Encounter encB = new Encounter();
            encB.setId(UUID.randomUUID());
            encB.setPatient(patient);
            encB.setEncounterDate(LocalDateTime.now());
            encB.setHospital(hospitalB);
            when(encounterRepository.findAllByPatient_IdAndHospital_Id(patientId, hospitalBId))
                .thenReturn(List.of(encB));
            when(encounterMapper.toEncounterResponseDTO(encB))
                .thenReturn(EncounterResponseDTO.builder().id(encB.getId()).build());

            PatientRecordDTO result = service.getAggregatedPatientRecord(patientId, toHospitalId);

            assertThat(result).isNotNull();
            assertThat(result.getEncounters()).hasSize(2);
            assertThat(result.getToHospitalId()).isEqualTo(toHospitalId);
        }

        @Test
        @DisplayName("includes same-hospital records when patient is registered there")
        void includesSameHospitalRecords() throws Exception {
            allTablesAvailable();

            // Register patient at requesting hospital
            PatientHospitalRegistration reg = new PatientHospitalRegistration();
            reg.setHospital(toHospital);
            reg.setActive(true);
            reg.setMrn("MRN-TO");
            patient.setHospitalRegistrations(Set.of(reg));

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(toHospitalId)).thenReturn(Optional.of(toHospital));
            when(consentRepository.findAllByPatientIdAndToHospitalId(patientId, toHospitalId))
                .thenReturn(List.of());

            stubEmptyClinicalDataForHospital(toHospitalId);
            lenient().when(patientInsuranceRepository.findByPatient_Id(patientId)).thenReturn(List.of());
            lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            PatientRecordDTO result = service.getAggregatedPatientRecord(patientId, toHospitalId);

            assertThat(result).isNotNull();
            assertThat(result.getPatientId()).isEqualTo(patientId);
        }

        @Test
        @DisplayName("throws when no consent and no registration found")
        void throwsWhenNothingFound() {
            patient.setHospitalRegistrations(Set.of());
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(toHospitalId)).thenReturn(Optional.of(toHospital));
            when(consentRepository.findAllByPatientIdAndToHospitalId(patientId, toHospitalId))
                .thenReturn(List.of());

            assertThatThrownBy(() -> service.getAggregatedPatientRecord(patientId, toHospitalId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active consent");
        }

        @Test
        @DisplayName("skips expired consents during aggregation")
        void skipsExpiredConsents() throws Exception {
            allTablesAvailable();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(toHospitalId)).thenReturn(Optional.of(toHospital));

            // Active consent from Hospital A
            activeConsent.setScope(null);
            // Expired consent from Hospital B
            consentFromB.setConsentExpiration(LocalDateTime.now().minusDays(5));

            when(consentRepository.findAllByPatientIdAndToHospitalId(patientId, toHospitalId))
                .thenReturn(List.of(activeConsent, consentFromB));

            stubEmptyClinicalDataForHospital(fromHospitalId);
            stubEmptyClinicalDataForHospital(hospitalBId);
            lenient().when(patientInsuranceRepository.findByPatient_Id(patientId)).thenReturn(List.of());
            lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            PatientRecordDTO result = service.getAggregatedPatientRecord(patientId, toHospitalId);

            assertThat(result).isNotNull();
            // Only Hospital A's data should be included (Hospital B expired)
            verify(encounterRepository).findAllByPatient_IdAndHospital_Id(patientId, fromHospitalId);
            verify(encounterRepository, never()).findAllByPatient_IdAndHospital_Id(patientId, hospitalBId);
        }

        @Test
        @DisplayName("respects scope filtering per consent during aggregation")
        void respectsScopePerConsent() throws Exception {
            allTablesAvailable();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(toHospitalId)).thenReturn(Optional.of(toHospital));

            // Hospital A allows only ENCOUNTERS
            activeConsent.setScope("ENCOUNTERS");
            // Hospital B allows only PRESCRIPTIONS
            consentFromB.setScope("PRESCRIPTIONS");

            when(consentRepository.findAllByPatientIdAndToHospitalId(patientId, toHospitalId))
                .thenReturn(List.of(activeConsent, consentFromB));

            stubEmptyClinicalDataForHospital(fromHospitalId);
            stubEmptyClinicalDataForHospital(hospitalBId);
            lenient().when(patientInsuranceRepository.findByPatient_Id(patientId)).thenReturn(List.of());
            lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            Encounter encA = buildEncounter();
            when(encounterRepository.findAllByPatient_IdAndHospital_Id(patientId, fromHospitalId))
                .thenReturn(List.of(encA));
            when(encounterMapper.toEncounterResponseDTO(encA))
                .thenReturn(EncounterResponseDTO.builder().id(encA.getId()).build());

            PatientRecordDTO result = service.getAggregatedPatientRecord(patientId, toHospitalId);

            assertThat(result.getEncounters()).hasSize(1);
            // Hospital B was scope-restricted to PRESCRIPTIONS — no encounters from B
            verify(encounterRepository, never()).findAllByPatient_IdAndHospital_Id(patientId, hospitalBId);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when patient not found")
        void throwsWhenPatientNotFound() {
            when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAggregatedPatientRecord(patientId, toHospitalId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Patient not found");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when hospital not found")
        void throwsWhenHospitalNotFound() {
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(toHospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAggregatedPatientRecord(patientId, toHospitalId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("hospital not found");
        }
    }

    // ── Test helpers ────────────────────────────────────────────────────────

    private Encounter buildEncounter() {
        Encounter e = new Encounter();
        e.setId(UUID.randomUUID());
        e.setPatient(patient);
        e.setEncounterDate(LocalDateTime.now());
        Hospital h = new Hospital();
        h.setId(fromHospitalId);
        e.setHospital(h);
        return e;
    }

    private LabOrder buildLabOrder() {
        LabOrder lo = new LabOrder();
        lo.setId(UUID.randomUUID());
        lo.setPatient(patient);
        lo.setOrderDatetime(LocalDateTime.now());
        Hospital h = new Hospital();
        h.setId(fromHospitalId);
        lo.setHospital(h);
        return lo;
    }
}
