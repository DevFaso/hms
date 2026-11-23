package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientRecipientChallenge;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientRecipientChallengeRepository;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Security-only code, no patient name, appointment, test or other clinical content. */
@Service @RequiredArgsConstructor
public class PatientRecipientChallengeService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PatientRepository patients;
    private final PatientRecipientChallengeRepository challenges;
    private final PasswordEncoder encoder;
    private final SmsService sms;
    private final EmailService email;
    private final MessageSource messages;
    private final Clock clock;
    private final PatientRecipientVerification verification;

    public record View(NotificationChannel channel, boolean verified, LocalDateTime expiresAt) {}

    @Transactional(readOnly = true)
    public List<View> status(UUID patientId) {
        Patient patient = patients.findByIdUnscoped(patientId).orElseThrow(() -> missing(patientId));
        return List.of(NotificationChannel.SMS, NotificationChannel.EMAIL).stream().map(channel -> new View(channel,
            verification.allows(patient, channel, recipient(patient, channel)), null)).toList();
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public View request(UUID patientId, NotificationChannel channel, UUID actor, Locale locale) {
        checkChannel(channel);
        Patient patient = patients.lockByIdUnscoped(patientId).orElseThrow(() -> missing(patientId));
        String recipient = recipient(patient, channel);
        if (recipient == null || recipient.isBlank() || actor == null) throw new BusinessException("recipientVerification.unavailable");
        if (channel == NotificationChannel.SMS && !sms.deliversRealSms()
            || channel == NotificationChannel.EMAIL && !email.deliversRealEmail()) throw new BusinessException("recipientVerification.unavailable");
        LocalDateTime now = LocalDateTime.now(clock);
        PatientRecipientChallenge row = challenges.findByPatientIdAndChannel(patientId, channel).orElseGet(() -> {
            PatientRecipientChallenge created = new PatientRecipientChallenge();
            created.setPatientId(patientId); created.setChannel(channel); return created;
        });
        if (row.getLastRequestedAt() != null && now.isBefore(row.getLastRequestedAt().plusSeconds(60)))
            throw new BusinessException("recipientVerification.cooldown");
        if (row.getWindowStartedAt() == null || !now.isBefore(row.getWindowStartedAt().plusHours(1))) {
            row.setWindowStartedAt(now); row.setRequestCount(0);
        }
        if (row.getRequestCount() >= 5) throw new BusinessException("recipientVerification.limit");
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        row.setRecipient(recipient); row.setCodeHash(encoder.encode(code)); row.setAttempts(0);
        row.setExpiresAt(now.plusMinutes(10)); row.setRequestedBy(actor); row.setLastRequestedAt(now);
        row.setRequestCount(row.getRequestCount() + 1);
        challenges.saveAndFlush(row);
        String body = messages.getMessage("recipientVerification.code", new Object[] {code}, locale);
        // Synchronous security send: no clinical data, no logging fallback, no retry after ambiguous delivery.
        try {
            if (channel == NotificationChannel.SMS) sms.send(recipient, body);
            else email.sendHtml(List.of(recipient), List.of(), List.of(),
                messages.getMessage("recipientVerification.subject", null, locale), org.springframework.web.util.HtmlUtils.htmlEscape(body));
        } catch (RuntimeException failure) {
            row.setCodeHash(null); challenges.save(row);
            throw new BusinessException("recipientVerification.unavailable");
        }
        return new View(channel, false, row.getExpiresAt());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public View confirm(UUID patientId, NotificationChannel channel, UUID actor, String code) {
        checkChannel(channel);
        Patient patient = patients.lockByIdUnscoped(patientId).orElseThrow(() -> missing(patientId));
        PatientRecipientChallenge row = challenges.findByPatientIdAndChannel(patientId, channel)
            .orElseThrow(() -> new BusinessException("recipientVerification.invalid"));
        LocalDateTime now = LocalDateTime.now(clock);
        if (!java.util.Objects.equals(actor, row.getRequestedBy()) || row.getCodeHash() == null
            || row.getAttempts() >= 5 || row.getExpiresAt() == null || !now.isBefore(row.getExpiresAt())
            || !PatientRecipientVerification.normalize(row.getRecipient(), channel).equals(
                PatientRecipientVerification.normalize(recipient(patient, channel), channel)))
            throw new BusinessException("recipientVerification.invalid");
        row.setAttempts(row.getAttempts() + 1);
        if (code == null || !code.matches("[0-9]{6}") || !encoder.matches(code, row.getCodeHash())) {
            challenges.save(row);
            throw new BusinessException("recipientVerification.invalid");
        }
        row.setCodeHash(null); row.setRecipient(null); challenges.save(row);
        if (channel == NotificationChannel.SMS) {
            patient.setVerifiedSmsRecipient(patient.getPhoneNumberPrimary()); patient.setPhoneVerifiedAt(now);
        } else patient.setVerifiedEmailRecipient(patient.getEmail());
        patients.save(patient);
        return new View(channel, true, null);
    }

    private static String recipient(Patient patient, NotificationChannel channel) {
        return channel == NotificationChannel.SMS ? patient.getPhoneNumberPrimary() : patient.getEmail();
    }
    private static void checkChannel(NotificationChannel channel) {
        if (channel != NotificationChannel.SMS && channel != NotificationChannel.EMAIL) throw new BusinessException("recipientVerification.invalid");
    }
    private static ResourceNotFoundException missing(UUID id) { return new ResourceNotFoundException("patient.notFound", id); }
}
