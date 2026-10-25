package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.PasswordResetToken;
import com.example.hms.model.User;
import com.example.hms.repository.PasswordResetTokenRepository;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.utility.TransactionCallbacks;
import com.example.hms.utility.UserDisplayUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
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
    private final LoginAttemptService loginAttemptService;
    private final AuditEventLogService auditEventLogService;
    private final SmsService smsService;
    private final org.springframework.context.MessageSource messageSource;
    /**
     * The reset-token clock: it writes {@code expiration} and every validity
     * check and the expired-token cleanup read it back. {@code passwordChangedAt}
     * below is NOT on it — it belongs to the password-rotation aggregate
     * ({@code PasswordRotationScheduler}, {@code UserServiceImpl}), which still
     * reads the system clock.
     */
    private final Clock clock;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    private static final int TOKEN_BYTES = 32; // 256-bit entropy
    private static final Pattern HEX_64 = Pattern.compile("^[0-9a-fA-F]{64}$");
    /** QA D787: a phone number as typed: digits, an optional leading +, spaces, dots, dashes, brackets. */
    private static final Pattern PHONE_SHAPED = Pattern.compile("[+\\d][\\d\\s().-]*");
    private static final SecureRandom FALLBACK_RANDOM = new SecureRandom();

    /* ===================== ISSUE ===================== */

    @Override
    @Transactional
    public void requestReset(String email, Locale locale) {
        handleResetRequest(email, null);
    }

    @Override
    @Transactional
    public void requestReset(String email, Locale locale, String requestIp) {
        handleResetRequest(email, requestIp);
    }

    @Override
    @Transactional
    public void requestResetByPhone(String phone, Locale locale, String requestIp) {
        User user = findByPhone(phone);
        if (user == null || user.isDeleted()) {
            return;
        }
        issueBySms(user, locale, requestIp);
    }

    @Override
    @Transactional
    public void requestResetByIdentifier(String identifier, Locale locale, String requestIp) {
        String typed = identifier == null ? "" : identifier.trim();
        if (typed.isEmpty()) {
            return;
        }
        boolean typedPhone = !typed.contains("@") && looksLikePhone(typed);
        User user = resolveAccount(typed, typedPhone);
        if (user == null || user.isDeleted()) {
            return;
        }
        boolean hasEmail = user.getEmail() != null && !user.getEmail().isBlank();
        boolean hasPhone = user.getPhoneNumber() != null && !user.getPhoneNumber().isBlank();
        // The channel the holder typed first when it can deliver; then the other one.
        if (typedPhone && hasPhone && smsService.deliversRealSms()) {
            issueBySms(user, locale, requestIp);
        } else if (hasEmail) {
            handleResetRequest(user, requestIp);
        } else if (hasPhone) {
            issueBySms(user, locale, requestIp);
        } else {
            // Ids only. The dialog's answer tells every holder to ask the
            // reception desk when nothing arrives; the desk's force-reset is
            // the way back for an account with no reachable channel.
            log.warn("🔑 Password reset not sent for user {}: no e-mail address or phone number on the account",
                user.getId());
        }
    }

    /**
     * The account an identifier names: an address, a phone number (local or
     * E.164) when the text is shaped like one, else a username. A phone-shaped
     * text that matches no phone is still tried as a username.
     */
    private User resolveAccount(String typed, boolean typedPhone) {
        try {
            return lookUpAccount(typed, typedPhone);
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException ambiguous) {
            // Two accounts share the identifier (a username differing only in case,
            // a shared number): no guess, and the same silent answer as for none.
            log.warn("🔑 Password reset not sent: the identifier matches more than one account");
            return null;
        }
    }

    private User lookUpAccount(String typed, boolean typedPhone) {
        if (typed.contains("@")) {
            return userRepository.findByEmail(typed).orElse(null);
        }
        if (typedPhone) {
            User byPhone = findByPhone(typed);
            if (byPhone != null) {
                return byPhone;
            }
        }
        return userRepository.findByUsernameIgnoreCase(typed).orElse(null);
    }

    private User findByPhone(String phone) {
        String e164 = com.example.hms.utility.PhoneNumbers.toE164(
            phone, com.example.hms.utility.PhoneNumbers.DEFAULT_CALLING_CODE);
        return e164 == null ? null : userRepository.findByPhoneNumber(e164).orElse(null);
    }

    private static boolean looksLikePhone(String typed) {
        String digits = typed.replaceAll("\\D", "");
        return PHONE_SHAPED.matcher(typed).matches() && digits.length() >= 6;
    }

    /** QA D171: the link by SMS. Nothing is issued when no SMS transport delivers for real. */
    private void issueBySms(User user, Locale locale, String requestIp) {
        // The link is a credential: never issue one that would only reach a log line.
        if (!smsService.deliversRealSms()) {
            log.warn("🔑 Password reset by SMS not sent for user {}: no SMS transport configured", user.getId());
            return;
        }
        tokenRepository.deleteByUser_IdAndConsumedAtIsNull(user.getId());
        String rawToken = generateRawToken();
        tokenRepository.save(PasswordResetToken.builder()
            .user(user)
            .tokenHash(sha256Hex(rawToken))
            .expiration(LocalDateTime.now(clock).plusHours(2))
            .ipAddress(requestIp)
            .build());
        String resetLink = frontendBaseUrl + "/reset-password?token=" + rawToken;
        String body = messageSource.getMessage("sms.passwordReset.link", new Object[]{resetLink},
            locale == null ? Locale.FRENCH : locale);
        final UUID requesterId = user.getId();
        final String to = user.getPhoneNumber();
        TransactionCallbacks.afterCommit(() -> {
            // QA D222: a provider failure must not reach the caller — a 400 for a known
            // phone against a 204 for an unknown one enumerates accounts. Log it only,
            // with the user id and the failure type (never the number or the link).
            try {
                smsService.send(to, body);
            } catch (RuntimeException ex) {
                log.warn("🔑 Password reset SMS for user {} failed: {}", requesterId, ex.getClass().getSimpleName());
                return;
            }
            audit(AuditEventType.PASSWORD_RESET_REQUEST, "Password reset link issued by SMS", requesterId);
        });
        log.info("🔑 Password reset link issued by SMS for user {}", requesterId);
    }

    private void handleResetRequest(String email, String requestIp) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("user.notFoundByEmail", email));
        handleResetRequest(user, requestIp);
    }

    /** The link by e-mail, to the account's own address. */
    private void handleResetRequest(User user, String requestIp) {
        // Enforce "one active token per user"
        tokenRepository.deleteByUser_IdAndConsumedAtIsNull(user.getId());

        // Generate raw token (Base64url, no padding) and store only its SHA-256 hash
        String rawToken = generateRawToken();
        String tokenHash = sha256Hex(rawToken);

        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(tokenHash)
            .expiration(LocalDateTime.now(clock).plusHours(2)) // 2h TTL
            .ipAddress(requestIp)
            .build();

        tokenRepository.save(resetToken);

        String resetLink = frontendBaseUrl + "/reset-password?token=" + rawToken;
        try {
            emailService.sendPasswordResetEmail(user.getEmail(), resetLink);
        } catch (RuntimeException ex) {
            // QA D222 sibling: a mail failure must look like any other request.
            log.warn("🔑 Password reset email for user {} failed: {}", user.getId(), ex.getClass().getSimpleName());
        }

        if (log.isDebugEnabled() && shouldLogRawToken()) {
            log.debug("🔑 [DEV ONLY] Raw password reset token for user {}: {}", user.getId(), rawToken);
            log.debug("🔗 [DEV ONLY] Reset link: {}", resetLink);
        }

        // Ids only: never the address, the link or the token.
        final UUID requesterId = user.getId();
        TransactionCallbacks.afterCommit(() -> audit(AuditEventType.PASSWORD_RESET_REQUEST,
            "Password reset link issued", requesterId));

        log.info("🔑 Password reset link issued for user {}", user.getId());
    }

    /* ===================== CONSUME ===================== */

    @Override
    @Transactional
    public void confirmReset(String token, String newPassword) {
        String tokenHash = resolveTokenHash(token);

        PasswordResetToken resetToken = tokenRepository.findByTokenHash(tokenHash)
            .orElseThrow(() -> new ResourceNotFoundException("passwordReset.token.invalid"));

        if (!resetToken.isValidAt(LocalDateTime.now(clock))) {
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
        resetToken.setConsumedAt(LocalDateTime.now(clock));
        tokenRepository.save(resetToken);

        notifyPasswordChanged(user);

        // The holder has just proved they control the account's address: the
        // failed attempts and the lock collected before the reset must not
        // refuse the new password (D18). After commit, so a rollback leaves the
        // lock in place for a password that did not change.
        final UUID resetUserId = user.getId();
        TransactionCallbacks.afterCommit(() -> {
            loginAttemptService.resetAttempts(resetUserId);
            audit(AuditEventType.PASSWORD_RESET_COMPLETE, "Password reset completed", resetUserId);
        });

        log.info("✅ Password successfully reset for user {}", user.getId());
    }

    /**
     * Tell the holder their password changed. QA D787: a phone-only account
     * reset its password by SMS and then the confirmation e-mail to its NULL
     * address threw inside the transaction, so the new password was rolled
     * back and the holder stayed locked out. By e-mail when the account has an
     * address, else by SMS when a real transport is configured; a failed notice
     * never undoes the reset.
     */
    private void notifyPasswordChanged(User user) {
        String email = user.getEmail();
        if (email != null && !email.isBlank()) {
            try {
                emailService.sendPasswordResetConfirmationEmail(email, UserDisplayUtil.resolveDisplayName(user));
            } catch (RuntimeException ex) {
                log.warn("🔑 Password-changed e-mail for user {} failed: {}", user.getId(), ex.getClass().getSimpleName());
            }
            return;
        }
        String phone = user.getPhoneNumber();
        if (phone == null || phone.isBlank() || !smsService.deliversRealSms()) {
            return;
        }
        String body = messageSource.getMessage("sms.passwordReset.changed", null, Locale.FRENCH);
        final UUID userId = user.getId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                smsService.send(phone, body);
            } catch (RuntimeException ex) {
                log.warn("🔑 Password-changed SMS for user {} failed: {}", userId, ex.getClass().getSimpleName());
            }
        });
    }

    /* ===================== HELPERS / OPS ===================== */

    @Override
    @Transactional(readOnly = true)
    public boolean verifyToken(String token) {
        String tokenHash = resolveTokenHash(token);
        return tokenRepository.findByTokenHash(tokenHash)
            .map(t -> t.isValidAt(LocalDateTime.now(clock)))
            .orElse(false);
    }

    @Override
    @Transactional
    public int cleanupExpiredTokens() {
        long deleted = tokenRepository.deleteByExpirationBefore(LocalDateTime.now(clock));
        return Math.toIntExact(deleted);
    }

    @Override
    @Transactional
    public void invalidateAllForUser(UUID userId) {
        tokenRepository.deleteByUser_IdAndConsumedAtIsNull(userId);
        log.info("🧹 Invalidated all active password reset tokens for user {}", userId);
    }

    /* -------------------- local helpers -------------------- */

    private void audit(AuditEventType type, String description, UUID userId) {
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
            .eventType(type)
            .eventDescription(description)
            .userId(userId)
            .resourceId(userId.toString())
            .entityType("USER")
            .status(AuditStatus.SUCCESS)
            .build());
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        try {
            SecureRandom.getInstanceStrong().nextBytes(bytes);
        } catch (java.security.NoSuchAlgorithmException | RuntimeException e) {
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
        } catch (java.security.NoSuchAlgorithmException | RuntimeException e) {
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
