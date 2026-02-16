package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ServiceTranslationMapper;
import com.example.hms.model.ServiceTranslation;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.ServiceTranslationRequestDTO;
import com.example.hms.payload.dto.ServiceTranslationResponseDTO;
import com.example.hms.repository.ServiceTranslationRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTranslationServiceImplTest {

    @Mock private ServiceTranslationRepository translationRepository;
    @Mock private TreatmentRepository treatmentRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private ServiceTranslationMapper mapper;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private ServiceTranslationServiceImpl service;

    private static final Locale LOCALE = Locale.ENGLISH;

    // ─── helpers ───

    private ServiceTranslationRequestDTO buildRequest(UUID treatmentId, UUID assignmentId) {
        ServiceTranslationRequestDTO dto = new ServiceTranslationRequestDTO();
        dto.setTreatmentId(treatmentId);
        dto.setAssignmentId(assignmentId);
        dto.setLanguageCode("en");
        dto.setName("Physiotherapy");
        dto.setDescription("Physical treatment");
        return dto;
    }

    private void stubMessage(String key) {
        lenient().when(messageSource.getMessage(eq(key), isNull(), eq(LOCALE)))
                .thenReturn("[" + key + "]");
    }

    // ═══════════════ createTranslation ═══════════════

    @Nested
    @DisplayName("createTranslation")
    class CreateTranslation {

        @Test
        @DisplayName("happy path – saves and returns DTO")
        void happyPath() {
            UUID treatmentId = UUID.randomUUID();
            UUID assignmentId = UUID.randomUUID();
            ServiceTranslationRequestDTO request = buildRequest(treatmentId, assignmentId);

            Treatment treatment = new Treatment();
            treatment.setId(treatmentId);
            UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
            assignment.setId(assignmentId);
            ServiceTranslation entity = new ServiceTranslation();
            ServiceTranslation saved = new ServiceTranslation();
            saved.setId(UUID.randomUUID());
            ServiceTranslationResponseDTO responseDTO = ServiceTranslationResponseDTO.builder().id(saved.getId()).build();

            when(treatmentRepository.findById(treatmentId)).thenReturn(Optional.of(treatment));
            when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
            when(mapper.toEntity(request, treatment, assignment)).thenReturn(entity);
            when(translationRepository.save(entity)).thenReturn(saved);
            when(mapper.toDto(saved)).thenReturn(responseDTO);

            ServiceTranslationResponseDTO result = service.createTranslation(request, LOCALE);

            assertThat(result).isSameAs(responseDTO);
            verify(translationRepository).save(entity);
        }

        @Test
        @DisplayName("throws when treatment not found")
        void treatmentNotFound() {
            UUID treatmentId = UUID.randomUUID();
            UUID assignmentId = UUID.randomUUID();
            ServiceTranslationRequestDTO request = buildRequest(treatmentId, assignmentId);
            stubMessage("treatment.not.found");

            when(treatmentRepository.findById(treatmentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createTranslation(request, LOCALE))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(translationRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws when assignment not found")
        void assignmentNotFound() {
            UUID treatmentId = UUID.randomUUID();
            UUID assignmentId = UUID.randomUUID();
            ServiceTranslationRequestDTO request = buildRequest(treatmentId, assignmentId);
            stubMessage("assignment.not.found");

            Treatment treatment = new Treatment();
            treatment.setId(treatmentId);
            when(treatmentRepository.findById(treatmentId)).thenReturn(Optional.of(treatment));
            when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createTranslation(request, LOCALE))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(translationRepository, never()).save(any());
        }
    }

    // ═══════════════ getTranslationById ═══════════════

    @Nested
    @DisplayName("getTranslationById")
    class GetTranslationById {

        @Test
        @DisplayName("returns DTO when found")
        void found() {
            UUID id = UUID.randomUUID();
            ServiceTranslation entity = new ServiceTranslation();
            entity.setId(id);
            ServiceTranslationResponseDTO responseDTO = ServiceTranslationResponseDTO.builder().id(id).build();

            when(translationRepository.findById(id)).thenReturn(Optional.of(entity));
            when(mapper.toDto(entity)).thenReturn(responseDTO);

            assertThat(service.getTranslationById(id, LOCALE)).isSameAs(responseDTO);
        }

        @Test
        @DisplayName("throws when not found")
        void notFound() {
            UUID id = UUID.randomUUID();
            stubMessage("translation.not.found");
            when(translationRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTranslationById(id, LOCALE))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ═══════════════ getAllTranslations ═══════════════

    @Nested
    @DisplayName("getAllTranslations")
    class GetAllTranslations {

        @Test
        @DisplayName("returns mapped list")
        void returnsList() {
            ServiceTranslation t1 = new ServiceTranslation();
            t1.setId(UUID.randomUUID());
            ServiceTranslation t2 = new ServiceTranslation();
            t2.setId(UUID.randomUUID());
            ServiceTranslationResponseDTO d1 = ServiceTranslationResponseDTO.builder().id(t1.getId()).build();
            ServiceTranslationResponseDTO d2 = ServiceTranslationResponseDTO.builder().id(t2.getId()).build();

            when(translationRepository.findAll()).thenReturn(List.of(t1, t2));
            when(mapper.toDto(t1)).thenReturn(d1);
            when(mapper.toDto(t2)).thenReturn(d2);

            List<ServiceTranslationResponseDTO> result = service.getAllTranslations(LOCALE);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getId()).isEqualTo(t1.getId());
            assertThat(result.get(1).getId()).isEqualTo(t2.getId());
        }

        @Test
        @DisplayName("returns empty list when no translations exist")
        void emptyList() {
            when(translationRepository.findAll()).thenReturn(List.of());

            assertThat(service.getAllTranslations(LOCALE)).isEmpty();
        }
    }

    // ═══════════════ updateTranslation ═══════════════

    @Nested
    @DisplayName("updateTranslation")
    class UpdateTranslation {

        @Test
        @DisplayName("updates and returns DTO")
        void happyPath() {
            UUID id = UUID.randomUUID();
            ServiceTranslationRequestDTO request = buildRequest(UUID.randomUUID(), UUID.randomUUID());
            ServiceTranslation existing = new ServiceTranslation();
            existing.setId(id);
            ServiceTranslation updated = new ServiceTranslation();
            updated.setId(id);
            ServiceTranslationResponseDTO responseDTO = ServiceTranslationResponseDTO.builder().id(id).build();

            when(translationRepository.findById(id)).thenReturn(Optional.of(existing));
            when(translationRepository.save(existing)).thenReturn(updated);
            when(mapper.toDto(updated)).thenReturn(responseDTO);

            assertThat(service.updateTranslation(id, request, LOCALE)).isSameAs(responseDTO);

            verify(mapper).updateEntity(existing, request);
            verify(translationRepository).save(existing);
        }

        @Test
        @DisplayName("throws when translation not found")
        void notFound() {
            UUID id = UUID.randomUUID();
            stubMessage("translation.not.found");
            when(translationRepository.findById(id)).thenReturn(Optional.empty());

            UUID randomId = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            var request = buildRequest(randomId, id2);
            assertThatThrownBy(() -> service.updateTranslation(id, request, LOCALE))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ═══════════════ deleteTranslation ═══════════════

    @Nested
    @DisplayName("deleteTranslation")
    class DeleteTranslation {

        @Test
        @DisplayName("deletes existing translation")
        void happyPath() {
            UUID id = UUID.randomUUID();
            when(translationRepository.existsById(id)).thenReturn(true);

            service.deleteTranslation(id, LOCALE);

            verify(translationRepository).deleteById(id);
        }

        @Test
        @DisplayName("throws when translation does not exist")
        void notFound() {
            UUID id = UUID.randomUUID();
            stubMessage("translation.not.found");
            when(translationRepository.existsById(id)).thenReturn(false);

            assertThatThrownBy(() -> service.deleteTranslation(id, LOCALE))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(translationRepository, never()).deleteById(any());
        }
    }
}
