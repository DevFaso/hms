package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.MfaMethodType;
import com.example.hms.exception.UnauthorizedException;
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
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.empty());
        when(enrollmentRepository.save(any(UserMfaEnrollment.class)))
                .thenAnswer(inv -> {
                    UserMfaEnrollment e = inv.getArgument(0);
                    ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
                    return e;
                });
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser);

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

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(existing));
        when(enrollmentRepository.save(any(UserMfaEnrollment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser);

        assertThat(result.secret()).isNotBlank();
        verify(backupCodeRepository).deleteAllByUserId(userId);
    }

    @Test
    void enrollTotp_resetsAlreadyVerifiedEnrollment() {
        UserMfaEnrollment existing = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(true).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(existing));
        when(enrollmentRepository.save(any(UserMfaEnrollment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(backupCodeRepository.save(any(MfaBackupCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser);

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

        when(backupCodeRepository.findByUserIdAndUsedFalse(userId))
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

        when(backupCodeRepository.findByUserIdAndUsedFalse(userId))
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
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.empty());

        assertThat(mfaService.verifyEnrollment(userId, "123456")).isFalse();
    }

    @Test
    void verifyEnrollment_alreadyVerified_returnsTrue() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(true)
                .totpSecret("SECRET").build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.verifyEnrollment(userId, "123456")).isTrue();
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
        when(backupCodeRepository.findByUserIdAndUsedFalse(userId))
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

    // ── Replacing a verified authenticator (the step-up) ──

    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private void verifiedAuthenticator() {
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(UserMfaEnrollment.builder()
                        .user(testUser).method(MfaMethodType.TOTP).totpSecret(SECRET)
                        .verified(true).enabled(true).build()));
    }

    private static String currentCode() throws Exception {
        return new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
                .generate(SECRET, System.currentTimeMillis() / 1000 / 30);
    }

    private static Authentication fullSession() {
        return new UsernamePasswordAuthenticationToken(
                "test@example.com", null,
                AuthorityUtils.createAuthorityList("ROLE_HOSPITAL_ADMIN"));
    }

    private static Authentication challengeSession() {
        return new UsernamePasswordAuthenticationToken(
                "test@example.com", null,
                AuthorityUtils.createAuthorityList(
                        MfaChallengeScope.AUTHORITY));
    }

    @Test
    void stepUp_nothingVerifiedYet_firstEnrolmentPassesEvenFromTheChallenge() {
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.empty());

        mfaService.requireStepUpToReplaceAuthenticator(userId, challengeSession(), null);

        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void stepUp_firstEnrolmentLeftHalfDone_canStillBeRestartedFromTheChallenge() {
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(UserMfaEnrollment.builder()
                        .user(testUser).method(MfaMethodType.TOTP).totpSecret(SECRET)
                        .verified(false).enabled(false).build()));

        mfaService.requireStepUpToReplaceAuthenticator(userId, challengeSession(), null);

        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void stepUp_reEnrolmentLeftHalfDone_staysProtected() throws Exception {
        // enrollTotp unverified the confirmed row and kept lastVerifiedAt.
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(UserMfaEnrollment.builder()
                        .user(testUser).method(MfaMethodType.TOTP).totpSecret(SECRET)
                        .verified(false).enabled(false)
                        .lastVerifiedAt(java.time.LocalDateTime.now().minusDays(3)).build()));
        String code = currentCode();

        assertThatThrownBy(() ->
                mfaService.requireStepUpToReplaceAuthenticator(userId, challengeSession(), null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() ->
                mfaService.requireStepUpToReplaceAuthenticator(userId, fullSession(), code))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(mfaService.hasConfirmedAuthenticator(userId)).isTrue();
        assertThat(mfaService.isMfaEnabled(userId)).isFalse();
    }

    @Test
    void stepUp_challengeSession_isRefusedEvenWithAValidCode() throws Exception {
        verifiedAuthenticator();
        String code = currentCode();

        assertThatThrownBy(() ->
                mfaService.requireStepUpToReplaceAuthenticator(userId, challengeSession(), code))
                .isInstanceOf(UnauthorizedException.class);

        verify(enrollmentRepository, never()).save(any());
        verify(auditEventLogService).logEvent(argThat(e ->
                e.getEventType() == AuditEventType.MFA_FAILURE
                        && e.getEventDescription().contains("challenge")
                        && !e.getEventDescription().contains(code)));
    }

    @Test
    void stepUp_fullSessionWithoutACode_isRefused() {
        verifiedAuthenticator();

        assertThatThrownBy(() ->
                mfaService.requireStepUpToReplaceAuthenticator(userId, fullSession(), " "))
                .isInstanceOf(UnauthorizedException.class);
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    void stepUp_fullSessionWithAWrongCode_isRefused() {
        verifiedAuthenticator();

        assertThatThrownBy(() ->
                mfaService.requireStepUpToReplaceAuthenticator(userId, fullSession(), "ABCDEF"))
                .isInstanceOf(UnauthorizedException.class);
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    void stepUp_fullSessionWithACurrentCode_passes() throws Exception {
        verifiedAuthenticator();

        mfaService.requireStepUpToReplaceAuthenticator(userId, fullSession(), currentCode());

        verifyNoInteractions(auditEventLogService);
    }
}
