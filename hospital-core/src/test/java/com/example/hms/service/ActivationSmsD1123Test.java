package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.security.provider.FacilityAssignmentGuard;
import com.example.hms.service.support.UserAccountAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D1123 (round 20): a phone-only patient could not activate from the SMS.
 * It was the fixed English string "Confirm your 'ROLE_PATIENT' role at '…'.
 * Use code: … (reference: …)" — no link, the raw role code, English for a
 * French reader — and no page took the code without the e-mail link. The
 * registrar and the hospital phone got English too, and a second SMS carried
 * the temporary password.
 *
 * <p>The SMS text is read from the REAL bundles (not a mocked source), so a
 * missing key or an untranslated sentence fails here.
 */
@ExtendWith(MockitoExtension.class)
class ActivationSmsD1123Test {

    @Mock private SmsService smsService;
    @Mock private EmailService emailService;
    @Mock private AssignmentLinkService assignmentLinkService;
    @Mock private UserRepository userRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private FacilityAssignmentGuard facilityAssignmentGuard;
    @Mock private LoginAttemptService loginAttemptService;
    @Mock private UserAccountAccess accountAccess;
    @Mock private com.example.hms.repository.StaffRepository staffRepository;
    @Mock private com.example.hms.repository.PatientRepository patientRepository;
    @Spy private MessageSource messageSource = TestMessageSources.bundles();

    @InjectMocks
    private UserRoleHospitalAssignmentServiceImpl service;

    private static final String CODE = "482913";
    private static final String TEMP_PASSWORD = "Temp@Secret123";
    private static final String LINK = "https://dev.e-keneya.com/onboarding/role-welcome?assignment=CHW-P-0042";

    private UserRoleHospitalAssignment assignment;
    private User patient;

    @BeforeEach
    void setUp() {
        patient = new User();
        patient.setId(UUID.randomUUID());
        patient.setUsername("70003201");
        patient.setFirstName("Assita");
        patient.setLastName("Nana");
        patient.setPhoneNumber("+22670003201");
        patient.setActive(false);

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("CHU Wend");
        hospital.setPhoneNumber("+22625000000");

        User registrar = new User();
        registrar.setId(UUID.randomUUID());
        registrar.setPhoneNumber("+22671111111");

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setAssignmentCode("CHW-P-0042");
        assignment.setConfirmationCode(CODE);
        assignment.setConfirmationSentAt(LocalDateTime.now());
        assignment.setTempPlainPassword(TEMP_PASSWORD);
        assignment.setUser(patient);
        assignment.setRole(role("ROLE_PATIENT"));
        assignment.setHospital(hospital);
        assignment.setRegisteredBy(registrar);
        assignment.setActive(false);

        lenient().when(assignmentRepository.findById(assignment.getId())).thenReturn(Optional.of(assignment));
        lenient().when(assignmentLinkService.buildProfileCompletionUrl("CHW-P-0042")).thenReturn(LINK);
        lenient().when(smsService.deliversRealSms()).thenReturn(true);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static Role role(String code) {
        Role r = new Role();
        r.setId(UUID.randomUUID());
        r.setCode(code);
        r.setName(code);
        return r;
    }

    private String smsTo(String phone) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(smsService, atLeastOnce()).send(to.capture(), body.capture());
        for (int i = 0; i < to.getAllValues().size(); i++) {
            if (phone.equals(to.getAllValues().get(i))) {
                return body.getAllValues().get(i);
            }
        }
        throw new AssertionError("no SMS to " + phone + " in " + to.getAllValues());
    }

