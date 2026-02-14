package com.example.hms.service;

import com.example.hms.exception.BusinessValidationException;
import com.example.hms.exception.UnauthorizedAccessException;
import com.example.hms.model.Treatment;
import com.example.hms.payload.dto.TreatmentRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TreatmentValidationService {

    private final DepartmentRepository departmentRepository;
    private final HospitalRepository hospitalRepository;
    private final MessageSource messageSource;
    private final AuthService authService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    public void validateTreatmentCreation(TreatmentRequestDTO dto, Locale locale) {
        validateHospitalDepartmentRelation(dto.getHospitalId(), dto.getDepartmentId(), locale);
        validateUserHospitalAssignment(dto.getHospitalId(), locale);
    }

    public void validateTreatmentUpdate(Treatment treatment, TreatmentRequestDTO dto, Locale locale) {
        if (!treatment.getHospital().getId().equals(dto.getHospitalId())) {
            throw new BusinessValidationException(
                    messageSource.getMessage("treatment.hospitalChangeNotAllowed", null, locale));
        }
        validateHospitalDepartmentRelation(dto.getHospitalId(), dto.getDepartmentId(), locale);
    }

    private void validateHospitalDepartmentRelation(UUID hospitalId, UUID departmentId, Locale locale) {
        if (!departmentRepository.existsByIdAndHospitalId(departmentId, hospitalId)) {
            throw new BusinessValidationException(
                    messageSource.getMessage("department.notInHospital", new Object[]{departmentId, hospitalId}, locale));
        }
    }

    private void validateUserHospitalAssignment(UUID hospitalId, Locale locale) {
        UUID currentUserId = authService.getCurrentUserId();
        if (!assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(currentUserId, hospitalId)) {
            throw new UnauthorizedAccessException(
                    messageSource.getMessage("user.notAssignedToHospital", null, locale));
        }
    }

}

