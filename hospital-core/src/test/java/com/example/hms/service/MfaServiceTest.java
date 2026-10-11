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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    @Mock private UserMfaEnrollmentRepository enrollmentRepository;
    @Mock private MfaBackupCodeRepository backupCodeRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private com.example.hms.security.LoginAttemptStore attemptStore;

    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private MfaService mfaService;

    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        mfaService = new MfaService(enrollmentRepository, backupCodeRepository, passwordEncoder, auditEventLogService,
                attemptStore);
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

    /** A row login does not ask for: the challenge may enrol, and the enrolment simply restarts. */
    private void assertTheChallengeRestarts(UserMfaEnrollment existing) {
        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(existing));
        when(enrollmentRepository.save(any(UserMfaEnrollment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(backupCodeRepository.save(any(MfaBackupCode.class))).thenAnswer(inv -> inv.getArgument(0));

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(testUser, challengeSession(), null);

        assertThat(result.replacing()).isFalse();
        assertThat(existing.getTotpSecret()).isEqualTo(result.secret()).isNotEqualTo("OLDSECRET");
        assertThat(existing.isVerified()).isFalse();
        assertThat(existing.getPendingTotpSecret()).isNull();
        verify(backupCodeRepository).deleteAllByUserId(userId);
    }

    @Test
    void enrollTotp_aDisabledVerifiedAuthenticator_isNotActive_soTheChallengeMayRestartIt() {
        assertTheChallengeRestarts(UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).totpSecret("OLDSECRET")
                .verified(true).enabled(false).build());
    }

    @Test
    void enrollTotp_aLegacyHalfReplacedRow_isNotActive_soTheChallengeMayRestartIt() {
        assertTheChallengeRestarts(UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).totpSecret("OLDSECRET")
                .verified(false).enabled(false).lastVerifiedAt(java.time.LocalDateTime.now().minusDays(2)).build());
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
    void aMatchingBackupCode_isFoundThenSpentOnce() {
        activeAuthenticator();
        String rawCode = "12345678";
        String hashed = passwordEncoder.encode(rawCode);

        MfaBackupCode code = MfaBackupCode.builder()
                .userId(userId).codeHash(hashed).used(false).build();
        ReflectionTestUtils.setField(code, "id", UUID.randomUUID());

        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId))
                .thenReturn(List.of(code));
        when(backupCodeRepository.markUsed(eq(code.getId()), any())).thenReturn(1);

        assertThat(mfaService.matchingBackupCode(userId, rawCode)).contains(code.getId());
        assertThat(mfaService.spendBackupCode(code.getId())).isTrue();
        verify(backupCodeRepository).markUsed(eq(code.getId()), any());
    }

    @Test
    void aWrongBackupCode_matchesNothing() {
        activeAuthenticator();
        MfaBackupCode code = MfaBackupCode.builder()
                .userId(userId).codeHash(passwordEncoder.encode("99999999")).used(false).build();

        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId))
                .thenReturn(List.of(code));

        assertThat(mfaService.matchingBackupCode(userId, "00000000")).isEmpty();
        verify(backupCodeRepository, never()).markUsed(any(), any());
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
    void verifyEnrollment_alreadyVerified_isNoCodeOracle() throws Exception {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(true)
                .totpSecret(SECRET).build();

        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        // The same answer for a wrong and a current code: this endpoint never
        // tells a (possibly stolen) session whether a guess is current, which
        // would bypass the step-up's wrong-code lockout. Nothing changes.
        assertThat(mfaService.verifyEnrollment(userId, "000000", fullSession())).isTrue();
        assertThat(mfaService.verifyEnrollment(userId, codeFor(SECRET), fullSession())).isTrue();
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void verifyEnrollment_alreadyVerified_fromTheLoginChallenge_isRefused() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(true)
                .totpSecret(SECRET).build();
        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        // The password alone learns nothing about the active authenticator here.
        refusedWith(MfaStepUpRefusedException.Reason.CHALLENGE,
                () -> mfaService.verifyEnrollment(userId, "000000", challengeSession()));
    }

    @Test
    void currentTotpStep_noEnrollment_isEmpty() {
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.empty());

        assertThat(mfaService.currentTotpStep(userId, "123456")).isEmpty();
    }

    @Test
    void currentTotpStep_notVerified_isEmpty() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(false).enabled(true)
                .totpSecret("SECRET").build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.currentTotpStep(userId, "123456")).isEmpty();
    }

    @Test
    void currentTotpStep_notEnabled_isEmpty() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(false)
                .totpSecret("SECRET").build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.currentTotpStep(userId, "123456")).isEmpty();
    }

    @Test
    void noBackupCodesLeft_matchesNothing() {
        activeAuthenticator();
        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId))
                .thenReturn(List.of());

        assertThat(mfaService.matchingBackupCode(userId, "12345678")).isEmpty();
    }

    @Test
    void isMfaEnabled_notEnabled_returnsFalse() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(false).build();

        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        assertThat(mfaService.isMfaEnabled(userId)).isFalse();
    }

    // ── The step-up and the replacement of an active authenticator ──

    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
    private static final String NEW_SECRET = "KRSXG5CTMVRXEZLUKRSXG5CTMVRXEZLU";

    /** The row as the step-up reads it (no lock) and as enrolment reads it (locked). */
    private UserMfaEnrollment activeAuthenticator() {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).totpSecret(SECRET)
                .verified(true).enabled(true).build();
        lenient().when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));
        lenient().when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));
        lenient().when(enrollmentRepository.claimTotpStep(eq(userId), eq(MfaMethodType.TOTP), anyLong(), any()))
                .thenReturn(1);
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
        backup.setId(UUID.randomUUID());
        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId)).thenReturn(List.of(backup));
        when(backupCodeRepository.markUsed(eq(backup.getId()), any())).thenReturn(1);

        assertThat(mfaService.requireStepUp(userId, "12345678", "test action", true)).isTrue();

        verify(backupCodeRepository).markUsed(eq(backup.getId()), any());
        verify(attemptStore).clear(MfaService.STEP_UP_KEY_PREFIX + userId);
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
    void stepUp_wrongCode_isRefusedAsInvalid_countedAndNeverAudited() {
        activeAuthenticator();

        refusedWith(MfaStepUpRefusedException.Reason.INVALID,
                () -> mfaService.requireStepUp(userId, "ABCDEF", "test action", true));

        verify(auditEventLogService).logEvent(argThat(e -> !e.getEventDescription().contains("ABCDEF")));
        verify(attemptStore).recordFailure(MfaService.STEP_UP_KEY_PREFIX + userId,
                MfaService.STEP_UP_MAX_FAILURES, MfaService.STEP_UP_WINDOW_MS, MfaService.STEP_UP_LOCK_MS);
    }

    @Test
    void stepUp_whileLocked_isRefusedWithoutCheckingAnything() throws Exception {
        activeAuthenticator();
        when(attemptStore.remainingLockMs(MfaService.STEP_UP_KEY_PREFIX + userId)).thenReturn(60_000L);
        String code = codeFor(SECRET);

        refusedWith(MfaStepUpRefusedException.Reason.LOCKED,
                () -> mfaService.requireStepUp(userId, code, "test action", true));

        verifyNoInteractions(backupCodeRepository);
        verify(attemptStore, never()).recordFailure(any(), anyInt(), anyLong(), anyLong());
    }

    @Test
    void stepUp_aSixDigitGuess_neverReachesBCrypt() {
        activeAuthenticator();

        refusedWith(MfaStepUpRefusedException.Reason.INVALID,
                () -> mfaService.requireStepUp(userId, "000000", "test action", true));

        verifyNoInteractions(backupCodeRepository);
    }

    @Test
    void stepUp_noAuthenticator_strictRefuses_nonStrictLetsTheCallerAuditTheBypass() {
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP)).thenReturn(Optional.empty());

        refusedWith(MfaStepUpRefusedException.Reason.NOT_ENROLLED,
                () -> mfaService.requireStepUp(userId, null, "test action", true));
        assertThat(mfaService.requireStepUp(userId, null, "test action", false)).isFalse();
    }

    @Test
    void stepUp_lookupFailure_isRefusedAsUnavailable() {
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenThrow(new IllegalStateException("db down"));

        refusedWith(MfaStepUpRefusedException.Reason.UNAVAILABLE,
                () -> mfaService.requireStepUp(userId, "123456", "test action", false));
    }

    @Test
    void stepUp_noActor_isRefusedAsUnidentified_andAuditedUnderTheCaller() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(fullSession());
        try {
            refusedWith(MfaStepUpRefusedException.Reason.UNIDENTIFIED,
                    () -> mfaService.requireStepUp(null, "123456", "test action", false));

            verify(auditEventLogService).logEvent(argThat(e -> e.getUserId() == null
                    && "test@example.com".equals(e.getUserName())
                    && e.getEventType() == AuditEventType.MFA_FAILURE
                    && e.getEventDescription().contains("mfa.stepup.unidentified")));
            verifyNoInteractions(enrollmentRepository);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
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
        activeAuthenticator();
        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId)).thenReturn(List.of());

        assertThat(mfaService.matchingBackupCode(userId, "12345678")).isEmpty();

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

    @Test
    void changingTheMfaRecords_ofAnInactiveAuthenticator_needsNoStepUp() {
        UserMfaEnrollment enrollment = activeAuthenticator();
        enrollment.setEnabled(false);

        mfaService.requireStepUpToChangeAuthenticator(userId, fullSession(), null);

        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void stepUp_ofAnActorWhoseAuthenticatorIsInactive_followsStrictMode() {
        UserMfaEnrollment enrollment = activeAuthenticator();
        enrollment.setEnabled(false);

        // Non-strict: the caller audits the bypass. Strict: refused, not enrolled.
        assertThat(mfaService.requireStepUp(userId, null, "test action", false)).isFalse();
        refusedWith(MfaStepUpRefusedException.Reason.NOT_ENROLLED,
                () -> mfaService.requireStepUp(userId, null, "test action", true));
    }

    @Test
    void stepUp_aReservationBeyondTheBudget_isRefusedWithoutEvaluatingTheCode() throws Exception {
        activeAuthenticator();
        when(attemptStore.recordFailure(MfaService.STEP_UP_KEY_PREFIX + userId, MfaService.STEP_UP_MAX_FAILURES,
                MfaService.STEP_UP_WINDOW_MS, MfaService.STEP_UP_LOCK_MS))
                .thenReturn(MfaService.STEP_UP_MAX_FAILURES + 1);
        String code = codeFor(SECRET);

        refusedWith(MfaStepUpRefusedException.Reason.LOCKED,
                () -> mfaService.requireStepUp(userId, code, "test action", true));

        verify(attemptStore, never()).clear(any());
    }

    @Test
    void stepUp_concurrentGuessesNeverExceedTheBudget() throws Exception {
        MfaService throttled = new MfaService(enrollmentRepository, backupCodeRepository, passwordEncoder,
                auditEventLogService, new com.example.hms.security.InMemoryLoginAttemptStore());
        activeAuthenticator();
        // A backup-code-shaped guess costs a BCrypt check, so the threads truly
        // overlap: without the reservation, every one would pass the lock check
        // before any failure was counted.
        when(backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId)).thenReturn(List.of(
                MfaBackupCode.builder().userId(userId).codeHash(passwordEncoder.encode("99999999")).build()));
        int threads = 20;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ConcurrentLinkedQueue<MfaStepUpRefusedException.Reason> reasons =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        try {
            List<java.util.concurrent.Future<?>> all = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                all.add(pool.submit(() -> {
                    go.await();
                    try {
                        throttled.requireStepUp(userId, "00000000", "test action", true);
                    } catch (MfaStepUpRefusedException ex) {
                        reasons.add(ex.getReason());
                    }
                    return null;
                }));
            }
            go.countDown();
            for (java.util.concurrent.Future<?> f : all) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(reasons).hasSize(threads);
        assertThat(reasons.stream().filter(r -> r == MfaStepUpRefusedException.Reason.INVALID).count())
                .isEqualTo(MfaService.STEP_UP_MAX_FAILURES);
    }

    @Test
    void claimingATotpStep_isATargetedConditionalUpdate_neverASaveOfTheEntityItRead() {
        when(enrollmentRepository.claimTotpStep(eq(userId), eq(MfaMethodType.TOTP), eq(42L), any())).thenReturn(1);

        assertThat(mfaService.claimTotpStep(userId, 42L)).isTrue();

        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void theLoginCheck_writesNothing() throws Exception {
        activeAuthenticator();

        assertThat(mfaService.currentTotpStep(userId, codeFor(SECRET))).isPresent();

        verify(enrollmentRepository, never()).save(any());
        verify(enrollmentRepository, never()).claimTotpStep(any(), any(), anyLong(), any());
    }

    // ── Round 4: a TOTP code is accepted once ──

    @Test
    void theLoginCheck_refusesACodeAtOrBeforeTheLastAcceptedStep() throws Exception {
        UserMfaEnrollment enrollment = activeAuthenticator();
        enrollment.setLastTotpStep(System.currentTimeMillis() / 1000 / 30 + 1);

        assertThat(mfaService.currentTotpStep(userId, codeFor(SECRET))).isEmpty();
    }

    @Test
    void stepUp_aTotpStepAlreadyAccepted_isRefusedAsReused_notAsWrong() throws Exception {
        activeAuthenticator();
        when(enrollmentRepository.claimTotpStep(eq(userId), eq(MfaMethodType.TOTP), anyLong(), any())).thenReturn(0);
        String code = codeFor(SECRET);

        // The code is right but already used (the sign-in a moment ago):
        // "wait for the next code", not "wrong code".
        refusedWith(MfaStepUpRefusedException.Reason.REUSED,
                () -> mfaService.requireStepUp(userId, code, "test action", true));
        // A right code is not a wrong guess: the reserved attempt is given back.
        verify(attemptStore).clear(MfaService.STEP_UP_KEY_PREFIX + userId);
    }

    @Test
    void stepUp_claimsTheStepOfTheCodeItAccepts() throws Exception {
        activeAuthenticator();
        long step = System.currentTimeMillis() / 1000 / 30;
        String code = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6).generate(SECRET, step);

        assertThat(mfaService.requireStepUp(userId, code, "test action", true)).isTrue();

        verify(enrollmentRepository).claimTotpStep(eq(userId), eq(MfaMethodType.TOTP), eq(step), any());
    }

    @Test
    void confirmingTheFirstAuthenticator_leavesTheConfirmingCodeUsableForTheSignInThatFollows() throws Exception {
        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(false).enabled(false)
                .totpSecret(SECRET).build();
        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(enrollment));

        long step = System.currentTimeMillis() / 1000 / 30;
        String code = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6).generate(SECRET, step);

        assertThat(mfaService.verifyEnrollment(userId, code, fullSession())).isTrue();

        // The portal sends the user straight to sign-in, where the code still
        // on screen must work: confirming does not claim its step.
        assertThat(enrollment.getLastTotpStep()).isNull();
    }

    @Test
    void theLoginVerify_hasItsOwnBudget_soAPasswordOnlyAttackerCannotLockTheStepUps() {
        // The step-up budget is spent; the login one is not affected, and
        // spending the login one never touches the step-up key.
        lenient().when(attemptStore.remainingLockMs(MfaService.STEP_UP_KEY_PREFIX + userId)).thenReturn(60_000L);
        when(attemptStore.remainingLockMs(MfaService.LOGIN_CODE_KEY_PREFIX + userId)).thenReturn(0L);
        when(attemptStore.recordFailure(eq(MfaService.LOGIN_CODE_KEY_PREFIX + userId), anyInt(), anyLong(), anyLong()))
                .thenReturn(1);

        assertThat(mfaService.reserveCodeAttempt(userId)).isTrue();

        verify(attemptStore, never()).recordFailure(eq(MfaService.STEP_UP_KEY_PREFIX + userId),
                anyInt(), anyLong(), anyLong());
    }

    @Test
    void aLockedLoginBudget_refusesTheLoginCode() {
        when(attemptStore.remainingLockMs(MfaService.LOGIN_CODE_KEY_PREFIX + userId)).thenReturn(60_000L);

        assertThat(mfaService.reserveCodeAttempt(userId)).isFalse();
    }

    // ── Round 7 ──

    @Test
    void leftoverBackupCodes_ofAnInactiveFactor_doNotSignIn() {
        UserMfaEnrollment disabled = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(true).enabled(false)
                .totpSecret(SECRET).build();
        when(enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(disabled));

        // The one active-factor rule: no active authenticator, no stand-in.
        assertThat(mfaService.matchingBackupCode(userId, "12345678")).isEmpty();
        verify(backupCodeRepository, never()).findByUserIdAndUsedFalseAndPendingFalse(any());
    }

    @Test
    void restartingAnEnrolment_forgetsTheOldSecretsAcceptedStep() {
        UserMfaEnrollment inactive = UserMfaEnrollment.builder()
                .user(testUser).method(MfaMethodType.TOTP).verified(false).enabled(false)
                .totpSecret(SECRET).lastTotpStep(Long.MAX_VALUE).build();
        when(enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP))
                .thenReturn(Optional.of(inactive));
        lenient().when(backupCodeRepository.save(any(MfaBackupCode.class))).thenAnswer(inv -> inv.getArgument(0));

        mfaService.enrollTotp(testUser, fullSession(), null);

        assertThat(inactive.getLastTotpStep()).isNull();
    }
}
