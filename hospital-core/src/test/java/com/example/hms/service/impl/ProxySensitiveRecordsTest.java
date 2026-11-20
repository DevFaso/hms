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

    private final ProxyRecordFilter filter = new ProxyRecordFilter(new SensitivityClassifierImpl());
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
