package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.MfaMethodType;
import com.example.hms.exception.MfaStepUpRefusedException;
import com.example.hms.model.MfaBackupCode;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.security.LoginAttemptStore;
import com.example.hms.security.MfaChallengeScope;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

import com.example.hms.model.User;

/**
 * Handles TOTP MFA enrollment, verification, and backup code management.
 */
@Slf4j
@Service
public class MfaService {

    private static final String ISSUER = "HMS";
    private static final int BACKUP_CODE_COUNT = 10;
    private static final int BACKUP_CODE_LENGTH = 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String ACTION_REPLACE = "authenticator replacement";

    /**
     * Wrong second-factor codes per user before a short lockout, shared by the
     * login MFA verify and every step-up, kept in the login throttle's store
     * (Redis when wired, so a lock holds on every instance).
     */
    static final int STEP_UP_MAX_FAILURES = 5;
    static final long STEP_UP_WINDOW_MS = 15 * 60 * 1000L;
    static final long STEP_UP_LOCK_MS = 15 * 60 * 1000L;
    static final String STEP_UP_KEY_PREFIX = "stepup:";
    /** A TOTP code is six digits, a backup code eight: BCrypt only ever runs on the latter. */
    private static final Pattern TOTP_SHAPE = Pattern.compile("\\d{6}");
    private static final Pattern BACKUP_CODE_SHAPE = Pattern.compile("\\d{" + BACKUP_CODE_LENGTH + "}");

    private final UserMfaEnrollmentRepository enrollmentRepository;
    private final MfaBackupCodeRepository backupCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventLogService auditEventLogService;
    private final LoginAttemptStore attemptStore;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator(32);
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
    private final TimeProvider timeProvider = new SystemTimeProvider();
    /** TOTP period in seconds, and the steps either side accepted for clock skew. */
    private static final int TOTP_PERIOD = 30;
    private static final int TOTP_SKEW_STEPS = 1;

    public MfaService(UserMfaEnrollmentRepository enrollmentRepository,
                      MfaBackupCodeRepository backupCodeRepository,
                      PasswordEncoder passwordEncoder,
                      AuditEventLogService auditEventLogService,
                      LoginAttemptStore attemptStore) {
        this.enrollmentRepository = enrollmentRepository;
        this.backupCodeRepository = backupCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditEventLogService = auditEventLogService;
        this.attemptStore = attemptStore;
    }

    // ───── Enrollment ─────

    /**
     * Starts a TOTP enrolment, or the replacement of the current authenticator.
     * The gate and the change share one transaction and one locked row read, so
     * nothing can change the row between the check and the write.
     *
     * <p>One rule decides, the one login uses for {@code mfaEnrolled}: does
     * the user have an ACTIVE second factor ({@link #isMfaEnabled}, verified
     * and enabled)? A factor login does not ask for protects nothing, so a
     * row that is not active never blocks anything here.
     *
     * <ul>
     *   <li>No active factor (none yet, one never confirmed, a disabled one, a
     *       replacement left half done before this rule): enrolment is open,
     *       from the login's MFA challenge token too, and simply restarts with
     *       a fresh secret and backup codes.</li>
     *   <li>An active factor: replacing it needs a full session, never the
     *       challenge token, and the step-up against it ({@link #requireStepUp}).
     *       The new secret and backup codes stay PENDING: the current
     *       authenticator and its backup codes keep working until
     *       {@link #verifyEnrollment} confirms a code from the new one and swaps
     *       them, so a replacement left half done changes nothing.</li>
     * </ul>
     *
     * @throws MfaStepUpRefusedException (403) when replacing without the step-up; audited
     */
    @Transactional
    public MfaEnrollmentResult enrollTotp(User user, Authentication caller, String stepUpCode) {
        UUID userId = user.getId();
        Optional<UserMfaEnrollment> existing =
                enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP);
        boolean replacing = existing.filter(MfaService::isActive).isPresent();

        String secret = secretGenerator.generate();
        UserMfaEnrollment enrollment;
        if (replacing) {
            enrollment = existing.get();
            requireStepUpToReplace(enrollment, userId, caller, stepUpCode);
            enrollment.setPendingTotpSecret(secret);
        } else {
            enrollment = existing.orElseGet(() -> UserMfaEnrollment.builder()
                    .user(user)
                    .method(MfaMethodType.TOTP)
                    .primaryFactor(false)
                    .build());
            enrollment.setTotpSecret(secret);
            enrollment.setPendingTotpSecret(null);
            enrollment.setVerified(false);
            enrollment.setEnabled(false);
            enrollment.setEnrolledAt(now());
        }
        enrollmentRepository.save(enrollment);

