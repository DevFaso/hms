package com.example.hms.service.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.RecallSource;
import com.example.hms.enums.RecallStatus;
import com.example.hms.enums.RecallType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.payload.dto.scheduling.RecallRequestDTO;
import com.example.hms.payload.dto.scheduling.RecallResponseDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import com.example.hms.utility.RoleValidator;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Patient recalls (P3 #22): the return visits the practice owes patients. */
@ExtendWith(MockitoExtension.class)
class PatientRecallServiceImplTest {

    @Mock private PatientRecallRepository recallRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private RoleValidator roleValidator;

    private PatientRecallServiceImpl service;

    private UUID hospitalId;
    private Hospital hospital;
    private UUID patientId;
    private Patient patient;

    @BeforeEach
    void setUp() {
        service = new PatientRecallServiceImpl(recallRepository, patientRepository,
            hospitalRepository, departmentRepository, staffRepository,
            appointmentRepository, roleValidator);

        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("CHU Yalgado");
        lenient().when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));

        patientId = UUID.randomUUID();
        patient = mock(Patient.class);
        lenient().when(patient.getId()).thenReturn(patientId);
        lenient().when(patient.getFirstName()).thenReturn("Awa");
        lenient().when(patient.getLastName()).thenReturn("Traore");
        lenient().when(patient.isRegisteredInHospital(hospitalId)).thenReturn(true);
        lenient().when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));

        lenient().when(recallRepository.save(any(PatientRecall.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    private RecallRequestDTO request() {
        return RecallRequestDTO.builder()
            .patientId(patientId)
            .dueDate(LocalDate.now().plusWeeks(6))
            .reason("Diabetes review")
            .build();
    }

    private PatientRecall existingRecall(RecallStatus status) {
        PatientRecall recall = PatientRecall.builder()
            .patient(patient)
            .hospital(hospital)
            .status(status)
            .dueDate(LocalDate.now().plusWeeks(2))
            .reason("Post-op review")
            .build();
        recall.setId(UUID.randomUUID());
        lenient().when(recallRepository.findByIdAndHospital_Id(recall.getId(), hospitalId))
            .thenReturn(Optional.of(recall));
        return recall;
    }

    @Test
    void createPersistsAManualRecallWithDefaults() {
        RecallResponseDTO dto = service.createRecall(request(), hospitalId, "reception1");

        assertThat(dto.getStatus()).isEqualTo(RecallStatus.PENDING);
        assertThat(dto.getRecallType()).isEqualTo(RecallType.FOLLOW_UP);
        assertThat(dto.getSource()).isEqualTo(RecallSource.MANUAL);
        assertThat(dto.getReason()).isEqualTo("Diabetes review");
        assertThat(dto.getCreatedBy()).isEqualTo("reception1");
        assertThat(dto.getPatientName()).isEqualTo("Awa Traore");
    }

    @Test
    void createRefusesAPatientNotRegisteredHere() {
        when(patient.isRegisteredInHospital(hospitalId)).thenReturn(false);

        RecallRequestDTO req = request();
        assertThatThrownBy(() -> service.createRecall(req, hospitalId, "reception1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not registered");
        verify(recallRepository, never()).save(any());
    }

    @Test
    void createReadsAnotherHospitalsDepartmentAsNotFound() {
        // 404-not-403: cross-tenant probes learn nothing.
        com.example.hms.model.Department foreign = mock(com.example.hms.model.Department.class);
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        when(foreign.getHospital()).thenReturn(other);
        UUID departmentId = UUID.randomUUID();
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(foreign));

        RecallRequestDTO req = request();
        req.setDepartmentId(departmentId);
        assertThatThrownBy(() -> service.createRecall(req, hospitalId, "reception1"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listMapsRowsForTheHospital() {
        PatientRecall recall = existingRecall(RecallStatus.PENDING);
        when(recallRepository.findForHospital(hospitalId, RecallStatus.PENDING, null))
            .thenReturn(List.of(recall));

        List<RecallResponseDTO> result = service.getRecalls(hospitalId, RecallStatus.PENDING, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReason()).isEqualTo("Post-op review");
    }

    @Test
    void closeStampsWhoAndWhen() {
        UUID actorId = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(actorId);
        PatientRecall recall = existingRecall(RecallStatus.NOTIFIED);

        RecallResponseDTO dto = service.closeRecall(recall.getId(), hospitalId);

        assertThat(dto.getStatus()).isEqualTo(RecallStatus.CLOSED);
        assertThat(recall.getClosedAt()).isNotNull();
        assertThat(recall.getClosedByUserId()).isEqualTo(actorId);
    }

    @Test
    void aScheduledRecallCanCloseButNotCancel() {
        PatientRecall scheduled = existingRecall(RecallStatus.SCHEDULED);
        UUID recallId = scheduled.getId();

        assertThatThrownBy(() -> service.cancelRecall(recallId, hospitalId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("pending or notified");

        assertThat(service.closeRecall(recallId, hospitalId).getStatus())
            .isEqualTo(RecallStatus.CLOSED);
    }

    @Test
    void aTerminalRecallStaysTerminal() {
        PatientRecall closed = existingRecall(RecallStatus.CLOSED);
        UUID recallId = closed.getId();

        assertThatThrownBy(() -> service.closeRecall(recallId, hospitalId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already");
    }

    @Test
    void linkingAnAppointmentMarksTheRecallScheduled() {
        PatientRecall recall = existingRecall(RecallStatus.NOTIFIED);
        Appointment appointment = mock(Appointment.class);
        UUID appointmentId = UUID.randomUUID();
        lenient().when(appointment.getId()).thenReturn(appointmentId);
        when(appointment.getHospital()).thenReturn(hospital);
        when(appointment.getPatient()).thenReturn(patient);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        RecallResponseDTO dto = service.linkAppointment(recall.getId(), hospitalId, appointmentId);

        assertThat(dto.getStatus()).isEqualTo(RecallStatus.SCHEDULED);
        assertThat(dto.getLinkedAppointmentId()).isEqualTo(appointmentId);
    }

    @Test
    void linkingAnotherPatientsAppointmentIsRefused() {
        PatientRecall recall = existingRecall(RecallStatus.PENDING);
        Appointment appointment = mock(Appointment.class);
        UUID appointmentId = UUID.randomUUID();
        when(appointment.getHospital()).thenReturn(hospital);
        Patient somebodyElse = mock(Patient.class);
        when(somebodyElse.getId()).thenReturn(UUID.randomUUID());
        when(appointment.getPatient()).thenReturn(somebodyElse);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        UUID recallId = recall.getId();
        assertThatThrownBy(() -> service.linkAppointment(recallId, hospitalId, appointmentId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("different patient");
    }

    @Test
    void aRecallFromAnotherHospitalReadsAsNotFound() {
        UUID strangeId = UUID.randomUUID();
        when(recallRepository.findByIdAndHospital_Id(strangeId, hospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.closeRecall(strangeId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
