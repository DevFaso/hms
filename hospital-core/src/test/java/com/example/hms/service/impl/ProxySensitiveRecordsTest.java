package com.example.hms.service.impl;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.service.AppointmentServiceImpl;
import com.example.hms.service.pharmacy.PrescriptionControlledResolver;
import com.example.hms.service.pharmacy.ReadyForCollectionLookup;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.ProxyRecordFilter;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.SensitivityClassifierImpl;
import com.example.hms.service.support.PatientChartAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D2730 (r65, High): a caregiver with « Dossier de santé uniquement » read
 * « Sérologie syphilis (TPHA) : Positif » and « Sulfate de fer 200 mg » from
 * a visit tagged HIV. The r65 data set, in miniature: one HIV-tagged visit
 * with a TPHA result and an iron prescription, one untagged visit with an
 * AgHBs result and paracetamol. The proxy reads the untagged visit only; the
 * patient still reads both.
 */
@DisplayName("D2730: a proxy never reads a sensitive (HIV-tagged) visit's results or medicines")
class ProxySensitiveRecordsTest {

    private final com.example.hms.repository.LabTestDefinitionRepository labTestDefinitionRepository =
        mock(com.example.hms.repository.LabTestDefinitionRepository.class);
    private final ProxyRecordFilter filter =
        new ProxyRecordFilter(new SensitivityClassifierImpl(), labTestDefinitionRepository);
    private final UUID patientId = UUID.randomUUID();
    private final Patient patient = new Patient();
    private final Hospital hospital = new Hospital();
    private Encounter hivVisit;
    private Encounter generalVisit;

    @BeforeEach
    void setUp() {
        patient.setId(patientId);
        hospital.setId(UUID.randomUUID());
        hivVisit = visit(SensitivityCategory.HIV, null);
        generalVisit = visit(null, null);
    }

    private static Encounter visit(SensitivityCategory tag, Department department) {
        Encounter e = new Encounter();
        e.setId(UUID.randomUUID());
        e.setSensitivityCategory(tag);
        e.setDepartment(department);
        return e;
    }

