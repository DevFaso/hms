package com.example.hms.service;

import com.example.hms.exception.BusinessValidationException;
import com.example.hms.exception.UnauthorizedAccessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Treatment;
import com.example.hms.payload.dto.TreatmentRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TreatmentValidationServiceTest {

    @Mock private DepartmentRepository departmentRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private MessageSource messageSource;
    @Mock private AuthService authService;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;

    @InjectMocks
    private TreatmentValidationService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Locale locale = Locale.ENGLISH;

    private TreatmentRequestDTO dto;

    @BeforeEach
    void setUp() {
        dto = TreatmentRequestDTO.builder()
                .hospitalId(hospitalId)
                .departmentId(departmentId)
                .name("Blood Test")
                .price(BigDecimal.valueOf(50))
                .build();
    }

    // ── validateTreatmentCreation ────────────────────────────────────────────

    @Nested
    @DisplayName("validateTreatmentCreation")
    class ValidateTreatmentCreation {

        @Test
        @DisplayName("passes when department belongs to hospital and user is assigned")
        void passesWhenValid() {
            when(departmentRepository.existsByIdAndHospitalId(departmentId, hospitalId)).thenReturn(true);
            when(authService.getCurrentUserId()).thenReturn(userId);
            when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(userId, hospitalId)).thenReturn(true);

            assertThatCode(() -> service.validateTreatmentCreation(dto, locale))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws BusinessValidationException when department not in hospital")
        void throwsWhenDepartmentNotInHospital() {
            when(departmentRepository.existsByIdAndHospitalId(departmentId, hospitalId)).thenReturn(false);
            when(messageSource.getMessage(eq("department.notInHospital"), any(Object[].class), eq(locale)))
                    .thenReturn("Department not in hospital");

            assertThatThrownBy(() -> service.validateTreatmentCreation(dto, locale))
                    .isInstanceOf(BusinessValidationException.class)
                    .hasMessage("Department not in hospital");
        }

        @Test
        @DisplayName("throws UnauthorizedAccessException when user not assigned to hospital")
        void throwsWhenUserNotAssigned() {
            when(departmentRepository.existsByIdAndHospitalId(departmentId, hospitalId)).thenReturn(true);
            when(authService.getCurrentUserId()).thenReturn(userId);
            when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(userId, hospitalId)).thenReturn(false);
            when(messageSource.getMessage(eq("user.notAssignedToHospital"), isNull(), eq(locale)))
                    .thenReturn("User not assigned");

            assertThatThrownBy(() -> service.validateTreatmentCreation(dto, locale))
                    .isInstanceOf(UnauthorizedAccessException.class)
                    .hasMessage("User not assigned");
        }
    }

    // ── validateTreatmentUpdate ──────────────────────────────────────────────

    @Nested
    @DisplayName("validateTreatmentUpdate")
    class ValidateTreatmentUpdate {

        private Treatment existingTreatment;

        @BeforeEach
        void setUp() {
            Hospital hospital = new Hospital();
            hospital.setId(hospitalId);
            existingTreatment = Treatment.builder().hospital(hospital).name("Old Name").build();
        }

        @Test
        @DisplayName("passes when hospital matches and department is valid")
        void passesWhenValid() {
            when(departmentRepository.existsByIdAndHospitalId(departmentId, hospitalId)).thenReturn(true);

            assertThatCode(() -> service.validateTreatmentUpdate(existingTreatment, dto, locale))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws BusinessValidationException when hospital ID changed")
        void throwsWhenHospitalChanged() {
            UUID differentHospitalId = UUID.randomUUID();
            TreatmentRequestDTO changedDto = TreatmentRequestDTO.builder()
                    .hospitalId(differentHospitalId)
                    .departmentId(departmentId)
                    .build();

            when(messageSource.getMessage(eq("treatment.hospitalChangeNotAllowed"), isNull(), eq(locale)))
                    .thenReturn("Cannot change hospital");

            assertThatThrownBy(() -> service.validateTreatmentUpdate(existingTreatment, changedDto, locale))
                    .isInstanceOf(BusinessValidationException.class)
                    .hasMessage("Cannot change hospital");
        }

        @Test
        @DisplayName("throws BusinessValidationException when department not in hospital during update")
        void throwsWhenDepartmentNotInHospitalOnUpdate() {
            when(departmentRepository.existsByIdAndHospitalId(departmentId, hospitalId)).thenReturn(false);
            when(messageSource.getMessage(eq("department.notInHospital"), any(Object[].class), eq(locale)))
                    .thenReturn("Department not in hospital");

            assertThatThrownBy(() -> service.validateTreatmentUpdate(existingTreatment, dto, locale))
                    .isInstanceOf(BusinessValidationException.class)
                    .hasMessage("Department not in hospital");
        }
    }
}
