package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.PasswordResetToken;
import com.example.hms.model.User;
import com.example.hms.repository.PasswordResetTokenRepository;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    private static final int TOKEN_BYTES = 32; // 256-bit entropy
    private static final Pattern HEX_64 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final SecureRandom FALLBACK_RANDOM = new SecureRandom();

    /* ===================== ISSUE ===================== */

    @Override
    @Transactional
    public void requestReset(String email, Locale locale) {
        handleResetRequest(email, locale, null);
    }

    @Override
    @Transactional
    public void requestReset(String email, Locale locale, String requestIp) {
        handleResetRequest(email, locale, requestIp);
    }

    private void handleResetRequest(String email, Locale locale, String requestIp) {
        if (log.isTraceEnabled()) {
            log.trace("Password reset requested for {} (locale={}, ip={})", email, locale, requestIp);
        }
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));

        // Enforce "one active token per user"
        tokenRepository.deleteByUser_IdAndConsumedAtIsNull(user.getId());

        // Generate raw token (Base64url, no padding) and store only its SHA-256 hash
        String rawToken = generateRawToken();
        String tokenHash = sha256Hex(rawToken);

        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(tokenHash)
            .expiration(LocalDateTime.now().plusHours(2)) // 2h TTL
            .ipAddress(requestIp)
            .build();

        tokenRepository.save(resetToken);

        String resetLink = "https://yourapp.com/reset-password?token=" + rawToken;
        emailService.sendPasswordResetEmail(user.getEmail(), resetLink);

        if (log.isDebugEnabled() && shouldLogRawToken()) {
            log.debug("🔑 [DEV ONLY] Raw password reset token for {}: {}", user.getEmail(), rawToken);
            log.debug("🔗 [DEV ONLY] Reset link: {}", resetLink);
        }

        log.info("🔑 Password reset link issued for: {}", user.getEmail());
    }

    /* ===================== CONSUME ===================== */

    @Override
    @Transactional
    public void confirmReset(String token, String newPassword) {
        String tokenHash = resolveTokenHash(token);

        PasswordResetToken resetToken = tokenRepository.findByTokenHash(tokenHash)
            .orElseThrow(() -> new ResourceNotFoundException("Invalid or expired reset token."));

        if (!resetToken.isValid()) {
            throw new IllegalStateException("Reset token is invalid (expired or already used).");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setForcePasswordChange(false);
    user.setPasswordChangedAt(LocalDateTime.now());
    user.setPasswordRotationWarningAt(null);
    user.setPasswordRotationForcedAt(null);
        userRepository.save(user);

        // Mark token consumed (preserve audit trail)
        resetToken.setConsumedAt(LocalDateTime.now());
        tokenRepository.save(resetToken);

        log.info("✅ Password successfully reset for user: {}", user.getEmail());
    }

    /* ===================== HELPERS / OPS ===================== */

    @Override
    @Transactional(readOnly = true)
    public boolean verifyToken(String token) {
        String tokenHash = resolveTokenHash(token);
        return tokenRepository.findByTokenHash(tokenHash)
            .map(PasswordResetToken::isValid)
            .orElse(false);
    }

    @Override
    @Transactional
    public int cleanupExpiredTokens() {
        long deleted = tokenRepository.deleteByExpirationBefore(LocalDateTime.now());
        return Math.toIntExact(deleted);
    }

    @Override
    @Transactional
    public void invalidateAllForUser(UUID userId) {
        tokenRepository.deleteByUser_IdAndConsumedAtIsNull(userId);
        log.info("🧹 Invalidated all active password reset tokens for user {}", userId);
    }

    /* -------------------- local helpers -------------------- */

    private static String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        try {
            SecureRandom.getInstanceStrong().nextBytes(bytes);
        } catch (Exception e) {
            FALLBACK_RANDOM.nextBytes(bytes);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash token", e);
        }
    }

    private String resolveTokenHash(String presentedToken) {
        Objects.requireNonNull(presentedToken, "Token must not be null");
        if (HEX_64.matcher(presentedToken).matches()) {
            if (log.isDebugEnabled()) {
                log.debug("[PasswordReset] Accepting legacy pre-hashed token input");
            }
            return presentedToken.toLowerCase(Locale.ROOT);
        }
        return sha256Hex(presentedToken);
    }

    private boolean shouldLogRawToken() {
        String profileProp = System.getProperty("spring.profiles.active", "");
        String profileEnv = System.getenv("SPRING_PROFILES_ACTIVE");
        String combined = (profileProp + "," + (profileEnv != null ? profileEnv : "")).toLowerCase();
        return !combined.contains("prod");
    }
}
