package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.EncounterTreatmentMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.Staff;
import com.example.hms.model.Treatment;
import com.example.hms.payload.dto.EncounterTreatmentRequestDTO;
import com.example.hms.payload.dto.EncounterTreatmentResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TreatmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EncounterTreatmentServiceImplTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private TreatmentRepository treatmentRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterTreatmentRepository encounterTreatmentRepository;
    @Mock private EncounterTreatmentMapper mapper;

    @InjectMocks
    private EncounterTreatmentServiceImpl service;

    private final UUID encounterId = UUID.randomUUID();
    private final UUID treatmentId = UUID.randomUUID();
    private final UUID staffId = UUID.randomUUID();

    private Encounter encounter;
    private Treatment treatment;
    private Staff staff;
    private EncounterTreatmentRequestDTO requestDTO;
    private EncounterTreatment entity;
    private EncounterTreatmentResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        encounter = new Encounter();
        treatment = new Treatment();
        staff = new Staff();

        requestDTO = EncounterTreatmentRequestDTO.builder()
                .encounterId(encounterId)
                .treatmentId(treatmentId)
                .staffId(staffId)
                .performedAt(LocalDateTime.now())
                .outcome("Successful")
                .notes("Patient tolerated well")
                .build();

        entity = EncounterTreatment.builder()
                .encounter(encounter)
                .treatment(treatment)
                .staff(staff)
                .performedAt(requestDTO.getPerformedAt())
                .outcome(requestDTO.getOutcome())
                .notes(requestDTO.getNotes())
                .build();

        responseDTO = EncounterTreatmentResponseDTO.builder()
                .id(UUID.randomUUID())
                .encounterId(encounterId)
                .treatmentId(treatmentId)
                .staffId(staffId)
                .performedAt(requestDTO.getPerformedAt())
                .outcome("Successful")
                .notes("Patient tolerated well")
                .build();
    }

    // ── addTreatmentToEncounter ──────────────────────────────────────────────

    @Nested
    @DisplayName("addTreatmentToEncounter")
    class AddTreatmentToEncounter {

        @Test
        @DisplayName("successfully adds treatment with staff provided")
        void addTreatmentWithStaff() {
            when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
            when(treatmentRepository.findById(treatmentId)).thenReturn(Optional.of(treatment));
            when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
            when(mapper.toEntity(requestDTO, encounter, treatment, staff)).thenReturn(entity);
            when(encounterTreatmentRepository.save(entity)).thenReturn(entity);
            when(mapper.toDto(entity)).thenReturn(responseDTO);

            EncounterTreatmentResponseDTO result = service.addTreatmentToEncounter(requestDTO);

            assertThat(result).isSameAs(responseDTO);
            verify(encounterRepository).findById(encounterId);
            verify(treatmentRepository).findById(treatmentId);
            verify(staffRepository).findById(staffId);
            verify(mapper).toEntity(requestDTO, encounter, treatment, staff);
            verify(encounterTreatmentRepository).save(entity);
            verify(mapper).toDto(entity);
        }

        @Test
        @DisplayName("successfully adds treatment without staff (staffId is null)")
        void addTreatmentWithoutStaff() {
            EncounterTreatmentRequestDTO noStaffDto = EncounterTreatmentRequestDTO.builder()
                    .encounterId(encounterId)
                    .treatmentId(treatmentId)
                    .staffId(null)
                    .performedAt(LocalDateTime.now())
                    .build();

            EncounterTreatment noStaffEntity = EncounterTreatment.builder()
                    .encounter(encounter)
                    .treatment(treatment)
                    .staff(null)
                    .performedAt(noStaffDto.getPerformedAt())
                    .build();

            when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
            when(treatmentRepository.findById(treatmentId)).thenReturn(Optional.of(treatment));
            when(mapper.toEntity(noStaffDto, encounter, treatment, null)).thenReturn(noStaffEntity);
            when(encounterTreatmentRepository.save(noStaffEntity)).thenReturn(noStaffEntity);
            when(mapper.toDto(noStaffEntity)).thenReturn(responseDTO);

            EncounterTreatmentResponseDTO result = service.addTreatmentToEncounter(noStaffDto);

            assertThat(result).isSameAs(responseDTO);
            verify(staffRepository, never()).findById(any());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when encounter not found")
        void throwsWhenEncounterNotFound() {
            when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addTreatmentToEncounter(requestDTO))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(treatmentRepository, never()).findById(any());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when treatment not found")
        void throwsWhenTreatmentNotFound() {
            when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
            when(treatmentRepository.findById(treatmentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addTreatmentToEncounter(requestDTO))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(staffRepository, never()).findById(any());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when staff not found")
        void throwsWhenStaffNotFound() {
            when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
            when(treatmentRepository.findById(treatmentId)).thenReturn(Optional.of(treatment));
            when(staffRepository.findById(staffId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addTreatmentToEncounter(requestDTO))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── getTreatmentsByEncounter ─────────────────────────────────────────────

    @Nested
    @DisplayName("getTreatmentsByEncounter")
    class GetTreatmentsByEncounter {

        @Test
        @DisplayName("returns list of response DTOs for encounter")
        void returnsListOfDtos() {
            EncounterTreatment et1 = EncounterTreatment.builder()
                    .encounter(encounter).treatment(treatment).outcome("A").build();
            EncounterTreatment et2 = EncounterTreatment.builder()
                    .encounter(encounter).treatment(treatment).outcome("B").build();

            EncounterTreatmentResponseDTO dto1 = EncounterTreatmentResponseDTO.builder()
                    .id(UUID.randomUUID()).outcome("A").build();
            EncounterTreatmentResponseDTO dto2 = EncounterTreatmentResponseDTO.builder()
                    .id(UUID.randomUUID()).outcome("B").build();

            when(encounterTreatmentRepository.findByEncounter_Id(encounterId)).thenReturn(List.of(et1, et2));
            when(mapper.toDto(any(EncounterTreatment.class))).thenReturn(dto1, dto2);

            List<EncounterTreatmentResponseDTO> result = service.getTreatmentsByEncounter(encounterId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getOutcome()).isEqualTo("A");
            assertThat(result.get(1).getOutcome()).isEqualTo("B");
            verify(mapper, times(2)).toDto(any(EncounterTreatment.class));
        }

        @Test
        @DisplayName("returns empty list when no treatments for encounter")
        void returnsEmptyList() {
            when(encounterTreatmentRepository.findByEncounter_Id(encounterId)).thenReturn(List.of());

            List<EncounterTreatmentResponseDTO> result = service.getTreatmentsByEncounter(encounterId);

            assertThat(result).isEmpty();
        }
    }
}
