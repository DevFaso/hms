package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.UnauthorizedAccessException;
import com.example.hms.mapper.TreatmentMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.ServiceTranslation;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.TreatmentRequestDTO;
import com.example.hms.payload.dto.TreatmentResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TreatmentServiceImpl implements TreatmentService {

    private final TreatmentRepository treatmentRepository;
    private final DepartmentRepository departmentRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final TreatmentMapper treatmentMapper;
    private final MessageSource messageSource;
    private final AuthService authService;
    private final TreatmentValidationService treatmentValidationService;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    @Override
    public TreatmentResponseDTO createTreatment(TreatmentRequestDTO dto, Locale locale, String effectiveRoleHeader) {
        treatmentValidationService.validateTreatmentCreation(dto, locale);

        Department department = departmentRepository.findById(dto.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage("department.notFound", new Object[]{dto.getDepartmentId()}, locale)));

        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage("hospital.notFound", new Object[]{dto.getHospitalId()}, locale)));

        UUID currentUserId = authService.getCurrentUserId();
        String token = authService.getCurrentUserToken();
        List<String> jwtRoles = jwtTokenProvider.getRolesFromToken(token);

        String effectiveRole = (effectiveRoleHeader != null && jwtRoles.contains(effectiveRoleHeader))
                ? effectiveRoleHeader
                : jwtTokenProvider.resolvePreferredRole(jwtRoles);

        UserRoleHospitalAssignment assignment = assignmentRepository
                .findByUserIdAndHospitalIdAndRole_Name(currentUserId, dto.getHospitalId(), effectiveRole)
                .orElseThrow(() -> new UnauthorizedAccessException(
                        messageSource.getMessage("user.notAuthorized", null, locale)));

        Treatment treatment = treatmentMapper.toTreatment(dto, department, hospital, assignment);

        // Add default translation for the treatment
        ServiceTranslation translation = ServiceTranslation.builder()
                .treatment(treatment)
                .languageCode(locale.getLanguage())
                .name(dto.getName())
                .description(dto.getDescription())
                .assignment(assignment)
                .build();
        treatment.getTranslations().add(translation);

        Treatment savedTreatment = treatmentRepository.save(treatment);

        return treatmentMapper.toTreatmentResponseDTO(savedTreatment, locale.getLanguage());
    }

    @Override
    @Transactional
    public TreatmentResponseDTO updateTreatment(UUID id, TreatmentRequestDTO dto, Locale locale) {
        Treatment treatment = treatmentRepository.findWithAssignmentById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage("treatment.notFound", new Object[]{id}, locale)));

        treatmentValidationService.validateTreatmentUpdate(treatment, dto, locale);

        Department department = departmentRepository.findById(dto.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage("department.notFound", new Object[]{dto.getDepartmentId()}, locale)));

        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage("hospital.notFound", new Object[]{dto.getHospitalId()}, locale)));

        treatmentMapper.updateTreatmentFromDto(dto, treatment, department, hospital);
        Treatment updatedTreatment = treatmentRepository.save(treatment);

        return treatmentMapper.toTreatmentResponseDTO(updatedTreatment, locale.getLanguage());
    }

    @Override
    @Transactional
    public void deleteTreatment(UUID id) {
        if (!treatmentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Treatment not found with id: " + id);
        }
        treatmentRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public TreatmentResponseDTO getTreatmentById(UUID id, Locale locale, String language) {
        Treatment treatment = treatmentRepository.findWithAssignmentAndUserById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage("treatment.notFound", new Object[]{id}, locale)));

        return treatmentMapper.toTreatmentResponseDTO(treatment, language);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TreatmentResponseDTO> getAllTreatments(Locale locale, String language) {
        return treatmentRepository.findAllWithAssignmentAndUser().stream()
                .map(t -> treatmentMapper.toTreatmentResponseDTO(t, language))
                .toList();
    }
}

