package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.RoiRequestKind;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.roi.RoiSelfRequestCreateDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.roi.RoiRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D4025: the patient's own record request. An empty request is refused once, naming every missing field,
 * the hospital too when the patient must choose one; a correction's description is refused by its own name.
 */
@ExtendWith(MockitoExtension.class)
class PatientPortalServiceImplRoiRequestTest {

    @Mock private PatientRepository patientRepository;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private RoiRequestService roiRequestService;
    @Mock private Authentication auth;

    @InjectMocks
    private PatientPortalServiceImpl service;

    private Patient patient;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
    }

    private void registeredAt(int hospitals) {
        List<PatientHospitalRegistration> registrations = new java.util.ArrayList<>();
        for (int i = 0; i < hospitals; i++) {
            Hospital hospital = new Hospital();
            hospital.setId(UUID.randomUUID());
            PatientHospitalRegistration registration = new PatientHospitalRegistration();
            registration.setHospital(hospital);
            registration.setActive(true);
            registrations.add(registration);
        }
        when(registrationRepository.findByPatientId(patient.getId())).thenReturn(registrations);
    }

    @Test
    @DisplayName("an empty request of a patient of two hospitals names the hospital, the purpose and the description")
    void emptyRequestNamesTheHospital() {
        registeredAt(2);

        FieldBusinessException refusal = catchThrowableOfType(FieldBusinessException.class,
            () -> service.createRoiRequest(auth, new RoiSelfRequestCreateDTO()));

        assertThat(refusal.getFieldErrors()).containsOnlyKeys("hospitalId", "purpose", "scopeDescription");
        verify(roiRequestService, never()).createForSelf(any(), any(), any());
    }

    @Test
    @DisplayName("a patient of one hospital is not asked for it: the empty request names the two texts only")
    void oneHospitalIsNotAsked() {
        registeredAt(1);

        FieldBusinessException refusal = catchThrowableOfType(FieldBusinessException.class,
            () -> service.createRoiRequest(auth, new RoiSelfRequestCreateDTO()));

        assertThat(refusal.getFieldErrors()).containsOnlyKeys("purpose", "scopeDescription");
    }

    @Test
    @DisplayName("a correction's missing description is refused as the correction, not as a scope")
    void correctionDescriptionHasItsOwnReason() {
        registeredAt(1);
        RoiSelfRequestCreateDTO dto = new RoiSelfRequestCreateDTO();
        dto.setRequestKind(RoiRequestKind.CORRECTION);
        dto.setPurpose("Wrong birth date");

        FieldBusinessException refusal = catchThrowableOfType(FieldBusinessException.class,
            () -> service.createRoiRequest(auth, dto));

        assertThat(refusal.getFieldErrors()).containsOnlyKeys("scopeDescription");
        assertThat(refusal.getFieldErrors().get("scopeDescription"))
            .containsIgnoringCase("correction");
    }

    @Test
    @DisplayName("a complete request is filed at the patient's only hospital")
    void completeRequestIsFiled() {
        registeredAt(1);
        RoiSelfRequestCreateDTO dto = new RoiSelfRequestCreateDTO();
        dto.setPurpose("Second opinion");
        dto.setScopeDescription("FULL_RECORD");

        service.createRoiRequest(auth, dto);

        verify(roiRequestService).createForSelf(eq(patient), any(UUID.class), eq(dto));
    }
}
