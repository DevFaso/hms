package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.TreatmentMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.TreatmentRequestDTO;
import com.example.hms.payload.dto.TreatmentResponseDTO;
import com.example.hms.repository.*;
import com.example.hms.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TreatmentServiceImplTest {

    @Mock private TreatmentRepository treatmentRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private TreatmentMapper treatmentMapper;
    @Mock private MessageSource messageSource;
    @Mock private AuthService authService;
    @Mock private TreatmentValidationService treatmentValidationService;
    @Mock private JwtTokenProvider jwtTokenProvider;

    @InjectMocks private TreatmentServiceImpl service;

    private final Locale locale = Locale.ENGLISH;

    // ---------- createTreatment ----------

    @Test
    void createTreatment_success() {
        UUID deptId = UUID.randomUUID();
        UUID hospId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        TreatmentRequestDTO dto = new TreatmentRequestDTO();
        dto.setDepartmentId(deptId);
        dto.setHospitalId(hospId);
        dto.setName("Therapy");
        dto.setDescription("Desc");

        Department dept = Department.builder().build();
        dept.setId(deptId);
        Hospital hospital = Hospital.builder().build();
        hospital.setId(hospId);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());

        Treatment treatment = Treatment.builder().name("Therapy").translations(new HashSet<>()).build();
        treatment.setId(UUID.randomUUID());
        TreatmentResponseDTO responseDTO = TreatmentResponseDTO.builder().id(treatment.getId()).build();

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(dept));
        when(hospitalRepository.findById(hospId)).thenReturn(Optional.of(hospital));
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(authService.getCurrentUserToken()).thenReturn("token");
        when(jwtTokenProvider.getRolesFromToken("token")).thenReturn(List.of("DOCTOR"));
        when(jwtTokenProvider.resolvePreferredRole(List.of("DOCTOR"))).thenReturn("DOCTOR");
        when(assignmentRepository.findByUserIdAndHospitalIdAndRole_Name(userId, hospId, "DOCTOR"))
            .thenReturn(Optional.of(assignment));
        when(treatmentMapper.toTreatment(eq(dto), eq(dept), eq(hospital), eq(assignment))).thenReturn(treatment);
        when(treatmentRepository.save(any(Treatment.class))).thenReturn(treatment);
        when(treatmentMapper.toTreatmentResponseDTO(treatment, "en")).thenReturn(responseDTO);

        TreatmentResponseDTO result = service.createTreatment(dto, locale, null);
        assertThat(result.getId()).isEqualTo(treatment.getId());
    }

    @Test
    void createTreatment_departmentNotFound() {
        TreatmentRequestDTO dto = new TreatmentRequestDTO();
        dto.setDepartmentId(UUID.randomUUID());
        dto.setHospitalId(UUID.randomUUID());

        when(departmentRepository.findById(dto.getDepartmentId())).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.createTreatment(dto, locale, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createTreatment_withEffectiveRoleHeader() {
        UUID deptId = UUID.randomUUID();
        UUID hospId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        TreatmentRequestDTO dto = new TreatmentRequestDTO();
        dto.setDepartmentId(deptId);
        dto.setHospitalId(hospId);
        dto.setName("Therapy");
        dto.setDescription("Desc");

        Department dept = Department.builder().build();
        dept.setId(deptId);
        Hospital hospital = Hospital.builder().build();
        hospital.setId(hospId);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());

        Treatment treatment = Treatment.builder().name("Therapy").translations(new HashSet<>()).build();
        treatment.setId(UUID.randomUUID());
        TreatmentResponseDTO responseDTO = TreatmentResponseDTO.builder().id(treatment.getId()).build();

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(dept));
        when(hospitalRepository.findById(hospId)).thenReturn(Optional.of(hospital));
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(authService.getCurrentUserToken()).thenReturn("token");
        when(jwtTokenProvider.getRolesFromToken("token")).thenReturn(List.of("ADMIN", "DOCTOR"));
        when(assignmentRepository.findByUserIdAndHospitalIdAndRole_Name(userId, hospId, "ADMIN"))
            .thenReturn(Optional.of(assignment));
        when(treatmentMapper.toTreatment(eq(dto), eq(dept), eq(hospital), eq(assignment))).thenReturn(treatment);
        when(treatmentRepository.save(any(Treatment.class))).thenReturn(treatment);
        when(treatmentMapper.toTreatmentResponseDTO(treatment, "en")).thenReturn(responseDTO);

        TreatmentResponseDTO result = service.createTreatment(dto, locale, "ADMIN");
        assertThat(result).isNotNull();
    }

    // ---------- updateTreatment ----------

    @Test
    void updateTreatment_success() {
        UUID id = UUID.randomUUID();
        TreatmentRequestDTO dto = new TreatmentRequestDTO();
        dto.setDepartmentId(UUID.randomUUID());
        dto.setHospitalId(UUID.randomUUID());

        Treatment treatment = Treatment.builder().name("Old").build();
        treatment.setId(id);
        Department dept = Department.builder().build();
        dept.setId(dto.getDepartmentId());
        Hospital hospital = Hospital.builder().build();
        hospital.setId(dto.getHospitalId());
        TreatmentResponseDTO responseDTO = TreatmentResponseDTO.builder().id(id).build();

        when(treatmentRepository.findWithAssignmentById(id)).thenReturn(Optional.of(treatment));
        when(departmentRepository.findById(dto.getDepartmentId())).thenReturn(Optional.of(dept));
        when(hospitalRepository.findById(dto.getHospitalId())).thenReturn(Optional.of(hospital));
        when(treatmentRepository.save(treatment)).thenReturn(treatment);
        when(treatmentMapper.toTreatmentResponseDTO(treatment, "en")).thenReturn(responseDTO);

        TreatmentResponseDTO result = service.updateTreatment(id, dto, locale);
        assertThat(result.getId()).isEqualTo(id);
    }

    @Test
    void updateTreatment_notFound() {
        UUID id = UUID.randomUUID();
        TreatmentRequestDTO dto = new TreatmentRequestDTO();
        when(treatmentRepository.findWithAssignmentById(id)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.updateTreatment(id, dto, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- deleteTreatment ----------

    @Test
    void deleteTreatment_success() {
        UUID id = UUID.randomUUID();
        when(treatmentRepository.existsById(id)).thenReturn(true);

        service.deleteTreatment(id);

        verify(treatmentRepository).deleteById(id);
    }

    @Test
    void deleteTreatment_notFound() {
        UUID id = UUID.randomUUID();
        when(treatmentRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteTreatment(id))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- getTreatmentById ----------

    @Test
    void getTreatmentById_success() {
        UUID id = UUID.randomUUID();
        Treatment treatment = Treatment.builder().name("Therapy").build();
        treatment.setId(id);
        TreatmentResponseDTO dto = TreatmentResponseDTO.builder().id(id).build();

        when(treatmentRepository.findWithAssignmentAndUserById(id)).thenReturn(Optional.of(treatment));
        when(treatmentMapper.toTreatmentResponseDTO(treatment, "en")).thenReturn(dto);

        TreatmentResponseDTO result = service.getTreatmentById(id, locale, "en");
        assertThat(result.getId()).isEqualTo(id);
    }

    @Test
    void getTreatmentById_notFound() {
        UUID id = UUID.randomUUID();
        when(treatmentRepository.findWithAssignmentAndUserById(id)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.getTreatmentById(id, locale, "en"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- getAllTreatments ----------

    @Test
    void getAllTreatments_success() {
        Treatment t1 = Treatment.builder().name("T1").build();
        t1.setId(UUID.randomUUID());
        Treatment t2 = Treatment.builder().name("T2").build();
        t2.setId(UUID.randomUUID());
        TreatmentResponseDTO dto1 = TreatmentResponseDTO.builder().id(t1.getId()).build();
        TreatmentResponseDTO dto2 = TreatmentResponseDTO.builder().id(t2.getId()).build();

        when(treatmentRepository.findAllWithAssignmentAndUser()).thenReturn(List.of(t1, t2));
        when(treatmentMapper.toTreatmentResponseDTO(t1, "en")).thenReturn(dto1);
        when(treatmentMapper.toTreatmentResponseDTO(t2, "en")).thenReturn(dto2);

        List<TreatmentResponseDTO> result = service.getAllTreatments(locale, "en");
        assertThat(result).hasSize(2);
    }
}