        List<String> rawBackupCodes = generateBackupCodes(userId, replacing);

        QrData qrData = new QrData.Builder()
                .label(user.getUsername())
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();

        log.info(replacing
                ? "[MFA] TOTP replacement started (pending until confirmed) for user={}"
                : "[MFA] TOTP enrollment started for user={}", userId);

        return new MfaEnrollmentResult(
                enrollment.getId(),
                secret,
                qrData.getUri(),
                generateQrCodeDataUrl(qrData.getUri()),
                rawBackupCodes,
                replacing
        );
    }

    /**
     * Confirms an enrolment with a code from the new authenticator: the first
     * one, or a pending replacement, which then takes the place of the current
     * one (secret and backup codes) in this transaction. Only a full session
     * confirms a replacement: the session that started it.
     *
     * @return true if the code confirmed it (or the authenticator was already confirmed)
     * @throws MfaStepUpRefusedException (403) when the login's MFA challenge token
     *         tries to confirm a replacement
     */
    @Transactional
    public boolean verifyEnrollment(UUID userId, String code, Authentication caller) {
        Optional<UserMfaEnrollment> opt =
                enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP);

        if (opt.isEmpty()) {
            log.warn("[MFA] No TOTP enrollment found for user={}", userId);
            return false;
        }

        UserMfaEnrollment enrollment = opt.get();
        if (isActive(enrollment) && enrollment.getPendingTotpSecret() != null) {
            if (MfaChallengeScope.isChallengeSession(caller)) {
                throw refused(userId, caller, ACTION_REPLACE, MfaStepUpRefusedException.Reason.CHALLENGE);
            }
            OptionalLong confirmStep = matchingTotpStep(enrollment.getPendingTotpSecret(), code);
            if (confirmStep.isEmpty()) {
                log.warn("[MFA] Invalid TOTP code confirming a replacement for user={}", userId);
                return false;
            }
            LocalDateTime confirmedAt = now();
            // The confirming code is spent: it cannot also sign in.
            enrollment.setLastTotpStep(confirmStep.getAsLong());
            enrollment.setTotpSecret(enrollment.getPendingTotpSecret());
            enrollment.setPendingTotpSecret(null);
            enrollment.setVerified(true);
            enrollment.setEnabled(true);
            enrollment.setPrimaryFactor(true);
            enrollment.setEnrolledAt(confirmedAt);
            enrollment.setLastVerifiedAt(confirmedAt);
            enrollmentRepository.save(enrollment);
            promotePendingBackupCodes(userId);
            log.info("[MFA] TOTP authenticator replaced for user={}", userId);
            return true;
        }

        if (enrollment.isVerified()) {
            log.warn("[MFA] TOTP already verified for user={}", userId);
            return true;
        }

        OptionalLong firstStep = matchingTotpStep(enrollment.getTotpSecret(), code);
        boolean valid = firstStep.isPresent();
        if (valid) {
            // The confirming code is spent: it cannot also sign in.
            enrollment.setLastTotpStep(firstStep.getAsLong());
            enrollment.setVerified(true);
            enrollment.setEnabled(true);
            enrollment.setPrimaryFactor(true);
            enrollment.setPendingTotpSecret(null);
            enrollment.setLastVerifiedAt(now());
            enrollmentRepository.save(enrollment);
            log.info("[MFA] TOTP enrollment verified for user={}", userId);
        } else {
            log.warn("[MFA] Invalid TOTP code during enrollment for user={}", userId);
        }
        return valid;
    }

    // ───── Step-up ─────

    /**
     * The MFA step-up for a sensitive action ({@code X-Mfa-Token}): a current
     * code from the actor's active authenticator, or one of their unused backup
     * codes, which it consumes, so a lost phone is recoverable. Shared by the
     * authenticator replacement, the tenant lifecycle and the emergency controls.
     *
     * @param strict for an actor with no active authenticator: refuse
     *               ({@code true}) or let the action through ({@code false}),
     *               in which case the caller audits the bypass
     * @return {@code true} when a code verified; {@code false} only for an actor
     *         with no active authenticator when {@code strict} is off
     * <p>Its own short transaction, and no row lock: a caller's long
     * transaction (a lifecycle change, an emergency control) never holds the
     * actor's MFA row, so the actor can still sign in meanwhile. The one write,
     * spending a backup code, is a conditional update that cannot spend a
     * code twice.
     *
     * @throws MfaStepUpRefusedException (403) on refusal, audited as {@code MFA_FAILURE}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean requireStepUp(UUID userId, String code, String action, boolean strict) {
        if (userId == null) {
            throw refused(null, null, action, MfaStepUpRefusedException.Reason.UNIDENTIFIED);
        }
        Optional<UserMfaEnrollment> enrollment;
        try {
            enrollment = enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP);
        } catch (RuntimeException ex) {
            log.warn("[MFA] Step-up lookup failed for user={}: {}", userId, ex.getMessage());
            throw refused(userId, null, action, MfaStepUpRefusedException.Reason.UNAVAILABLE);
        }
        if (enrollment.filter(MfaService::isActive).isEmpty()) {
            if (strict) {
                throw refused(userId, null, action, MfaStepUpRefusedException.Reason.NOT_ENROLLED);
            }
            return false;
        }
        checkStepUpCode(enrollment.get(), userId, null, code, action);
        return true;
    }

    /**
     * The guard on {@code PUT /auth/credentials/mfa}, which can disable or
     * delete the authenticator: with an active one, the same rule as a
     * replacement. Joins the caller's transaction and locks the row, so the
     * change it guards runs on what it checked.
     *
     * @throws MfaStepUpRefusedException (403); audited
     */
    @Transactional
    public void requireStepUpToChangeAuthenticator(UUID userId, Authentication caller, String stepUpCode) {
        Optional<UserMfaEnrollment> enrollment =
                enrollmentRepository.lockByUserIdAndMethod(userId, MfaMethodType.TOTP);
        if (enrollment.filter(MfaService::isActive).isPresent()) {
            requireStepUpToReplace(enrollment.get(), userId, caller, stepUpCode);
        }
    }

    /** A full session (never the challenge token) and the step-up, against the row already read. */
    private void requireStepUpToReplace(UserMfaEnrollment enrollment, UUID userId,
                                        Authentication caller, String stepUpCode) {
        if (MfaChallengeScope.isChallengeSession(caller)) {
            throw refused(userId, caller, ACTION_REPLACE, MfaStepUpRefusedException.Reason.CHALLENGE);
        }
        checkStepUpCode(enrollment, userId, caller, stepUpCode, ACTION_REPLACE);
    }

    /**
     * A current TOTP code from the active authenticator, or an unused backup
     * code. The attempt is reserved BEFORE the code is evaluated (the store
     * counts it atomically), so concurrent guesses cannot exceed
     * {@value #STEP_UP_MAX_FAILURES} per window whatever the endpoint; a code
     * that verifies clears the count.
     */
    private void checkStepUpCode(UserMfaEnrollment enrollment, UUID userId, Authentication caller,
                                 String code, String action) {
        if (!StringUtils.hasText(code)) {
            throw refused(userId, caller, action, MfaStepUpRefusedException.Reason.REQUIRED);
        }
        if (!reserveCodeAttempt(userId)) {
            throw refused(userId, caller, action, MfaStepUpRefusedException.Reason.LOCKED);
        }
        String candidate = code.trim();
        if (claimCurrentTotp(enrollment, userId, candidate) || consumeBackupCode(userId, candidate)) {
            clearCodeAttempts(userId);
            return;
        }
        throw refused(userId, caller, action, MfaStepUpRefusedException.Reason.INVALID);
    }

    /** A current code of the active secret, accepted once: its time-step is claimed atomically. */
    private boolean claimCurrentTotp(UserMfaEnrollment enrollment, UUID userId, String candidate) {
        OptionalLong step = matchingTotpStep(enrollment.getTotpSecret(), candidate);
        if (step.isEmpty() || !claimTotpStep(userId, step.getAsLong())) {
            return false;
        }
        // The row may be managed here (the replacement's locked read): keep it
        // in step with the claim, so its own save (DynamicUpdate) agrees.
        enrollment.setLastTotpStep(step.getAsLong());
        return true;
    }

    /**
     * Reserves one second-factor attempt for the user BEFORE the code is
     * evaluated: the store counts it atomically, so concurrent guesses cannot
     * exceed {@value #STEP_UP_MAX_FAILURES} per window, at login or at any
     * step-up. {@code false}: the user is locked out for now. A code that
     * verifies clears the count ({@link #clearCodeAttempts}).
     */
    public boolean reserveCodeAttempt(UUID userId) {
        String throttleKey = STEP_UP_KEY_PREFIX + userId;
        return attemptStore.remainingLockMs(throttleKey) <= 0
                && attemptStore.recordFailure(throttleKey, STEP_UP_MAX_FAILURES, STEP_UP_WINDOW_MS,
                        STEP_UP_LOCK_MS) <= STEP_UP_MAX_FAILURES;
    }

    /** A second-factor code verified: the user's failure count starts again. */
    public void clearCodeAttempts(UUID userId) {
        attemptStore.clear(STEP_UP_KEY_PREFIX + userId);
    }

    /** Audits the refusal (never the code) and returns the exception to throw. */
    private MfaStepUpRefusedException refused(UUID userId, Authentication caller, String action,
                                              MfaStepUpRefusedException.Reason reason) {
        Authentication actor = caller != null ? caller : SecurityContextHolder.getContext().getAuthentication();
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(userId)
                    .userName(actor == null ? null : actor.getName())
                    .eventType(AuditEventType.MFA_FAILURE)
                    .status(AuditStatus.FAILURE)
                    .eventDescription("MFA step-up for " + action + " refused: " + reason.code())
                    .build());
        } catch (RuntimeException ex) {
            log.error("[MFA] Failed to audit a refused step-up for user={}", userId, ex);
        }
        return new MfaStepUpRefusedException(reason);
    }

    // ───── Verification (login flow) ─────

    /**
     * The TOTP time-step the code matches on the user's active authenticator,
     * when it is a current code newer than the last one accepted. Writes
     * nothing: the sign-in claims the step ({@link #claimTotpStep}) only once
     * it has claimed the challenge.
     */
    @Transactional(readOnly = true)
    public OptionalLong currentTotpStep(UUID userId, String code) {
        return enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP)
                .filter(MfaService::isActive)
                .map(enrollment -> newerThanLast(enrollment,
                        matchingTotpStep(enrollment.getTotpSecret(), code)))
                .orElse(OptionalLong.empty());
    }

    private static OptionalLong newerThanLast(UserMfaEnrollment enrollment, OptionalLong step) {
        Long last = enrollment.getLastTotpStep();
        return step.isPresent() && last != null && step.getAsLong() <= last ? OptionalLong.empty() : step;
    }

    /**
     * Accepts the code of this time-step once: a conditional update that sets
     * {@code last_totp_step} (and stamps {@code lastVerifiedAt}) only when the
     * step is newer than the last accepted one. Targeted columns, never a save
     * of an entity read earlier. {@code false}: replayed, or used concurrently.
     */
    @Transactional
    public boolean claimTotpStep(UUID userId, long step) {
        return enrollmentRepository.claimTotpStep(userId, MfaMethodType.TOTP, step, now()) == 1;
    }

    /**
     * The id of an unused backup code of the current set that matches, without
     * spending it ({@link #spendBackupCode}). Only a backup-code-shaped input
     * reaches BCrypt.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> matchingBackupCode(UUID userId, String rawCode) {
        if (rawCode == null || !BACKUP_CODE_SHAPE.matcher(rawCode.trim()).matches()) {
            return Optional.empty();
        }
        String candidate = rawCode.trim();
        return backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId).stream()
                .filter(bc -> passwordEncoder.matches(candidate, bc.getCodeHash()))
                .map(MfaBackupCode::getId)
                .findFirst();
    }

    /** Spends a backup code; {@code false} when another request spent it first. */
    @Transactional
    public boolean spendBackupCode(UUID codeId) {
        return backupCodeRepository.markUsed(codeId, now()) == 1;
    }

    /**
     * An unused backup code of the current set (never a pending one) matches:
     * spend it. Only a backup-code-shaped input reaches BCrypt, and the spend
     * is a conditional update, so two requests cannot both use one code.
     */
    private boolean consumeBackupCode(UUID userId, String rawCode) {
        if (rawCode == null || !BACKUP_CODE_SHAPE.matcher(rawCode.trim()).matches()) {
            return false;
        }
        String candidate = rawCode.trim();
        List<MfaBackupCode> unusedCodes = backupCodeRepository.findByUserIdAndUsedFalseAndPendingFalse(userId);

        for (MfaBackupCode bc : unusedCodes) {
            if (passwordEncoder.matches(candidate, bc.getCodeHash())) {
                if (backupCodeRepository.markUsed(bc.getId(), now()) != 1) {
                    return false;
                }
                log.info("[MFA] Backup code consumed for user={}, remaining={}",
                        userId, unusedCodes.size() - 1);
                return true;
            }
        }
        return false;
    }

    /**
     * The time-step (epoch seconds / 30) whose code matches, within one step
     * of clock skew either side, compared in constant time. Only a six-digit
     * input is tried.
     */
    private OptionalLong matchingTotpStep(String secret, String code) {
        if (!StringUtils.hasText(secret) || code == null || !TOTP_SHAPE.matcher(code.trim()).matches()) {
            return OptionalLong.empty();
        }
        byte[] candidate = code.trim().getBytes(StandardCharsets.US_ASCII);
        long current = Math.floorDiv(timeProvider.getTime(), TOTP_PERIOD);
        try {
            for (long step = current - TOTP_SKEW_STEPS; step <= current + TOTP_SKEW_STEPS; step++) {
                byte[] expected = codeGenerator.generate(secret, step).getBytes(StandardCharsets.US_ASCII);
                if (MessageDigest.isEqual(expected, candidate)) {
                    return OptionalLong.of(step);
                }
            }
        } catch (CodeGenerationException ex) {
            log.warn("[MFA] TOTP code generation failed: {}", ex.getMessage());
        }
        return OptionalLong.empty();
    }

    /** Server-local time, as every MFA timestamp has always been stored. */
    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.systemDefault());
    }

    /**
     * The second factor login asks for: a TOTP authenticator, verified and
     * enabled. The one rule for login ({@code mfaEnrolled}), for whether the
     * challenge may enrol, and for whether a step-up needs a code. The portal
     * reads it from the credential DTO ({@code active}).
     */
    public static boolean isActive(UserMfaEnrollment enrollment) {
        return enrollment.getMethod() == MfaMethodType.TOTP
                && enrollment.isVerified() && enrollment.isEnabled();
    }

    // ───── Queries ─────

    /**
     * Checks if the user has MFA (TOTP) enrolled and verified.
     */
    public boolean isMfaEnabled(UUID userId) {
        Optional<UserMfaEnrollment> opt =
                enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP);
        return opt.isPresent() && isActive(opt.get());
    }

    /**
     * Checks if MFA is required for a given role based on config.
     */
    public boolean isMfaRequiredForRole(String roleName, List<String> requiredRoles) {
        return requiredRoles.contains(roleName);
    }

    // ───── Backup codes ─────

    /**
     * A fresh set of backup codes. {@code pending}: issued with a replacement
     * that is not confirmed yet, so they replace only an earlier pending set
     * and the current codes keep working.
     */
    private List<String> generateBackupCodes(UUID userId, boolean pending) {
        if (pending) {
            backupCodeRepository.deleteAll(backupCodeRepository.findByUserIdAndPending(userId, true));
        } else {
            backupCodeRepository.deleteAllByUserId(userId);
        }

        SecureRandom random = SECURE_RANDOM;
        List<String> rawCodes = new ArrayList<>(BACKUP_CODE_COUNT);

        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            String code = generateRandomCode(random);
            rawCodes.add(code);

            backupCodeRepository.save(MfaBackupCode.builder()
                    .userId(userId)
                    .codeHash(passwordEncoder.encode(code))
                    .used(false)
                    .pending(pending)
                    .build());
        }

        log.info("[MFA] Generated {} backup codes for user={} pending={}", BACKUP_CODE_COUNT, userId, pending);
        return rawCodes;
    }

    /** A replacement was confirmed: its backup codes replace the current set. */
    private void promotePendingBackupCodes(UUID userId) {
        backupCodeRepository.deleteAll(backupCodeRepository.findByUserIdAndPending(userId, false));
        for (MfaBackupCode code : backupCodeRepository.findByUserIdAndPending(userId, true)) {
            code.setPending(false);
            backupCodeRepository.save(code);
        }
    }

    private String generateRandomCode(SecureRandom random) {
        StringBuilder sb = new StringBuilder(BACKUP_CODE_LENGTH);
        for (int i = 0; i < BACKUP_CODE_LENGTH; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * Generate a QR code as a base64 data URL using ZXing — no external service needed.
     */
    private String generateQrCodeDataUrl(String otpauthUri) {
        try {
            var hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            var bitMatrix = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(otpauthUri, com.google.zxing.BarcodeFormat.QR_CODE, 200, 200, hints);
            var image = com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(bitMatrix);
            var baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "PNG", baos);
            String base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
            return "data:image/png;base64," + base64;
        } catch (Exception e) {
            log.warn("[MFA] Failed to generate QR code locally, returning empty", e);
            return "";
        }
    }

    // ───── Result record ─────

    /**
     * {@code replacing}: a pending replacement of an active authenticator,
     * which keeps working until the replacement is confirmed.
     */
    public record MfaEnrollmentResult(
            UUID enrollmentId,
            String secret,
            String otpauthUri,
            String qrCodeDataUrl,
            List<String> backupCodes,
            boolean replacing
    ) {}
}
