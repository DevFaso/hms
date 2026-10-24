package com.example.hms.service;

import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.UserResponseDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * QA D626: a patient registration on {@code POST /users/admin-register}
 * creates the account AND the patient record in ONE transaction, so no caller
 * can leave a PATIENT account without a patient record behind (the old
 * two-call flow relied on the client's compensation delete).
 */
@Service
@RequiredArgsConstructor
public class PatientAccountRegistrationService {

    private final UserService userService;
    private final PatientService patientService;
    private final Validator validator;
    private final ActingScopeResolver actingScopeResolver;

    /**
     * The record is written at the hospital the account's PATIENT assignment
     * gets (UserServiceImpl.resolveHospitalForPatient): the pinned acting
     * hospital first, else the hospital the body names.
     */
    @Transactional
    public UserResponseDTO registerPatient(AdminSignupRequest request, Locale locale) {
        UUID patientHospitalId = actingScopeResolver.current() instanceof ActingScope.Pinned pinned
            ? pinned.hospitalId()
            : request.getHospitalId();
        PatientRequestDTO patient = request.getPatient();
        UserResponseDTO user = userService.createUserWithRolesAndHospital(request);
        patient.setUserId(user.getId());
        patient.setHospitalId(patientHospitalId);
        Set<ConstraintViolation<PatientRequestDTO>> violations = validator.validate(patient);
        if (!violations.isEmpty()) {
            // Rolls the account back with it.
            throw new ConstraintViolationException(violations);
        }
        PatientResponseDTO created = patientService.createPatientByStaff(patient, locale);
        user.setPatientId(created.getId());
        return user;
    }
}