    @ParameterizedTest(name = "{0}: the patient SMS is in the reader''s language, with the code and the link")
    @CsvSource(delimiter = '|', value = {
        "fr|activez votre compte patient à CHU Wend",
        "es|active su cuenta de paciente en CHU Wend",
        "en|activate your patient account at CHU Wend",
    })
    void thePatientCanActivateFromTheSms(String language, String sentence) {
        LocaleContextHolder.setLocale(Locale.forLanguageTag(language));

        service.sendNotifications(assignment.getId());

        String sms = smsTo("+22670003201");
        assertThat(sms).contains(sentence).contains(CODE).contains(LINK)
            .as("no raw role code").doesNotContain("ROLE_").doesNotContain("PATIENT")
            .as("no secret beyond the one-time code").doesNotContain(TEMP_PASSWORD)
            .as("no emoji: they force UCS-2 and split the SMS").matches("[\\x{0000}-\\x{FFFF}]*");
        assertThat(sms.length()).as("one SMS segment for a short hospital name").isLessThanOrEqualTo(160);
    }

    @Test
    void noSmsEverCarriesTheTemporaryPassword() {
        LocaleContextHolder.setLocale(Locale.FRENCH);

        service.sendNotifications(assignment.getId());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(smsService, atLeastOnce()).send(anyString(), body.capture());
        assertThat(body.getAllValues()).isNotEmpty().noneMatch(text -> text.contains(TEMP_PASSWORD));
    }

    @Test
    void theRegistrarAndHospitalCopiesAreLocalizedAndNameTheRoleByItsLabel() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assignment.setRole(role("ROLE_NURSE"));

        service.sendNotifications(assignment.getId());

        assertThat(smsTo("+22670003201"))
            .contains("confirmez votre affectation (Infirmier(ère)) à CHU Wend").contains(CODE).contains(LINK);
        assertThat(smsTo("+22671111111")).contains("inscrit(e) comme Infirmier(ère) à CHU Wend")
            .doesNotContain("NURSE");
        assertThat(smsTo("+22625000000")).contains("nouveau compte").doesNotContain("NURSE");
    }

    // ---- "I have a code": activation by phone + code, no e-mail ----

    @Test
    void thePhoneAndTheCodeActivateTheAccount() {
        when(userRepository.findByPhoneNumber("+22670003201")).thenReturn(Optional.of(patient));
        when(assignmentRepository.findByUserId(patient.getId())).thenReturn(List.of(assignment));
        when(assignmentRepository.findByAssignmentCode("CHW-P-0042")).thenReturn(Optional.of(assignment));

        var view = service.verifyAssignmentByPhone("70 00 32 01", CODE);

        assertThat(assignment.getActive()).isTrue();
        assertThat(assignment.getConfirmationVerifiedAt()).isNotNull();
        assertThat(patient.isActive()).isTrue();
        assertThat(view.getTempPassword()).as("the sign-in details are shown once, here").isEqualTo(TEMP_PASSWORD);
    }

    @Test
    void aWrongCodeOrAnUnknownNumberAnswersTheSameAndFiveWrongCodesLockTheNumber() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        lenient().when(userRepository.findByPhoneNumber("+22670003201")).thenReturn(Optional.of(patient));
        lenient().when(assignmentRepository.findByUserId(patient.getId())).thenReturn(List.of(assignment));

        String wrong = messageOf(() -> service.verifyAssignmentByPhone("+22670003201", "000000"));
        String unknown = messageOf(() -> service.verifyAssignmentByPhone("+22679999999", CODE));
        assertThat(unknown).as("no account enumeration").isEqualTo(wrong);
        // QA D1152: the refusal is French on a French page (the key was in no bundle,
        // so the English default "Invalid confirmation code." was shown).
        assertThat(wrong).isEqualTo("Code de confirmation incorrect.");

        for (int i = 0; i < 4; i++) {
            messageOf(() -> service.verifyAssignmentByPhone("+22670003201", "000000"));
        }
        assertThatThrownBy(() -> service.verifyAssignmentByPhone("+22670003201", CODE))
            .as("the sixth try is refused even with the right code")
            .isInstanceOf(BusinessException.class)
            // QA D1152: the lock ends after its window; no "request a new code".
            .hasMessage("assignment.phoneActivation.tooManyAttempts");
        assertThat(assignment.getActive()).isFalse();
    }

    private static String messageOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected a refusal");
    }
}
