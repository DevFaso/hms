package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.roi.RoiSelfRequestCreateDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.PatientPortalService;
import com.example.hms.validation.SelfRequestHospitalChosenValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D4025: the patient's own record request. An empty request is refused once, naming the hospital too when
 * the patient must choose one (several registrations); the description is never called a « portée ».
 */
@ExtendWith(MockitoExtension.class)
class PatientPortalServiceImplRoiRequestTest {

    private static final String CHOOSE = "patientPortal.request.chooseHospital";

    @Mock private PatientRepository patientRepository;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private Authentication auth;

    @InjectMocks
    private PatientPortalServiceImpl service;

    private Patient patient;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        lenient().when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        lenient().when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
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
    @DisplayName("a patient of two hospitals who names none must choose; a patient of one need not")
    void hospitalRefusalFollowsTheRegistrations() {
        registeredAt(2);
        assertThat(service.selfRequestHospitalRefusal(auth, null)).isEqualTo(CHOOSE);

        registeredAt(1);
        assertThat(service.selfRequestHospitalRefusal(auth, null)).isNull();
    }

    @Test
    @DisplayName("a hospital the patient is not registered at is refused by name")
    void unregisteredHospitalIsRefused() {
        UUID elsewhere = UUID.randomUUID();
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patient.getId(), elsewhere))
            .thenReturn(Optional.empty());
        assertThat(service.selfRequestHospitalRefusal(auth, elsewhere))
            .isEqualTo("patientPortal.request.hospitalNotRegistered");
    }

    /** Bean validation as the request sees it, the hospital check answered by {@code portal}. */
    private static Map<String, String> refusalsOfEmptyBody(PatientPortalService portal) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PatientPortalService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(portal);
        ConstraintValidatorFactory defaults =
            Validation.byDefaultProvider().configure().getDefaultConstraintValidatorFactory();
        ConstraintValidatorFactory factory = new ConstraintValidatorFactory() {
            @Override
            public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                return key == SelfRequestHospitalChosenValidator.class
                    ? key.cast(new SelfRequestHospitalChosenValidator(provider))
                    : defaults.getInstance(key);
            }

            @Override
            public void releaseInstance(ConstraintValidator<?, ?> instance) {
                // nothing held
            }
        };
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("patient", "n/a", List.of()));
        try (ValidatorFactory validators = Validation.byDefaultProvider().configure()
            .constraintValidatorFactory(factory).buildValidatorFactory()) {
            Validator validator = validators.getValidator();
            Set<ConstraintViolation<RoiSelfRequestCreateDTO>> violations =
                validator.validate(new RoiSelfRequestCreateDTO());
            return violations.stream().collect(Collectors.toMap(
                v -> v.getPropertyPath().toString(), ConstraintViolation::getMessageTemplate, (a, b) -> a));
        }
    }

    @Test
    @DisplayName("an empty request of a patient who must choose names the hospital with the purpose and the description")
    void emptyRequestNamesTheHospitalAtOnce() {
        PatientPortalService portal = mock(PatientPortalService.class);
        when(portal.selfRequestHospitalRefusal(any(), isNull())).thenReturn(CHOOSE);

        Map<String, String> refusals = refusalsOfEmptyBody(portal);

        assertThat(refusals).containsOnlyKeys("hospitalId", "purpose", "scopeDescription");
        assertThat(refusals.get("hospitalId")).isEqualTo("{" + CHOOSE + "}");
    }

    @Test
    @DisplayName("a patient of one hospital is not asked for it")
    void oneHospitalIsNotAsked() {
        PatientPortalService portal = mock(PatientPortalService.class);
        when(portal.selfRequestHospitalRefusal(any(), isNull())).thenReturn(null);

        assertThat(refusalsOfEmptyBody(portal)).containsOnlyKeys("purpose", "scopeDescription");
    }

    @Test
    @DisplayName("the description's refusal and label never say « portée » or « étendue »")
    void descriptionIsNotAScope() throws IOException {
        for (String bundle : List.of("messages-patient_fr.properties", "messages-common_fr.properties")) {
            Properties texts = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/i18n/" + bundle)) {
                texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : List.of("roiSelfRequest.scopeDescription.required",
                "roiRequest.scopeDescription.required", "field.label.scopeDescription")) {
                String text = texts.getProperty(key);
                if (text != null) {
                    assertThat(text).as(bundle + " " + key).doesNotContainIgnoringCase("portée")
                        .doesNotContainIgnoringCase("étendue");
                }
            }
        }
    }
}