    private LabResult result(String test, String value, Encounter visit, SensitivityCategory testTag, int minutesAgo) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(UUID.randomUUID());
        definition.setName(test);
        definition.setSensitivityCategory(testTag);
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(hospital);
        order.setPatient(patient);
        order.setLabTestDefinition(definition);
        order.setEncounter(visit);
        LabResult r = new LabResult();
        r.setId(UUID.randomUUID());
        r.setLabOrder(order);
        r.setResultValue(value);
        r.setReleased(true);
        r.setResultDate(LocalDateTime.now().minusMinutes(minutesAgo));
        return r;
    }

    private static Prescription prescription(String name, Encounter visit, int minutesAgo) {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setMedicationName(name);
        p.setStatus(PrescriptionStatus.SIGNED);
        p.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        p.setEncounter(visit);
        return p;
    }

    @Nested
    @DisplayName("the shared filter")
    class Filter {

        @Test
        @DisplayName("a TPHA result of an HIV-tagged visit is withheld; an AgHBs of an untagged one is not")
        void syphilisSerologyOfAnHivVisit() {
            assertThat(filter.admits(result("Sérologie syphilis (TPHA)", "Positif", hivVisit, null, 1))).isFalse();
            assertThat(filter.admits(result("Antigène HBs", "Négatif", generalVisit, null, 2))).isTrue();
        }

        @Test
        @DisplayName("a test definition's own tag withholds its result even from an untagged visit")
        void aTaggedTestDefinition() {
            assertThat(filter.admits(result("Sérologie syphilis (TPHA)", "Positif", generalVisit,
                SensitivityCategory.REPRODUCTIVE_HEALTH, 1))).isFalse();
        }

        @Test
        @DisplayName("a department's default category withholds an untagged visit's rows")
        void aSensitiveDepartmentDefault() {
            Department psychiatry = new Department();
            psychiatry.setDefaultSensitivityCategory(SensitivityCategory.BEHAVIOURAL_HEALTH);
            Encounter visit = visit(null, psychiatry);
            assertThat(filter.admits(prescription("Halopéridol", visit, 1))).isFalse();
            Appointment appointment = new Appointment();
            appointment.setDepartment(psychiatry);
            assertThat(filter.admits(appointment)).as("the appointment names the department").isFalse();
        }

        @Test
        @DisplayName("a medicine, an invoice and a problem follow their tag; untagged rows travel")
        void everyRowKind() {
            assertThat(filter.admits(prescription("Sulfate de fer 200 mg", hivVisit, 1))).isFalse();
            assertThat(filter.admits(prescription("Paracétamol", generalVisit, 1))).isTrue();
            BillingInvoice invoice = new BillingInvoice();
            invoice.setEncounter(hivVisit);
            assertThat(filter.admits(invoice)).isFalse();
            PatientProblem problem = new PatientProblem();
            problem.setSensitivityCategory(SensitivityCategory.HIV);
            assertThat(filter.admits(problem)).isFalse();
            assertThat(filter.admits(new PatientProblem())).isTrue();
        }
    }

    /** One version of a hospital's test, as the catalogue holds it. */
    private LabTestDefinition version(String code, com.example.hms.enums.LabTestDefinitionApprovalStatus status,
                                      SensitivityCategory tag) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(UUID.randomUUID());
        definition.setTestCode(code);
        definition.setHospital(hospital);
        definition.setApprovalStatus(status);
        definition.setSensitivityCategory(tag);
        return definition;
    }

    private LabResult resultOf(LabTestDefinition definition, Encounter visit) {
        LabResult r = result(definition.getTestCode(), "Positif", visit, null, 1);
        r.getLabOrder().setLabTestDefinition(definition);
        return r;
    }

    @Nested
    @DisplayName("a test's versions")
    class TestVersions {

        @Test
        @DisplayName("version 2 is tagged: the results ordered on the retired version 1 are withheld too")
        void aNewVersionTagsTheOldVersionsResults() {
            LabTestDefinition v1 = version("TPHA", com.example.hms.enums.LabTestDefinitionApprovalStatus.RETIRED, null);
            LabTestDefinition v2 = version("TPHA", com.example.hms.enums.LabTestDefinitionApprovalStatus.ACTIVE,
                SensitivityCategory.REPRODUCTIVE_HEALTH);
            when(labTestDefinitionRepository.findLiveByCodeAtHospital("TPHA", hospital.getId())).thenReturn(List.of(v2));

            assertThat(filter.admits(resultOf(v1, generalVisit))).as("a result graded against version 1").isFalse();
            assertThat(filter.admits(resultOf(v2, generalVisit))).isFalse();
            assertThat(filter.labResults().test(resultOf(v1, generalVisit))).as("the per-read filter").isFalse();
        }

        @Test
        @DisplayName("a tagged draft of the next version changes nothing until it is live")
        void aDraftVersionDoesNotDecide() {
            LabTestDefinition v1 = version("NFS", com.example.hms.enums.LabTestDefinitionApprovalStatus.ACTIVE, null);
            LabTestDefinition draft = version("NFS", com.example.hms.enums.LabTestDefinitionApprovalStatus.DRAFT,
                SensitivityCategory.HIV);
            when(labTestDefinitionRepository.findLiveByCodeAtHospital("NFS", hospital.getId()))
                .thenReturn(List.of(v1, draft));

            assertThat(filter.admits(resultOf(v1, generalVisit))).isTrue();
        }

        @Test
        @DisplayName("the live version's tag was removed by the director: the test's results are ordinary again")
        void theLiveVersionDecidesWhenTheTagIsRemoved() {
            LabTestDefinition v1 = version("HCG", com.example.hms.enums.LabTestDefinitionApprovalStatus.RETIRED,
                SensitivityCategory.REPRODUCTIVE_HEALTH);
            LabTestDefinition v2 = version("HCG", com.example.hms.enums.LabTestDefinitionApprovalStatus.ACTIVE, null);
            when(labTestDefinitionRepository.findLiveByCodeAtHospital("HCG", hospital.getId())).thenReturn(List.of(v2));

            assertThat(filter.admits(resultOf(v1, generalVisit))).isTrue();
        }

        @Test
        @DisplayName("a test with no live version left keeps the tag of the version the order used")
        void aDiscontinuedTestKeepsItsOwnTag() {
            LabTestDefinition retired = version("VIH", com.example.hms.enums.LabTestDefinitionApprovalStatus.RETIRED,
                SensitivityCategory.HIV);
            when(labTestDefinitionRepository.findLiveByCodeAtHospital("VIH", hospital.getId())).thenReturn(List.of());

            assertThat(filter.admits(resultOf(retired, generalVisit))).isFalse();
        }

        @Test
        @DisplayName("one read resolves each test once, however many results it has")
        void oneLookupPerTestPerRead() {
            LabTestDefinition v1 = version("GLY", com.example.hms.enums.LabTestDefinitionApprovalStatus.ACTIVE, null);
            when(labTestDefinitionRepository.findLiveByCodeAtHospital("GLY", hospital.getId())).thenReturn(List.of(v1));
            java.util.function.Predicate<LabResult> oneRead = filter.labResults();

            assertThat(List.of(resultOf(v1, generalVisit), resultOf(v1, generalVisit), resultOf(v1, generalVisit)))
                .allMatch(oneRead);
            org.mockito.Mockito.verify(labTestDefinitionRepository, org.mockito.Mockito.times(1))
                .findLiveByCodeAtHospital("GLY", hospital.getId());
        }
    }

    @Nested
    @DisplayName("diagnoses and vitals")
    class DiagnosesAndVitals {

        @Test
        @DisplayName("« B20 Maladie due au VIH » recorded in the HIV visit is withheld with no tag of its own")
        void aProblemFollowsTheVisitItWasRecordedIn() {
            PatientProblem b20 = new PatientProblem();
            b20.setProblemDisplay("B20 Maladie due au VIH");
            b20.setSourceEncounter(hivVisit);
            PatientProblem asthma = new PatientProblem();
            asthma.setProblemDisplay("Asthme");
            asthma.setSourceEncounter(generalVisit);

            assertThat(filter.admits(b20)).isFalse();
            assertThat(filter.admits(asthma)).isTrue();
        }

        @Test
        @DisplayName("a vital taken at the HIV visit's hospital on its day is withheld; another day's is not")
        void aVitalFollowsTheSensitiveVisitItWasTakenDuring() {
            LocalDateTime visitTime = LocalDateTime.now().minusDays(10);
            hivVisit.setHospital(hospital);
            hivVisit.setEncounterDate(visitTime);
            hivVisit.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
            hivVisit.setCheckoutTimestamp(visitTime.plusHours(1));
            List<Encounter> sensitive = filter.sensitiveAmong(List.of(hivVisit, generalVisit));
            assertThat(sensitive).containsExactly(hivVisit);

            assertThat(filter.admits(vital(hospital, visitTime.plusMinutes(5)), sensitive)).isFalse();
            assertThat(filter.admits(vital(hospital, visitTime.minusDays(3)), sensitive)).isTrue();
            Hospital elsewhere = new Hospital();
            elsewhere.setId(UUID.randomUUID());
            assertThat(filter.admits(vital(elsewhere, visitTime.plusMinutes(5)), sensitive))
                .as("another hospital's reading that day").isTrue();
            assertThat(filter.admits(vital(hospital, visitTime.plusMinutes(5)), List.of()))
                .as("a patient with no sensitive visit").isTrue();
        }

        @Test
        @DisplayName("an open sensitive stay withholds every vital taken there since it began")
        void anOpenSensitiveStayCoversEveryDaySinceItBegan() {
            hivVisit.setHospital(hospital);
            hivVisit.setEncounterDate(LocalDateTime.now().minusDays(4));
            hivVisit.setStatus(com.example.hms.enums.EncounterStatus.IN_PROGRESS);
            List<Encounter> sensitive = List.of(hivVisit);

            assertThat(filter.admits(vital(hospital, LocalDateTime.now().minusDays(2)), sensitive)).isFalse();
            assertThat(filter.admits(vital(hospital, LocalDateTime.now().minusDays(6)), sensitive)).isTrue();
        }

        private com.example.hms.model.PatientVitalSign vital(Hospital at, LocalDateTime recordedAt) {
            com.example.hms.model.PatientVitalSign vital = new com.example.hms.model.PatientVitalSign();
            vital.setId(UUID.randomUUID());
            vital.setHospital(at);
            vital.setRecordedAt(recordedAt);
            return vital;
        }
    }

    @Nested
    @DisplayName("lab results")
    class LabResults {

        private final LabResultRepository repository = mock(LabResultRepository.class);
        private final PatientChartAccess chartAccess = mock(PatientChartAccess.class);
        private PatientLabResultServiceImpl service;

        @BeforeEach
        void setUp() {
            service = new PatientLabResultServiceImpl(repository, chartAccess, mock(HospitalRepository.class),
                new LabResultMapper(), mock(RecordAccessPolicy.class), mock(CrossHospitalReachRecorder.class),
                mock(LabSpecimenRepository.class), filter);
            when(chartAccess.requireOwnRecord(patientId)).thenReturn(patient);
            when(repository.findAllPatientResults(eq(patientId), any(Pageable.class))).thenReturn(List.of(
                result("Sérologie syphilis (TPHA)", "Positif", hivVisit, null, 1),
                result("Antigène HBs", "Négatif", hivVisit, null, 2),
                result("Glycémie", "5.1", generalVisit, null, 3)));
        }

        private static List<String> names(List<PatientLabResultResponseDTO> rows) {
            return rows.stream().map(PatientLabResultResponseDTO::getTestName).toList();
        }

        @Test
        @DisplayName("the proxy reads the untagged visit's result only, on every read and in the total")
        void theProxyReadsNoHivVisitResult() {
            assertThat(names(service.getLabResultsForProxy(patientId, 10))).containsExactly("Glycémie");
            assertThat(names(service.getLabResultsForProxy(patientId, 0, 10))).containsExactly("Glycémie");
            assertThat(service.countLabResultsForProxy(patientId)).isEqualTo(1);
        }

        @Test
        @DisplayName("the patient's own read is unchanged")
        void thePatientReadsEverything() {
            assertThat(names(service.getLabResultsForPatientPortal(patientId, null, 10)))
                .containsExactly("Sérologie syphilis (TPHA)", "Antigène HBs", "Glycémie");
            assertThat(service.countLabResultsForPatientPortal(patientId)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("medications")
    class Medications {

        private final PrescriptionRepository repository = mock(PrescriptionRepository.class);
        private final PatientChartAccess chartAccess = mock(PatientChartAccess.class);
        private PatientMedicationServiceImpl service;

        @BeforeEach
        void setUp() {
            service = new PatientMedicationServiceImpl(repository, mock(PrescriptionControlledResolver.class),
                chartAccess, mock(HospitalRepository.class), mock(RefillRequestRepository.class),
                mock(RecordAccessPolicy.class), mock(CrossHospitalReachRecorder.class),
                mock(ReadyForCollectionLookup.class), mock(AdmissionRepository.class), filter);
            when(chartAccess.requireOwnRecord(patientId)).thenReturn(patient);
            when(repository.findByPatient_Id(eq(patientId), any())).thenReturn(new PageImpl<>(List.of(
                prescription("Sulfate de fer 200 mg", hivVisit, 1),
                prescription("Paracétamol 500 mg", generalVisit, 2))));
        }

        private static List<String> names(List<PatientMedicationResponseDTO> rows) {
            return rows.stream().map(PatientMedicationResponseDTO::getMedicationName).toList();
        }

        @Test
        @DisplayName("the proxy never reads the HIV visit's medicine, on the list, the page or the total")
        void theProxyReadsNoHivVisitMedicine() {
            assertThat(names(service.getMedicationsForProxy(patientId, 10))).containsExactly("Paracétamol 500 mg");
            assertThat(names(service.getMedicationsPageForProxy(patientId, PageRequest.of(0, 10)).getContent()))
                .containsExactly("Paracétamol 500 mg");
            assertThat(service.getMedicationsPageForProxy(patientId, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
            assertThat(service.countMedicationsForProxy(patientId)).isEqualTo(1);
        }

        @Test
        @DisplayName("the patient's own list is unchanged")
        void thePatientReadsEverything() {
            assertThat(names(service.getMedicationsForPatientPortal(patientId, null, 10)))
                .containsExactlyInAnyOrder("Sulfate de fer 200 mg", "Paracétamol 500 mg");
        }
    }

    @Nested
    @DisplayName("appointments")
    class Appointments {

        @Test
        @DisplayName("the proxy never reads the appointment that opened the HIV-tagged visit")
        void theProxyReadsNoHivVisitAppointment() throws ReflectiveOperationException {
            AppointmentRepository appointments = mock(AppointmentRepository.class);
            EncounterRepository encounters = mock(EncounterRepository.class);
            AppointmentMapper mapper = mock(AppointmentMapper.class);
            // Every other collaborator is unused by this read (as AppointmentCalendarServiceTest builds it).
            java.lang.reflect.Constructor<?> constructor = AppointmentServiceImpl.class.getDeclaredConstructors()[0];
            AppointmentServiceImpl service = (AppointmentServiceImpl) constructor.newInstance(
                new Object[constructor.getParameterCount()]);
            ReflectionTestUtils.setField(service, "appointmentRepository", appointments);
            ReflectionTestUtils.setField(service, "encounterRepository", encounters);
            ReflectionTestUtils.setField(service, "appointmentMapper", mapper);
            ReflectionTestUtils.setField(service, "proxyRecordFilter", filter);

            Appointment hivFollowUp = new Appointment();
            hivFollowUp.setId(UUID.randomUUID());
            Appointment checkUp = new Appointment();
            checkUp.setId(UUID.randomUUID());
            hivVisit.setAppointment(hivFollowUp);
            when(appointments.findByPatient_Id(patientId)).thenReturn(List.of(hivFollowUp, checkUp));
            when(encounters.findByAppointmentIdIn(anyList())).thenReturn(List.of(hivVisit));
            when(mapper.toAppointmentResponseDTO(any())).thenAnswer(inv -> {
                AppointmentResponseDTO dto = new AppointmentResponseDTO();
                dto.setId(((Appointment) inv.getArgument(0)).getId());
                return dto;
            });

            assertThat(service.getAppointmentsForProxy(patientId))
                .extracting(AppointmentResponseDTO::getId).containsExactly(checkUp.getId());
        }
    }
}
