package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientPrimaryCareMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientPrimaryCare;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientPrimaryCareRequestDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientPrimaryCareRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Locale;

@ExtendWith(MockitoExtension.class)
class PatientPrimaryCareServiceImplTest {

    @Mock private PatientPrimaryCareRepository pcpRepo;
    @Mock private PatientRepository patientRepo;
    @Mock private HospitalRepository hospitalRepo;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepo;
    @Mock private PatientPrimaryCareMapper mapper;
    @Mock private RoleValidator roleValidator;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private PatientPrimaryCareServiceImpl service;

    // shared ids
    private UUID patientId;
    private UUID hospitalId;
    private UUID assignmentId;
    private UUID pcpId;

    // shared entities
    private Patient patient;
    private Hospital hospital;
    private UserRoleHospitalAssignment assignment;
    private PatientPrimaryCare pcpEntity;
    private PatientPrimaryCareResponseDTO responseDTO;
    private PatientPrimaryCareRequestDTO requestDTO;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();
        pcpId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);

        hospital = new Hospital();
        hospital.setId(hospitalId);

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(assignmentId);
        assignment.setHospital(hospital);

        pcpEntity = PatientPrimaryCare.builder()
                .patient(patient)
                .hospital(hospital)
                .assignment(assignment)
                .startDate(LocalDate.now())
                .current(true)
                .build();
        pcpEntity.setId(pcpId);

        responseDTO = PatientPrimaryCareResponseDTO.builder()
                .id(pcpId)
                .patientId(patientId)
                .hospitalId(hospitalId)
                .assignmentId(assignmentId)
                .build();

        requestDTO = PatientPrimaryCareRequestDTO.builder()
                .hospitalId(hospitalId)
                .assignmentId(assignmentId)
                .startDate(LocalDate.now())
                .build();
    }

    // ─── assignPrimaryCare ───────────────────────────────────────

    @Nested
    class AssignPrimaryCare {

        @Test
        void patientNotFound() {
            when(patientRepo.findById(patientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assignPrimaryCare(patientId, requestDTO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(patientId.toString());
        }

        @Test
        void hospitalNotFound() {
            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepo.findById(hospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assignPrimaryCare(patientId, requestDTO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(hospitalId.toString());
        }

        @Test
        void assignmentNotFound() {
            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepo.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(assignmentRepo.findById(assignmentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assignPrimaryCare(patientId, requestDTO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(assignmentId.toString());
        }

        @Test
        void assignmentHospitalMismatch() {
            Hospital otherHospital = new Hospital();
            otherHospital.setId(UUID.randomUUID());
            assignment.setHospital(otherHospital);

            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepo.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(assignmentRepo.findById(assignmentId)).thenReturn(Optional.of(assignment));

            assertThatThrownBy(() -> service.assignPrimaryCare(patientId, requestDTO))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("mismatch");
        }

        @Test
        void endsExistingPcpAndCreatesNew() {
            // existing PCP that should be ended
            PatientPrimaryCare existing = PatientPrimaryCare.builder()
                    .patient(patient).hospital(hospital).assignment(assignment)
                    .startDate(LocalDate.now().minusDays(30)).current(true).build();
            existing.setId(UUID.randomUUID());

            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepo.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(assignmentRepo.findById(assignmentId)).thenReturn(Optional.of(assignment));
            doNothing().when(roleValidator).validateRoleOrThrow(assignmentId, hospitalId,
                    eq("ROLE_DOCTOR"), any(Locale.class), eq(messageSource));
            when(pcpRepo.findCurrentByPatientAndHospital(patientId, hospitalId))
                    .thenReturn(Optional.of(existing));
            when(pcpRepo.save(existing)).thenReturn(existing);
            when(mapper.toEntity(requestDTO, patient, hospital, assignment))
                    .thenReturn(pcpEntity);
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            PatientPrimaryCareResponseDTO result = service.assignPrimaryCare(patientId, requestDTO);

            assertThat(result).isEqualTo(responseDTO);
            assertThat(existing.isCurrent()).isFalse();
            assertThat(existing.getEndDate()).isEqualTo(LocalDate.now().minusDays(1));
            verify(pcpRepo).save(existing);
            verify(pcpRepo).save(pcpEntity);
        }

        @Test
        void noExistingPcpCreatesNew() {
            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepo.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(assignmentRepo.findById(assignmentId)).thenReturn(Optional.of(assignment));
            doNothing().when(roleValidator).validateRoleOrThrow(assignmentId, hospitalId,
                    eq("ROLE_DOCTOR"), any(Locale.class), eq(messageSource));
            when(pcpRepo.findCurrentByPatientAndHospital(patientId, hospitalId))
                    .thenReturn(Optional.empty());
            when(mapper.toEntity(requestDTO, patient, hospital, assignment))
                    .thenReturn(pcpEntity);
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            PatientPrimaryCareResponseDTO result = service.assignPrimaryCare(patientId, requestDTO);

            assertThat(result).isEqualTo(responseDTO);
            // save called only once (no existing to end)
            verify(pcpRepo, times(1)).save(any(PatientPrimaryCare.class));
        }
    }

    // ─── updatePrimaryCare ───────────────────────────────────────

    @Nested
    class UpdatePrimaryCare {

        @Test
        void notFound() {
            when(pcpRepo.findById(pcpId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePrimaryCare(pcpId, requestDTO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(pcpId.toString());
        }

        @Test
        void updatesStartDateEndDateNotes() {
            PatientPrimaryCareRequestDTO req = PatientPrimaryCareRequestDTO.builder()
                    .startDate(LocalDate.of(2025, 1, 1))
                    .endDate(LocalDate.of(2025, 12, 31))
                    .notes("Updated notes")
                    .hospitalId(null)   // skip hospital switch
                    .assignmentId(null) // skip assignment switch
                    .build();

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            service.updatePrimaryCare(pcpId, req);

            assertThat(pcpEntity.getStartDate()).isEqualTo(LocalDate.of(2025, 1, 1));
            assertThat(pcpEntity.getEndDate()).isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(pcpEntity.getNotes()).isEqualTo("Updated notes");
        }

        @Test
        void skipNullFields() {
            LocalDate originalStart = pcpEntity.getStartDate();
            String originalNotes = pcpEntity.getNotes();

            PatientPrimaryCareRequestDTO req = PatientPrimaryCareRequestDTO.builder()
                    .startDate(null)
                    .endDate(null)
                    .notes(null)
                    .hospitalId(null)
                    .assignmentId(null)
                    .build();

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            service.updatePrimaryCare(pcpId, req);

            assertThat(pcpEntity.getStartDate()).isEqualTo(originalStart);
            assertThat(pcpEntity.getNotes()).isEqualTo(originalNotes);
        }

        @Test
        void switchesHospital() {
            UUID newHospitalId = UUID.randomUUID();
            Hospital newHospital = new Hospital();
            newHospital.setId(newHospitalId);

            PatientPrimaryCareRequestDTO req = PatientPrimaryCareRequestDTO.builder()
                    .hospitalId(newHospitalId)
                    .assignmentId(null)
                    .build();

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(hospitalRepo.findById(newHospitalId)).thenReturn(Optional.of(newHospital));
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            service.updatePrimaryCare(pcpId, req);

            assertThat(pcpEntity.getHospital()).isEqualTo(newHospital);
        }

        @Test
        void switchHospitalNotFound() {
            UUID newHospitalId = UUID.randomUUID();
            PatientPrimaryCareRequestDTO req = PatientPrimaryCareRequestDTO.builder()
                    .hospitalId(newHospitalId)
                    .build();

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(hospitalRepo.findById(newHospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePrimaryCare(pcpId, req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(newHospitalId.toString());
        }

        @Test
        void sameHospitalIdDoesNotSwitch() {
            // hospitalId matches entity's hospital — should NOT attempt to look up a new one
            PatientPrimaryCareRequestDTO req = PatientPrimaryCareRequestDTO.builder()
                    .hospitalId(hospitalId) // same as entity's
                    .assignmentId(null)
                    .build();

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            service.updatePrimaryCare(pcpId, req);

            verify(hospitalRepo, never()).findById(any());
            assertThat(pcpEntity.getHospital()).isEqualTo(hospital);
        }

        @Test
        void switchesAssignment() {
            UUID newAssignmentId = UUID.randomUUID();
            UserRoleHospitalAssignment newAssignment = new UserRoleHospitalAssignment();
            newAssignment.setId(newAssignmentId);
            newAssignment.setHospital(hospital); // same hospital

            PatientPrimaryCareRequestDTO req = PatientPrimaryCareRequestDTO.builder()
                    .hospitalId(null)
                    .assignmentId(newAssignmentId)
                    .build();

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(assignmentRepo.findById(newAssignmentId)).thenReturn(Optional.of(newAssignment));
            doNothing().when(roleValidator).validateRoleOrThrow(newAssignmentId, hospitalId,
                    eq("ROLE_DOCTOR"), any(Locale.class), eq(messageSource));
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            service.updatePrimaryCare(pcpId, req);

            assertThat(pcpEntity.getAssignment()).isEqualTo(newAssignment);
        }

        @Test
        void switchAssignmentNotFound() {
            UUID newAssignmentId = UUID.randomUUID();
            PatientPrimaryCareRequestDTO req = PatientPrimaryCareRequestDTO.builder()
                    .hospitalId(null)
                    .assignmentId(newAssignmentId)
                    .build();

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(assignmentRepo.findById(newAssignmentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePrimaryCare(pcpId, req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(newAssignmentId.toString());
        }

        @Test
        void switchAssignmentHospitalMismatch() {
            UUID newAssignmentId = UUID.randomUUID();
            Hospital otherHospital = new Hospital();
            otherHospital.setId(UUID.randomUUID());
            UserRoleHospitalAssignment newAssignment = new UserRoleHospitalAssignment();
            newAssignment.setId(newAssignmentId);
            newAssignment.setHospital(otherHospital); // different hospital

            PatientPrimaryCareRequestDTO req = PatientPrimaryCareRequestDTO.builder()
                    .hospitalId(null)
                    .assignmentId(newAssignmentId)
                    .build();

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(assignmentRepo.findById(newAssignmentId)).thenReturn(Optional.of(newAssignment));

            assertThatThrownBy(() -> service.updatePrimaryCare(pcpId, req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("mismatch");
        }

        @Test
        void sameAssignmentIdDoesNotSwitch() {
            PatientPrimaryCareRequestDTO req = PatientPrimaryCareRequestDTO.builder()
                    .hospitalId(null)
                    .assignmentId(assignmentId) // same as entity's
                    .build();

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            service.updatePrimaryCare(pcpId, req);

            verify(assignmentRepo, never()).findById(any());
        }
    }

    // ─── endPrimaryCare ──────────────────────────────────────────

    @Nested
    class EndPrimaryCare {

        @Test
        void notFound() {
            when(pcpRepo.findById(pcpId)).thenReturn(Optional.empty());

            LocalDate today = LocalDate.now();
            assertThatThrownBy(() -> service.endPrimaryCare(pcpId, today))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void nullEndDateDefaultsToToday() {
            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            service.endPrimaryCare(pcpId, null);

            assertThat(pcpEntity.getEndDate()).isEqualTo(LocalDate.now());
            assertThat(pcpEntity.isCurrent()).isFalse();
        }

        @Test
        void endDateBeforeStartDateThrows() {
            pcpEntity.setStartDate(LocalDate.of(2025, 6, 1));

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));

            LocalDate endDate = LocalDate.of(2025, 5, 1);
            assertThatThrownBy(() -> service.endPrimaryCare(pcpId, endDate))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("before startDate");
        }

        @Test
        void validEndDate() {
            pcpEntity.setStartDate(LocalDate.of(2025, 1, 1));

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            PatientPrimaryCareResponseDTO result = service.endPrimaryCare(pcpId, LocalDate.of(2025, 6, 1));

            assertThat(result).isEqualTo(responseDTO);
            assertThat(pcpEntity.getEndDate()).isEqualTo(LocalDate.of(2025, 6, 1));
            assertThat(pcpEntity.isCurrent()).isFalse();
        }

        @Test
        void nullStartDateSkipsBeforeCheck() {
            pcpEntity.setStartDate(null);

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            // should not throw — startDate is null so the "before" check is skipped
            service.endPrimaryCare(pcpId, LocalDate.of(2025, 1, 1));

            assertThat(pcpEntity.getEndDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        }

        @Test
        void endDateEqualsStartDateIsAllowed() {
            LocalDate date = LocalDate.of(2025, 6, 1);
            pcpEntity.setStartDate(date);

            when(pcpRepo.findById(pcpId)).thenReturn(Optional.of(pcpEntity));
            when(pcpRepo.save(pcpEntity)).thenReturn(pcpEntity);
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            service.endPrimaryCare(pcpId, date);

            assertThat(pcpEntity.getEndDate()).isEqualTo(date);
        }
    }

    // ─── getCurrentPrimaryCare ───────────────────────────────────

    @Nested
    class GetCurrentPrimaryCare {

        @Test
        void found() {
            when(pcpRepo.findByPatient_IdAndCurrentTrue(patientId)).thenReturn(Optional.of(pcpEntity));
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);

            Optional<PatientPrimaryCareResponseDTO> result = service.getCurrentPrimaryCare(patientId);

            assertThat(result).isPresent().contains(responseDTO);
        }

        @Test
        void notFound() {
            when(pcpRepo.findByPatient_IdAndCurrentTrue(patientId)).thenReturn(Optional.empty());

            Optional<PatientPrimaryCareResponseDTO> result = service.getCurrentPrimaryCare(patientId);

            assertThat(result).isEmpty();
        }
    }

    // ─── getPrimaryCareHistory ───────────────────────────────────

    @Nested
    class GetPrimaryCareHistory {

        @Test
        void returnsMappedList() {
            PatientPrimaryCare e2 = PatientPrimaryCare.builder()
                    .patient(patient).hospital(hospital).assignment(assignment)
                    .startDate(LocalDate.now().minusDays(60)).build();
            e2.setId(UUID.randomUUID());

            PatientPrimaryCareResponseDTO dto2 = PatientPrimaryCareResponseDTO.builder()
                    .id(e2.getId()).build();

            when(pcpRepo.findByPatient_IdOrderByStartDateDesc(patientId))
                    .thenReturn(List.of(pcpEntity, e2));
            when(mapper.toDto(pcpEntity)).thenReturn(responseDTO);
            when(mapper.toDto(e2)).thenReturn(dto2);

            List<PatientPrimaryCareResponseDTO> result = service.getPrimaryCareHistory(patientId);

            assertThat(result).hasSize(2).containsExactly(responseDTO, dto2);
        }

        @Test
        void emptyHistory() {
            when(pcpRepo.findByPatient_IdOrderByStartDateDesc(patientId))
                    .thenReturn(Collections.emptyList());

            List<PatientPrimaryCareResponseDTO> result = service.getPrimaryCareHistory(patientId);

            assertThat(result).isEmpty();
        }
    }

    // ─── deletePrimaryCare ───────────────────────────────────────

    @Nested
    class DeletePrimaryCare {

        @Test
        void notFoundThrows() {
            when(pcpRepo.existsById(pcpId)).thenReturn(false);

            assertThatThrownBy(() -> service.deletePrimaryCare(pcpId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(pcpId.toString());
        }

        @Test
        void deletesWhenExists() {
            when(pcpRepo.existsById(pcpId)).thenReturn(true);
            doNothing().when(pcpRepo).deleteById(pcpId);

            service.deletePrimaryCare(pcpId);

            verify(pcpRepo).deleteById(pcpId);
        }
    }
}
