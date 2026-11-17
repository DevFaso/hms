package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.MfaMethodType;
import com.example.hms.enums.RecoveryContactType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.NotificationTransportUnavailableException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.User;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.model.UserRecoveryContact;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.credential.UserCredentialHealthDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentRequestDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactRequestDTO;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRecoveryContactRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.integration.IkoddiGateway;
import com.example.hms.service.integration.SmsRecipientAllowlist;
import com.example.hms.service.integration.SmsRecipientNotAllowedException;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.PhoneNumbers;
import com.example.hms.utility.SmsCountryPolicy;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserCredentialLifecycleServiceImpl implements UserCredentialLifecycleService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int VERIFICATION_CODE_LENGTH = 6;
    private static final int VERIFICATION_CODE_EXPIRY_MINUTES = 15;
    private static final int MAX_VERIFICATION_ATTEMPTS = 5;
    /** Campaign tag of the recovery-phone code SMS (provider-side reporting only). */
    static final String RECOVERY_SMS_CAMPAIGN = "recovery-contact-code";
    private static final String CONTACT_VALUE_FIELD = "contactValue";

    private final UserRepository userRepository;
    private final UserMfaEnrollmentRepository mfaEnrollmentRepository;
    private final UserRecoveryContactRepository recoveryContactRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final MfaService mfaService;
    private final AuditEventLogService auditEventLogService;
    /**
     * The SMS transport the phone-verification codes use (Orange, or IKODDI
     * when selected). Its {@code isConfigured()} is the same signal the desk's
     * "verify phone" availability reads (D2000).
     */
    private final IkoddiGateway smsGateway;
    private final MessageSource messageSource;
    /** Country code a local number takes, exactly as the SMS gateway applies it. */
    @Value("${app.ikoddi.country-number-code:226}")
    private String smsCountryNumberCode;
    /** The same switch as the other super-admin step-ups (emergency controls, impersonation). */
    @Value("${hms.support-impersonation.require-mfa-strict:false}")
    private boolean requireMfaStrict;
    /**
     * Writes the recovery-code expiry and reads it back on verify; also stamps
     * {@code lastLoginAt}/{@code verifiedAt}, which nothing compares in time.
     */
    private final Clock clock;
    /**
     * D2000: when each user's recovery codes were sent in the last hour, for the
     * hourly cap. In memory per instance, like the {@code RateLimitFilter}
     * buckets in front of the route; the per-contact cooldown is read from the
     * stored code's expiry instead, so it holds across instances.
     */
    private final Map<UUID, Deque<LocalDateTime>> recoverySendsByUser = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public void recordSuccessfulLogin(UUID userId) {
        if (userId == null) {
            return;
        }
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastLoginAt(LocalDateTime.now(clock));
            userRepository.save(user);
        });
    }

    @Override
    @Transactional
    public List<UserCredentialHealthDTO> listCredentialHealth() {
        List<User> users = userRepository.findByIsDeletedFalse();
        if (users.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<UserMfaEnrollment>> enrollmentMap =
            mfaEnrollmentRepository.findAll().stream()
                .collect(Collectors.groupingBy(e -> e.getUser().getId()));

        Map<UUID, List<UserRecoveryContact>> contactMap =
            recoveryContactRepository.findAll().stream()
                .collect(Collectors.groupingBy(c -> c.getUser().getId()));

        return users.stream()
            .map(user -> toHealthDto(user,
                enrollmentMap.getOrDefault(user.getId(), List.of()),
                contactMap.getOrDefault(user.getId(), List.of())))
            .sorted(Comparator
                .comparing(UserCredentialHealthDTO::isForcePasswordChange).reversed()
                .thenComparing(UserCredentialHealthDTO::getLastLoginAt,
                    Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(UserCredentialHealthDTO::getUsername, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    @Override
    @Transactional
    public UserCredentialHealthDTO getCredentialHealth(UUID userId) {
        User user = userRepository.findByIdWithRolesAndProfiles(userId)
            .orElseThrow(() -> new ResourceNotFoundException("user.notFound", userId));
        List<UserMfaEnrollment> enrollments = mfaEnrollmentRepository.findByUserId(userId);
        List<UserRecoveryContact> contacts = recoveryContactRepository.findByUserId(userId);
        return toHealthDto(user, enrollments, contacts);
    }

    @Override
    @Transactional
    public List<UserMfaEnrollmentDTO> upsertMfaEnrollmentsAsSuperAdmin(UUID actorId, UUID userId,
                                                                       List<UserMfaEnrollmentRequestDTO> payload,
                                                                       String stepUpCode) {
        // Validate first: the step-up commits on its own (a backup code is
        // spent), so a request that would fail anyway must fail before it.
        resolveUser(userId);
        requireAtMostOneTotp(payload);
        if (!mfaService.requireStepUp(actorId, stepUpCode, "MFA enrolment change", requireMfaStrict)) {
            auditMfaChangeBypass(actorId, userId);
        }
        return upsertMfaEnrollments(userId, payload);
    }

    /**
     * Only confirming a code turns a TOTP authenticator on
     * (MfaService#verifyEnrollment): this list may switch one off, never back
     * on, or it would revive a secret nobody has shown a code from since, as
     * the login factor, with no code at all.
     */
    private static boolean mayEnable(UserMfaEnrollmentRequestDTO dto, UserMfaEnrollment enrollment) {
        return dto.isEnabled()
            && (dto.getMethod() != MfaMethodType.TOTP || enrollment.getId() == null || enrollment.isEnabled());
    }

    /**
     * One TOTP row per user (V183), and no entry listed twice (same method and
     * channel): a bad request, checked before any step-up spends a code, never
     * a unique-index violation or a map collision after it.
     */
    private void requireAtMostOneTotp(List<UserMfaEnrollmentRequestDTO> payload) {
        if (payload == null) {
            return;
        }
        if (payload.stream().filter(dto -> dto != null && dto.getMethod() == MfaMethodType.TOTP).count() > 1) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.mfa.totpOnce"));
        }
        Set<String> keys = new java.util.HashSet<>();
        for (UserMfaEnrollmentRequestDTO dto : payload) {
            if (dto != null && dto.getMethod() != null && !keys.add(mfaKey(dto))) {
                throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.mfa.methodOnce"));
            }
        }
    }

    private void auditMfaChangeBypass(UUID actorId, UUID userId) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(actorId)
                .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
                .status(AuditStatus.SUCCESS)
                .resourceId(userId == null ? null : userId.toString())
                .eventDescription("MFA enrolment change performed without MFA step-up "
                    + "(actor not enrolled, non-strict mode).")
                .build());
        } catch (RuntimeException ex) {
            log.error("[MFA] Failed to audit an MFA-change bypass by actor={}", actorId, ex);
        }
    }

    /**
     * The write itself, behind both guarded entry points: never exposed on the
     * interface, so no caller reaches it without a step-up.
     */
    @Transactional
    List<UserMfaEnrollmentDTO> upsertMfaEnrollments(UUID userId, List<UserMfaEnrollmentRequestDTO> payload) {
        User user = resolveUser(userId);

        List<UserMfaEnrollment> existing = mfaEnrollmentRepository.findByUserId(userId);
        Map<String, UserMfaEnrollment> existingByKey = existing.stream()
            .collect(Collectors.toMap(this::mfaKey, enrollment -> enrollment));

        Set<String> requestedKeys = payload == null ? Set.of() : payload.stream()
            .filter(Objects::nonNull)
            .map(this::mfaKey)
            .collect(Collectors.toSet());

        requireAtMostOneTotp(payload);

        List<UserMfaEnrollment> toPersist = new ArrayList<>();
        if (payload != null) {
            for (UserMfaEnrollmentRequestDTO dto : payload) {
                if (dto == null || dto.getMethod() == null) {
                    continue;
                }
                String key = mfaKey(dto);
                UserMfaEnrollment enrollment = existingByKey.getOrDefault(key, UserMfaEnrollment.builder().user(user).build());
                enrollment.setUser(user);
                enrollment.setMethod(dto.getMethod());
                enrollment.setChannel(normalize(dto.getChannel()));
                enrollment.setEnabled(mayEnable(dto, enrollment));
                enrollment.setPrimaryFactor(dto.isPrimaryFactor());
                enrollment.setEnrolledAt(dto.getEnrolledAt());
                // Never from the client: lastVerifiedAt (like verified) records
                // a code that verified. An existing row keeps its own.
                toPersist.add(enrollment);
            }
        }

        // ensure only one primary factor remains
        reconcilePrimaryFlag(toPersist, UserMfaEnrollment::isPrimaryFactor, UserMfaEnrollment::setPrimaryFactor);

        List<UserMfaEnrollment> saved = mfaEnrollmentRepository.saveAll(toPersist);

        // remove obsolete
        for (UserMfaEnrollment enrollment : existing) {
            if (!requestedKeys.contains(mfaKey(enrollment))) {
                mfaEnrollmentRepository.delete(enrollment);
            }
        }

        return saved.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public List<UserMfaEnrollmentDTO> upsertOwnMfaEnrollments(UUID userId, List<UserMfaEnrollmentRequestDTO> payload,
                                                              Authentication caller, String stepUpCode) {
        mfaService.requireStepUpToChangeAuthenticator(userId, caller, stepUpCode);
        return upsertMfaEnrollments(userId, payload);
    }

    @Override
    @Transactional
    public List<UserRecoveryContactDTO> upsertRecoveryContacts(UUID userId, List<UserRecoveryContactRequestDTO> payload) {
        User user = resolveUser(userId);

        List<UserRecoveryContact> existing = recoveryContactRepository.findByUserId(userId);
        Map<String, UserRecoveryContact> existingByKey = existing.stream()
            .collect(Collectors.toMap(
                this::recoveryKey,
                contact -> contact,
                (left, right) -> left,
                LinkedHashMap::new
            ));

        List<UserRecoveryContactRequestDTO> sanitizedPayload = payload == null ? List.of() : payload.stream()
            .filter(this::isValidContactRequest)
            .toList();

        Set<String> requestedKeys = sanitizedPayload.stream()
            .map(this::recoveryKey)
            .collect(Collectors.toSet());

        // D2000 (owner request): a NEW recovery contact must not be the email or
        // phone the user signs in with; losing that one is what recovery is for.
        // Contacts already on file are left alone so they stay removable.
        Set<String> keysOnFile = Set.copyOf(existingByKey.keySet());
        for (UserRecoveryContactRequestDTO dto : sanitizedPayload) {
            if (!keysOnFile.contains(recoveryKey(dto)) && isSignInIdentifier(user, dto)) {
                throw new FieldBusinessException(CONTACT_VALUE_FIELD, "users.recovery.sameAsSignIn");
            }
        }

        List<UserRecoveryContact> toPersist = new ArrayList<>(sanitizedPayload.size());
        for (UserRecoveryContactRequestDTO dto : sanitizedPayload) {
            String key = recoveryKey(dto);
            UserRecoveryContact contact = existingByKey.getOrDefault(key, UserRecoveryContact.builder().user(user).build());
            contact.setUser(user);
            contact.setContactType(dto.getContactType());
            contact.setContactValue(dto.getContactValue().trim());
            // Preserve existing verification state — verified flag is only set via the verify endpoint
            if (contact.getId() == null) {
                // New contact — always starts unverified
                contact.setVerified(false);
                contact.setVerifiedAt(null);
            }
            // Existing contacts keep their current verified/verifiedAt values
            contact.setPrimaryContact(dto.isPrimaryContact());
            contact.setNotes(normalize(dto.getNotes()));
            existingByKey.put(key, contact);
            toPersist.add(contact);
        }

        reconcilePrimaryFlag(toPersist, UserRecoveryContact::isPrimaryContact, UserRecoveryContact::setPrimaryContact);

        List<UserRecoveryContact> saved = recoveryContactRepository.saveAll(toPersist);

        for (UserRecoveryContact contact : existing) {
            if (!requestedKeys.contains(recoveryKey(contact))) {
                recoveryContactRepository.delete(contact);
            }
        }

        return saved.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public String sendRecoveryContactVerificationCode(UUID userId, UUID contactId, String confirmedContactValue) {
        UserRecoveryContact contact = recoveryContactRepository.findById(contactId)
            .orElseThrow(() -> new ResourceNotFoundException("recoveryContact.notFound", contactId));

        if (!contact.getUser().getId().equals(userId)) {
            // Someone else's contact answers exactly as an unknown one.
            throw new ResourceNotFoundException("recoveryContact.notFound", contactId);
        }

        if (confirmedContactValue == null || confirmedContactValue.isBlank() || !confirmedContactValue.equals(contact.getContactValue())) {
            throw new FieldBusinessException("confirmedContactValue", "users.recovery.confirmationRequired");
        }

        if (contact.isVerified()) {
            throw new IllegalStateException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.recovery.alreadyVerified"));
        }

        // D2000: a code costs money (SMS) and can harass, so the limits of the
        // desk's phone verification apply: one per contact per cooldown, and an
        // hourly cap per user. Checked before a code exists.
        requireRecoverySendAllowed(userId, contact);

        // D2000: every channel either really sends or refuses BEFORE a code is
        // stored. A type with no channel used to fall through silently and the
        // caller was told "code sent" for a code that never left the server.
        RecoveryContactType type = contact.getContactType();
        String smsTarget = null;
        if (type == RecoveryContactType.PHONE) {
            smsTarget = requireTextablePhone(contact.getContactValue());
        } else if (type != RecoveryContactType.EMAIL) {
            throw new BusinessException("users.recovery.typeNotVerifiable");
        }

        // Generate 6-digit code
        String code = generateVerificationCode();

        // Store hashed code with expiry; a failed send below rolls this back.
        contact.setVerificationCodeHash(passwordEncoder.encode(code));
        contact.setVerificationCodeExpiresAt(LocalDateTime.now(clock).plusMinutes(VERIFICATION_CODE_EXPIRY_MINUTES));
        contact.setVerificationAttempts(0);
        recoveryContactRepository.save(contact);

        if (type == RecoveryContactType.PHONE) {
            sendRecoverySms(contactId, smsTarget, code);
        } else {
            sendRecoveryEmail(contact.getContactValue(), code);
        }

        recordRecoverySend(userId);
        log.info("Verification code sent for recovery contact {} (type={})", contactId, type);
        return contact.getContactType().name();
    }

    @Override
    @Transactional
    public UserRecoveryContactDTO verifyRecoveryContact(UUID userId, UUID contactId, String code) {
        UserRecoveryContact contact = recoveryContactRepository.findById(contactId)
            .orElseThrow(() -> new ResourceNotFoundException("recoveryContact.notFound", contactId));

        if (!contact.getUser().getId().equals(userId)) {
            // Someone else's contact answers exactly as an unknown one.
            throw new ResourceNotFoundException("recoveryContact.notFound", contactId);
        }

        if (contact.isVerified()) {
            throw new IllegalStateException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.recovery.alreadyVerified"));
        }

        if (contact.getVerificationCodeHash() == null) {
            throw new IllegalStateException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.verification.noCodeSent"));
        }

        if (contact.getVerificationAttempts() >= MAX_VERIFICATION_ATTEMPTS) {
            // Reset the code — force resend
            contact.setVerificationCodeHash(null);
            contact.setVerificationCodeExpiresAt(null);
            contact.setVerificationAttempts(0);
            recoveryContactRepository.save(contact);
            throw new IllegalStateException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.verification.tooManyAttempts"));
        }

        if (contact.getVerificationCodeExpiresAt() != null
                && contact.getVerificationCodeExpiresAt().isBefore(LocalDateTime.now(clock))) {
            contact.setVerificationCodeHash(null);
            contact.setVerificationCodeExpiresAt(null);
            contact.setVerificationAttempts(0);
            recoveryContactRepository.save(contact);
            throw new IllegalStateException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.verification.expired"));
        }

        contact.setVerificationAttempts(contact.getVerificationAttempts() + 1);

        if (!passwordEncoder.matches(code, contact.getVerificationCodeHash())) {
            recoveryContactRepository.save(contact);
            int remaining = MAX_VERIFICATION_ATTEMPTS - contact.getVerificationAttempts();
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.verification.invalidCode", remaining));
        }

        // Code matches — mark as verified
        contact.setVerified(true);
        contact.setVerifiedAt(LocalDateTime.now(clock));
        contact.setVerificationCodeHash(null);
        contact.setVerificationCodeExpiresAt(null);
        contact.setVerificationAttempts(0);
        UserRecoveryContact saved = recoveryContactRepository.save(contact);

        log.info("Recovery contact {} verified successfully", contactId);
        return toDto(saved);
    }

    private String generateVerificationCode() {
        StringBuilder sb = new StringBuilder(VERIFICATION_CODE_LENGTH);
        for (int i = 0; i < VERIFICATION_CODE_LENGTH; i++) {
            sb.append(SECURE_RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private User resolveUser(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("user.notFound", userId));
    }

    private UserCredentialHealthDTO toHealthDto(User user,
                                                List<UserMfaEnrollment> enrollments,
                                                List<UserRecoveryContact> contacts) {
        long enrolledCount = enrollments.stream().filter(UserMfaEnrollment::isEnabled).count();
        long verifiedMfa = enrollments.stream()
            .filter(enrollment -> enrollment.getLastVerifiedAt() != null)
            .count();
        boolean hasPrimaryMfa = enrollments.stream().anyMatch(UserMfaEnrollment::isPrimaryFactor);

        long contactCount = contacts.size();
        long verifiedContacts = contacts.stream().filter(UserRecoveryContact::isVerified).count();
        boolean hasPrimaryContact = contacts.stream().anyMatch(UserRecoveryContact::isPrimaryContact);

        return UserCredentialHealthDTO.builder()
            .userId(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .active(user.isActive())
            .forcePasswordChange(user.isForcePasswordChange())
            .forceUsernameChange(user.isForceUsernameChange())
            .lastLoginAt(user.getLastLoginAt())
            .mfaEnrolledCount(enrolledCount)
            .verifiedMfaCount(verifiedMfa)
            .hasPrimaryMfa(hasPrimaryMfa)
            .recoveryContactCount(contactCount)
            .verifiedRecoveryContacts(verifiedContacts)
            .hasPrimaryRecoveryContact(hasPrimaryContact)
            .phoneRecoveryAvailable(smsGateway.isConfigured())
            .mfaEnrollments(enrollments.stream().map(this::toDto).toList())
            .recoveryContacts(contacts.stream().map(this::toDto).toList())
            .build();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * A user has at most one TOTP row (V183's partial unique index), so TOTP is
     * keyed by method alone: a changed channel updates that row instead of
     * inserting a second one before the old is deleted.
     */
    private String mfaKey(UserMfaEnrollment enrollment) {
        return mfaKey(enrollment.getMethod(), enrollment.getChannel());
    }

    private String mfaKey(UserMfaEnrollmentRequestDTO dto) {
        return mfaKey(dto.getMethod(), dto.getChannel());
    }

    private String mfaKey(MfaMethodType method, String channel) {
        return key(method, method == MfaMethodType.TOTP ? null : channel);
    }

    private String recoveryKey(UserRecoveryContact contact) {
        return key(contact.getContactType(), contact.getContactValue());
    }

    private String recoveryKey(UserRecoveryContactRequestDTO dto) {
        return key(dto.getContactType(), dto.getContactValue());
    }

    private String key(Enum<?> type, String value) {
        String normalizedValue = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        String typeCode = type == null ? "unknown" : type.name();
        return typeCode + "::" + normalizedValue;
    }

    private UserMfaEnrollmentDTO toDto(UserMfaEnrollment enrollment) {
        return UserMfaEnrollmentDTO.builder()
            .method(enrollment.getMethod())
            .channel(enrollment.getChannel())
            .enabled(enrollment.isEnabled())
            .primaryFactor(enrollment.isPrimaryFactor())
            .enrolledAt(enrollment.getEnrolledAt())
            .lastVerifiedAt(enrollment.getLastVerifiedAt())
            .active(MfaService.isActive(enrollment))
            .build();
    }

    private UserRecoveryContactDTO toDto(UserRecoveryContact contact) {
        return UserRecoveryContactDTO.builder()
            .id(contact.getId())
            .contactType(contact.getContactType())
            .contactValue(contact.getContactValue())
            .verified(contact.isVerified())
            .verifiedAt(contact.getVerifiedAt())
            .primaryContact(contact.isPrimaryContact())
            .notes(contact.getNotes())
            .build();
    }

    private <T> void reconcilePrimaryFlag(List<T> items,
                                          java.util.function.Predicate<T> isPrimary,
                                          java.util.function.BiConsumer<T, Boolean> setter) {
        if (items == null || items.isEmpty()) {
            return;
        }
        long primaryCount = items.stream().filter(isPrimary).count();
        if (primaryCount <= 1) {
            return;
        }
        // enforce only the most recently updated (last in list) retains primary flag
        for (int i = 0; i < items.size(); i++) {
            boolean keepPrimary = i == items.size() - 1;
            setter.accept(items.get(i), keepPrimary);
        }
    }

    /**
     * 429 when this contact was sent a code less than the desk's cooldown ago
     * (its stored code's expiry minus the code lifetime is when it was sent; a
     * failed send rolled that back), or when the user reached the desk's
     * hourly cap of sends. The limits are PhoneVerificationServiceImpl's.
     */
    private void requireRecoverySendAllowed(UUID userId, UserRecoveryContact contact) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = contact.getVerificationCodeExpiresAt();
        if (expiresAt != null) {
            LocalDateTime sentAt = expiresAt.minusMinutes(VERIFICATION_CODE_EXPIRY_MINUTES);
            if (now.isBefore(sentAt.plusSeconds(PhoneVerificationServiceImpl.RESEND_COOLDOWN_SECONDS))) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "users.recovery.cooldown");
            }
        }
        Deque<LocalDateTime> sends = recoverySendsByUser.get(userId);
        if (sends == null) {
            return;
        }
        synchronized (sends) {
            sends.removeIf(at -> !at.isAfter(now.minusHours(1)));
            if (sends.size() >= PhoneVerificationServiceImpl.MAX_SENDS_PER_USER_PER_HOUR) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "users.recovery.limitReached");
            }
        }
    }

    private void recordRecoverySend(UUID userId) {
        Deque<LocalDateTime> sends = recoverySendsByUser.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (sends) {
            sends.addLast(LocalDateTime.now(clock));
        }
    }

    /**
     * The number in the wire form the SMS gateway uses ({@code +} and the
     * international digits, a local number taking the configured country
     * code). Refuses, before any code exists, when no SMS transport is
     * configured or the number is not one we text.
     */
    private String requireTextablePhone(String raw) {
        if (!smsGateway.isConfigured()) {
            throw new NotificationTransportUnavailableException(MessageUtil.resolveOrRaw("users.recovery.smsUnavailable"));
        }
        String digits = PhoneNumbers.toInternationalDigits(raw, smsCallingCode());
        String e164 = digits.isEmpty() ? "" : "+" + digits;
        if (!SmsCountryPolicy.allows(e164)) {
            throw new FieldBusinessException(CONTACT_VALUE_FIELD, "users.recovery.phoneInvalid");
        }
        return e164;
    }

    /** The code by SMS, in French like every verification code; never logs the code or the full number. */
    private void sendRecoverySms(UUID contactId, String e164, String code) {
        String body = messageSource.getMessage("sms.phoneVerification.code",
            new Object[] {code, VERIFICATION_CODE_EXPIRY_MINUTES}, Locale.FRENCH);
        IkoddiGateway.SmsDispatch dispatch;
        try {
            dispatch = smsGateway.sendSms(List.of(e164), body, RECOVERY_SMS_CAMPAIGN);
        } catch (SmsRecipientNotAllowedException ex) {
            log.warn("Recovery code SMS for contact {} not sent: {} is not on this environment's allowlist",
                contactId, maskPhone(e164));
            throw new BusinessException(SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED);
        } catch (NotificationTransportUnavailableException ex) {
            throw new NotificationTransportUnavailableException(MessageUtil.resolveOrRaw("users.recovery.smsUnavailable"));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            log.warn("Recovery code SMS for contact {} to {} not accepted: {}", contactId, maskPhone(e164), ex.getMessage());
            throw new BusinessException("users.recovery.smsFailed");
        }
        if (dispatch == null || !dispatch.delivered()) {
            log.warn("Recovery code SMS for contact {} to {} not accepted by the provider", contactId, maskPhone(e164));
            throw new BusinessException("users.recovery.smsFailed");
        }
    }

    /** The code by email; "no mail transport" is said in the reader's language, not the transport's English. */
    private void sendRecoveryEmail(String to, String code) {
        try {
            emailService.sendRecoveryContactVerificationEmail(to, code);
        } catch (NotificationTransportUnavailableException ex) {
            throw new NotificationTransportUnavailableException(MessageUtil.resolveOrRaw("users.recovery.emailUnavailable"));
        }
    }

    /** Whether a requested contact is the email or phone this user signs in with. */
    private boolean isSignInIdentifier(User user, UserRecoveryContactRequestDTO dto) {
        String value = dto.getContactValue().trim();
        if (dto.getContactType() == RecoveryContactType.EMAIL) {
            return user.getEmail() != null && user.getEmail().trim().equalsIgnoreCase(value);
        }
        if (dto.getContactType() == RecoveryContactType.PHONE) {
            return PhoneNumbers.isSameSubscriber(user.getPhoneNumber(), value, smsCallingCode());
        }
        return false;
    }

    private String smsCallingCode() {
        return smsCountryNumberCode == null || smsCountryNumberCode.isBlank()
            ? PhoneNumbers.DEFAULT_CALLING_CODE : smsCountryNumberCode.trim();
    }

    /** Display-safe form for logs: a leading '+' and the last two digits. */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "••••";
        }
        boolean plus = phone.startsWith("+");
        return (plus ? "+" : "") + "•".repeat(phone.length() - 2 - (plus ? 1 : 0))
            + phone.substring(phone.length() - 2);
    }

    private boolean isValidContactRequest(UserRecoveryContactRequestDTO dto) {
        if (dto == null || dto.getContactType() == null) {
            return false;
        }
        String value = dto.getContactValue();
        return value != null && !value.trim().isEmpty();
    }
}
