package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.MfaMethodType;
import com.example.hms.exception.MfaStepUpRefusedException;
import com.example.hms.model.MfaBackupCode;
import com.example.hms.model.User;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.security.MfaChallengeScope;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    @Mock private UserMfaEnrollmentRepository enrollmentRepository;
    @Mock private MfaBackupCodeRepository backupCodeRepository;
    @Mock private AuditEventLogService auditEventLogService;

    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private MfaService mfaService;

    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        mfaService = new MfaService(enrollmentRepository, backupCodeRepository, passwordEncoder, auditEventLogService);
        userId = UUID.randomUUID();
        testUser = User.builder().username("test@example.com").build();
        ReflectionTestUtils.setField(testUser, "id", userId);
    }

    @Test
    void enrollTotp_createsNewEnrollmentAndBackupCodes() {
        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.empty());
        when(enrollmentRepository.save(any(UserMfaEnrollment.class)))
                .thenAnswer(inv -> {
                    UserMfaEnrollment e = inv.getArgument(0);
                    ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
                    return e;
                });
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser, challengeSession(), null);

        assertThat(result.secret()).isNotBlank();
        assertThat(result.otpauthUri()).contains("otpauth://totp/").contains("issuer=HMS");
        assertThat(result.backupCodes()).hasSize(10);
        assertThat(result.backupCodes()).allMatch(c -> c.length() == 8);

        verify(enrollmentRepository).save(any(UserMfaEnrollment.class));
        verify(backupCodeRepository, times(10)).save(any(MfaBackupCode.class));
    }

    @Test
    void enrollTotp_resetsExistingUnverifiedEnrollment() {
        UserMfaEnrollment existing = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(false).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(existing));
        when(enrollmentRepository.save(any(UserMfaEnrollment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser, challengeSession(), null);

        assertThat(result.secret()).isNotBlank();
        verify(backupCodeRepository).deleteAllByUserId(userId);
    }

    @Test
    void enrollTotp_aDisabledAuthenticatorIsNotActive_soEnrolmentRestarts() {
        UserMfaEnrollment existing = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(false).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(existing));
        when(enrollmentRepository.save(any(UserMfaEnrollment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser, challengeSession(), null);

        assertThat(result.secret()).isNotBlank();
        assertThat(existing.isVerified()).isFalse();
        assertThat(existing.isEnabled()).isFalse();
    }

    @Test
    void isMfaEnabled_returnsTrueWhenVerifiedAndEnabled() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(true).build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.isMfaEnabled(userId)).isTrue();
    }

    @Test
    void isMfaEnabled_returnsFalseWhenNoEnrollment() {
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.empty());

        assertThat(mfaService.isMfaEnabled(userId)).isFalse();
    }

    @Test
    void isMfaEnabled_returnsFalseWhenNotVerified() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(false).enabled(true).build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.isMfaEnabled(userId)).isFalse();
    }

    @Test
    void verifyBackupCode_consumesMatchingCode() {
        String rawCode = "12345678";
        String hashed = passwordEncoder.encode(rawCode);

        MfaBackupCode code = MfaBackupCode.builder()
                .userId(userId).codeHash(hashed).used(false).build();
        ReflectionTestUtils.setField(code, "id", UUID.randomUUID());

        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId))
                .thenReturn(List.of(code));
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        boolean result = mfaService.verifyBackupCode(userId, rawCode);

        assertThat(result).isTrue();
        assertThat(code.isUsed()).isTrue();
        assertThat(code.getUsedAt()).isNotNull();
        verify(backupCodeRepository).save(code);
    }

    @Test
    void verifyBackupCode_returnsFalseForInvalidCode() {
        MfaBackupCode code = MfaBackupCode.builder()
                .userId(userId).codeHash(passwordEncoder.encode("99999999")).used(false).build();

        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId))
                .thenReturn(List.of(code));

        boolean result = mfaService.verifyBackupCode(userId, "00000000");

        assertThat(result).isFalse();
        verify(backupCodeRepository, never()).save(any());
    }

    @Test
    void isMfaRequiredForRole_matchesConfiguredRoles() {
        List<String> required = List.of("ROLE_DOCTOR", "ROLE_ADMIN");
        assertThat(mfaService.isMfaRequiredForRole("ROLE_DOCTOR", required)).isTrue();
        assertThat(mfaService.isMfaRequiredForRole("ROLE_NURSE", required)).isFalse();
    }

    @Test
    void verifyEnrollment_noEnrollment_returnsFalse() {
        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.empty());

        assertThat(mfaService.verifyEnrollment(userId, "123456", fullSession())).isFalse();
    }

    @Test
    void verifyEnrollment_alreadyVerified_returnsTrue() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(true)
                .totpSecret("SECRET").build();

        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.verifyEnrollment(userId, "123456", fullSession())).isTrue();
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void verifyCode_noEnrollment_returnsFalse() {
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.empty());

        assertThat(mfaService.verifyCode(userId, "123456")).isFalse();
    }

    @Test
    void verifyCode_notVerified_returnsFalse() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(false).enabled(true)
                .totpSecret("SECRET").build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.verifyCode(userId, "123456")).isFalse();
    }

    @Test
    void verifyCode_notEnabled_returnsFalse() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(false)
                .totpSecret("SECRET").build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.verifyCode(userId, "123456")).isFalse();
    }

    @Test
    void verifyBackupCode_emptyList_returnsFalse() {
        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId))
                .thenReturn(List.of());

        assertThat(mfaService.verifyBackupCode(userId, "12345678")).isFalse();
    }

    @Test
    void isMfaEnabled_notEnabled_returnsFalse() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(false).build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.isMfaEnabled(userId)).isFalse();
    }

    @Test
    void regenerateBackupCodes_deletesOldAndCreatesNew() {
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<String> codes = mfaService.regenerateBackupCodes(userId);

        assertThat(codes).hasSize(10);
        verify(backupCodeRepository).deleteAllByUserId(userId);
        verify(backupCodeRepository, times(10)).save(any(MfaBackupCode.class));
    }

    // ── The step-up and the replacement of an active authenticator ──

    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
    private static final String NEW_SECRET = "KRSXG5CTMVRXEZLUKRSXG5CTMVRXEZLU";

    private UserMfaEnrollment activeAuthenticator() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).totpSecret(SECRET)
                .verified(true).enabled(true).build();
        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));
        return enrollment;
    }

    private static String codeFor(String secret) throws Exception {
        return new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
                .generate(secret, System.currentTimeMillis() / 1000 / 30);
    }

    private static Authentication fullSession() {
        return new UsernamePasswordAuthenticationToken(
                "test@example.com", null,
                AuthorityUtils.createAuthorityList("ROLE_HOSPITAL_ADMIN"));
    }

    private static Authentication challengeSession() {
        return new UsernamePasswordAuthenticationToken(
                "test@example.com", null,
                AuthorityUtils.createAuthorityList(MfaChallengeScope.AUTHORITY));
    }

    private void refusedWith(MfaStepUpRefusedException.Reason reason, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(MfaStepUpRefusedException.class,
                        ex -> assertThat(ex.getReason()).isEqualTo(reason));
    }

    @Test
    void stepUp_currentTotpCode_passes() throws Exception {
        activeAuthenticator();

        assertThat(mfaService.requireStepUp(userId, codeFor(SECRET), "test action", true)).isTrue();

        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void stepUp_unusedBackupCode_passesAndIsConsumed() {
        activeAuthenticator();
        MfaBackupCode backup = MfaBackupCode.builder()
                .userId(userId).codeHash(passwordEncoder.encode("12345678")).used(false).build();
        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId)).thenReturn(List.of(backup));

        assertThat(mfaService.requireStepUp(userId, "12345678", "test action", true)).isTrue();

        assertThat(backup.isUsed()).isTrue();
        verify(backupCodeRepository).save(backup);
    }

    @Test
    void stepUp_missingCode_isRefusedAsRequired_andAudited() {
        activeAuthenticator();

        refusedWith(MfaStepUpRefusedException.Reason.REQUIRED,
                () -> mfaService.requireStepUp(userId, " ", "test action", true));

        verify(auditEventLogService).logEvent(argThat(e ->
                e.getEventType() == AuditEventType.MFA_FAILURE
                        && e.getEventDescription().contains("mfa.stepup.required")));
    }

    @Test
    void stepUp_wrongCode_isRefusedAsInvalid_andTheCodeIsNeverAudited() {
        activeAuthenticator();
        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId)).thenReturn(List.of());

        refusedWith(MfaStepUpRefusedException.Reason.INVALID,
                () -> mfaService.requireStepUp(userId, "ABCDEF", "test action", true));

        verify(auditEventLogService).logEvent(argThat(e -> !e.getEventDescription().contains("ABCDEF")));
    }

    @Test
    void stepUp_noAuthenticator_strictRefuses_nonStrictLetsTheCallerAuditTheBypass() {
        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP)).thenReturn(Optional.empty());

        refusedWith(MfaStepUpRefusedException.Reason.NOT_ENROLLED,
                () -> mfaService.requireStepUp(userId, null, "test action", true));
        assertThat(mfaService.requireStepUp(userId, null, "test action", false)).isFalse();
    }

    @Test
    void stepUp_lookupFailure_isRefusedAsUnavailable() {
        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenThrow(new IllegalStateException("db down"));

        refusedWith(MfaStepUpRefusedException.Reason.UNAVAILABLE,
                () -> mfaService.requireStepUp(userId, "123456", "test action", false));
    }

    @Test
    void stepUp_noActor_isRefused() {
        refusedWith(MfaStepUpRefusedException.Reason.REQUIRED,
                () -> mfaService.requireStepUp(null, "123456", "test action", false));
    }

    @Test
    void replacing_fromTheChallenge_isRefusedEvenWithACurrentCode() throws Exception {
        UserMfaEnrollment enrollment = activeAuthenticator();
        String code = codeFor(SECRET);

        refusedWith(MfaStepUpRefusedException.Reason.CHALLENGE,
                () -> mfaService.enrollTotp(testUser, challengeSession(), code));

        assertThat(enrollment.getTotpSecret()).isEqualTo(SECRET);
        assertThat(enrollment.getPendingTotpSecret()).isNull();
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void replacing_withoutTheStepUp_isRefused() {
        UserMfaEnrollment enrollment = activeAuthenticator();

        refusedWith(MfaStepUpRefusedException.Reason.REQUIRED,
                () -> mfaService.enrollTotp(testUser, fullSession(), null));

        assertThat(enrollment.getPendingTotpSecret()).isNull();
        verify(backupCodeRepository, never()).save(any());
    }

    @Test
    void replacing_withTheStepUp_staysPending_andTheCurrentAuthenticatorKeepsWorking() throws Exception {
        UserMfaEnrollment enrollment = activeAuthenticator();
        when(enrollmentRepository.save(any(UserMfaEnrollment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(backupCodeRepository.findByUserIdAndPending(userId, true)).thenReturn(List.of());
        when(backupCodeRepository.save(any(MfaBackupCode.class))).thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser, fullSession(), codeFor(SECRET));

        assertThat(result.replacing()).isTrue();
        assertThat(enrollment.getPendingTotpSecret()).isEqualTo(result.secret());
        assertThat(enrollment.getTotpSecret()).isEqualTo(SECRET);
        assertThat(enrollment.isVerified()).isTrue();
        assertThat(enrollment.isEnabled()).isTrue();
        verify(backupCodeRepository, never()).deleteAllByUserId(any());
        verify(backupCodeRepository, times(10)).save(argThat(MfaBackupCode::isPending));
    }

    @Test
    void confirmingAReplacement_swapsTheSecretAndPromotesItsBackupCodes() throws Exception {
        UserMfaEnrollment enrollment = activeAuthenticator();
        enrollment.setPendingTotpSecret(NEW_SECRET);
        MfaBackupCode current = MfaBackupCode.builder().userId(userId).codeHash("h1").pending(false).build();
        MfaBackupCode pending = MfaBackupCode.builder().userId(userId).codeHash("h2").pending(true).build();
        when(backupCodeRepository.findByUserIdAndPending(userId, false)).thenReturn(List.of(current));
        when(backupCodeRepository.findByUserIdAndPending(userId, true)).thenReturn(List.of(pending));

        assertThat(mfaService.verifyEnrollment(userId, codeFor(NEW_SECRET), fullSession())).isTrue();

        assertThat(enrollment.getTotpSecret()).isEqualTo(NEW_SECRET);
        assertThat(enrollment.getPendingTotpSecret()).isNull();
        assertThat(enrollment.isVerified()).isTrue();
        verify(backupCodeRepository).deleteAll(List.of(current));
        assertThat(pending.isPending()).isFalse();
    }

    @Test
    void confirmingAReplacement_withAWrongCode_changesNothing() {
        UserMfaEnrollment enrollment = activeAuthenticator();
        enrollment.setPendingTotpSecret(NEW_SECRET);

        assertThat(mfaService.verifyEnrollment(userId, "ABCDEF", fullSession())).isFalse();

        assertThat(enrollment.getTotpSecret()).isEqualTo(SECRET);
        assertThat(enrollment.getPendingTotpSecret()).isEqualTo(NEW_SECRET);
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void confirmingAReplacement_fromTheChallenge_isRefused() throws Exception {
        UserMfaEnrollment enrollment = activeAuthenticator();
        enrollment.setPendingTotpSecret(NEW_SECRET);
        String code = codeFor(NEW_SECRET);

        refusedWith(MfaStepUpRefusedException.Reason.CHALLENGE,
                () -> mfaService.verifyEnrollment(userId, code, challengeSession()));

        assertThat(enrollment.getTotpSecret()).isEqualTo(SECRET);
    }

    @Test
    void pendingBackupCodes_doNotSignIn() {
        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId)).thenReturn(List.of());

        assertThat(mfaService.verifyBackupCode(userId, "12345678")).isFalse();

        verify(backupCodeRepository, never()).findByUserIdAndPending(any(), anyBoolean());
    }

    @Test
    void changingTheMfaRecords_followsTheReplacementRule() throws Exception {
        activeAuthenticator();

        refusedWith(MfaStepUpRefusedException.Reason.CHALLENGE,
                () -> mfaService.requireStepUpToChangeAuthenticator(userId, challengeSession(), null));
        refusedWith(MfaStepUpRefusedException.Reason.REQUIRED,
                () -> mfaService.requireStepUpToChangeAuthenticator(userId, fullSession(), null));
        mfaService.requireStepUpToChangeAuthenticator(userId, fullSession(), codeFor(SECRET));
    }

    @Test
    void changingTheMfaRecords_withNoActiveAuthenticator_needsNothing() {
        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP)).thenReturn(Optional.empty());

        mfaService.requireStepUpToChangeAuthenticator(userId, challengeSession(), null);

        verifyNoInteractions(auditEventLogService);
    }
}
