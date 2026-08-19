package com.example.hms.service;

import com.example.hms.enums.MfaMethodType;
import com.example.hms.model.MfaBackupCode;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private final UserMfaEnrollmentRepository enrollmentRepository;
    private final MfaBackupCodeRepository backupCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator(32);
    private final CodeVerifier codeVerifier;

    public MfaService(UserMfaEnrollmentRepository enrollmentRepository,
                      MfaBackupCodeRepository backupCodeRepository,
                      PasswordEncoder passwordEncoder) {
        this.enrollmentRepository = enrollmentRepository;
        this.backupCodeRepository = backupCodeRepository;
        this.passwordEncoder = passwordEncoder;

        TimeProvider timeProvider = new SystemTimeProvider();
        CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        this.codeVerifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        // Allow ±1 time step (30 seconds each direction) for clock skew
        ((DefaultCodeVerifier) this.codeVerifier).setAllowedTimePeriodDiscrepancy(1);
    }

    // ───── Enrollment ─────

    /**
     * Starts TOTP enrollment for a user. Returns the secret and QR data URI.
     * Does NOT mark enrollment as verified yet.
     */
    @Transactional
    public MfaEnrollmentResult enrollTotp(User user) {
        UUID userId = user.getId();
        String username = user.getUsername();

        // If already enrolled for TOTP, reset it
        Optional<UserMfaEnrollment> existing =
                enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP);

        String secret = secretGenerator.generate();

        UserMfaEnrollment enrollment;
        if (existing.isPresent()) {
            enrollment = existing.get();
            enrollment.setTotpSecret(secret);
            enrollment.setVerified(false);
            enrollment.setEnabled(false);
            enrollment.setEnrolledAt(LocalDateTime.now());
        } else {
            enrollment = UserMfaEnrollment.builder()
                    .user(user)
                    .method(MfaMethodType.TOTP)
                    .totpSecret(secret)
                    .enabled(false)
                    .verified(false)
                    .primaryFactor(false)
                    .enrolledAt(LocalDateTime.now())
                    .build();
        }

        enrollmentRepository.save(enrollment);

        // Generate backup codes (deletes any old ones)
        List<String> rawBackupCodes = generateBackupCodes(userId);

        // Build QR otpauth URI
        QrData qrData = new QrData.Builder()
                .label(username)
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();

        log.info("[MFA] TOTP enrollment started for user={}", userId);

        String qrCodeDataUrl = generateQrCodeDataUrl(qrData.getUri());

        return new MfaEnrollmentResult(
                enrollment.getId(),
                secret,
                qrData.getUri(),
                qrCodeDataUrl,
                rawBackupCodes
        );
    }

    /**
     * Verifies the first TOTP code during enrollment to confirm the user's authenticator is set up.
     *
     * @return true if verification succeeded
     */
    @Transactional
    public boolean verifyEnrollment(UUID userId, String code) {
        Optional<UserMfaEnrollment> opt =
                enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP);

        if (opt.isEmpty()) {
            log.warn("[MFA] No TOTP enrollment found for user={}", userId);
            return false;
        }

        UserMfaEnrollment enrollment = opt.get();
        if (enrollment.isVerified()) {
            log.warn("[MFA] TOTP already verified for user={}", userId);
            return true;
        }

        boolean valid = codeVerifier.isValidCode(enrollment.getTotpSecret(), code);
        if (valid) {
            enrollment.setVerified(true);
            enrollment.setEnabled(true);
            enrollment.setPrimaryFactor(true);
            enrollment.setLastVerifiedAt(LocalDateTime.now());
            enrollmentRepository.save(enrollment);
            log.info("[MFA] TOTP enrollment verified for user={}", userId);
        } else {
            log.warn("[MFA] Invalid TOTP code during enrollment for user={}", userId);
        }
        return valid;
    }

    // ───── Verification (login flow) ─────

    /**
     * Verifies a TOTP code during login.
     */
    @Transactional
    public boolean verifyCode(UUID userId, String code) {
        Optional<UserMfaEnrollment> opt =
                enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP);

        if (opt.isEmpty() || !opt.get().isVerified() || !opt.get().isEnabled()) {
            return false;
        }

        UserMfaEnrollment enrollment = opt.get();
        boolean valid = codeVerifier.isValidCode(enrollment.getTotpSecret(), code);
        if (valid) {
            enrollment.setLastVerifiedAt(LocalDateTime.now());
            enrollmentRepository.save(enrollment);
        }
        return valid;
    }

    /**
     * Verifies a backup code during login. Consumes the code on success.
     */
    @Transactional
    public boolean verifyBackupCode(UUID userId, String rawCode) {
        List<MfaBackupCode> unusedCodes = backupCodeRepository.findByUserIdAndUsedFalse(userId);

        for (MfaBackupCode bc : unusedCodes) {
            if (passwordEncoder.matches(rawCode, bc.getCodeHash())) {
                bc.setUsed(true);
                bc.setUsedAt(LocalDateTime.now());
                backupCodeRepository.save(bc);
                log.info("[MFA] Backup code consumed for user={}, remaining={}",
                        userId, unusedCodes.size() - 1);
                return true;
            }
        }
        return false;
    }

    // ───── Queries ─────

    /**
     * Checks if the user has MFA (TOTP) enrolled and verified.
     */
    public boolean isMfaEnabled(UUID userId) {
        Optional<UserMfaEnrollment> opt =
                enrollmentRepository.findByUserIdAndMethod(userId, MfaMethodType.TOTP);
        return opt.isPresent() && opt.get().isVerified() && opt.get().isEnabled();
    }

    /**
     * Checks if MFA is required for a given role based on config.
     */
    public boolean isMfaRequiredForRole(String roleName, List<String> requiredRoles) {
        return requiredRoles.contains(roleName);
    }

    // ───── Backup codes ─────

    @Transactional
    public List<String> regenerateBackupCodes(UUID userId) {
        return generateBackupCodes(userId);
    }

    private List<String> generateBackupCodes(UUID userId) {
        // Delete existing codes
        backupCodeRepository.deleteAllByUserId(userId);

        SecureRandom random = SECURE_RANDOM;
        List<String> rawCodes = new ArrayList<>(BACKUP_CODE_COUNT);

        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            String code = generateRandomCode(random);
            rawCodes.add(code);

            backupCodeRepository.save(MfaBackupCode.builder()
                    .userId(userId)
                    .codeHash(passwordEncoder.encode(code))
                    .used(false)
                    .build());
        }

        log.info("[MFA] Generated {} backup codes for user={}", BACKUP_CODE_COUNT, userId);
        return rawCodes;
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

    public record MfaEnrollmentResult(
            UUID enrollmentId,
            String secret,
            String otpauthUri,
            String qrCodeDataUrl,
            List<String> backupCodes
    ) {}
}
