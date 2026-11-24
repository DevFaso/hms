package com.example.hms.service.notification;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.AuditEventLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * QA D2203: the desk records a patient's channel choices (SMS and e-mail, per kind of notice) on the patient's
 * record. The same rows the patient sets in the portal ({@code notification_preferences}, keyed by the patient's
 * account, which every patient has, activated or not) and the same rule {@link PatientChannelConsent} applies to
 * every send: an explicit disabled row turns the channel off, no row means on.
 *
 * <p>Only what a sender reads is offered ({@link #DESK_TYPES} x {@link #DESK_CHANNELS}); the in-app bell stays the
 * patient's own choice in the portal and is never touched here. The patient must be registered at the desk's
 * hospital (404 otherwise, as for the record). Every change is audited with the kinds and channels changed.
 */
@Service
@RequiredArgsConstructor
public class PatientChannelPreferenceService {

    /** The kinds of notice a sender reads: exactly the preferences {@link PatientNotificationEvent} names. */
    public static final Set<NotificationType> DESK_TYPES = Arrays.stream(PatientNotificationEvent.values())
        .map(PatientNotificationEvent::preferenceType)
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(NotificationType.class)));

    /** The channels the desk records: the two a patient without the app receives. */
    public static final Set<NotificationChannel> DESK_CHANNELS =
        EnumSet.of(NotificationChannel.SMS, NotificationChannel.EMAIL);

    private static final String PATIENT_NOT_FOUND = "patient.notFound";

    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final com.example.hms.repository.UserRepository userRepository;
    private final AuditEventLogService auditEventLogService;

    /** Every desk kind x channel, on unless the patient (or the desk) turned it off. */
    @Transactional(readOnly = true)
    public List<NotificationPreferenceDTO> read(UUID patientId, UUID hospitalId) {
        User account = accountAt(patientId, hospitalId);
        return NotificationPreferenceViews.describe(matrix(stored(account.getId())), userRepository);
    }

    /**
     * Records the desk's choices. Only desk kinds and channels are written; any other entry is ignored and the
     * other rows of the account (the bell, anything else) are kept as they are.
     */
    @Transactional
    public List<NotificationPreferenceDTO> update(UUID patientId, UUID hospitalId,
                                                  List<NotificationPreferenceUpdateDTO> updates, UUID actorId) {
        if (actorId == null) throw new ResourceNotFoundException("user.notFound", actorId);
        User account = accountAt(patientId, hospitalId);
        userRepository.lockForNotificationPreferences(account.getId())
            .orElseThrow(() -> new ResourceNotFoundException("user.notFound", account.getId()));
        // Only the desk's kinds and channels are recorded here; anything else (the bell, a kind no
        // sender reads) is left as it is, never written: the response shows what is recorded.
        List<NotificationPreferenceUpdateDTO> wanted = updates == null ? List.of() : updates.stream()
            .filter(Objects::nonNull)
            .filter(update -> DESK_TYPES.contains(update.getNotificationType())
                && DESK_CHANNELS.contains(update.getChannel()))
            .toList();
        Map<NotificationType, Map<NotificationChannel, NotificationPreference>> rows = stored(account.getId());
        List<NotificationPreference> toSave = new ArrayList<>();
        Set<String> changes = new LinkedHashSet<>();
        for (NotificationPreferenceUpdateDTO update : wanted) {
            boolean enabled = Boolean.TRUE.equals(update.getEnabled());
            NotificationPreference row = rows
                .computeIfAbsent(update.getNotificationType(), type -> new EnumMap<>(NotificationChannel.class))
                .get(update.getChannel());
            boolean current = row == null || row.isEnabled();
            if (current == enabled && row != null) {
                continue;
            }
            if (row == null) {
                row = NotificationPreference.builder().user(account).notificationType(update.getNotificationType())
                    .channel(update.getChannel()).build();
                rows.get(update.getNotificationType()).put(update.getChannel(), row);
            }
            row.recordChoice(enabled, actorId, java.time.LocalDateTime.now());
            toSave.add(row);
            if (current != enabled) {
                changes.add(update.getNotificationType().name() + "/" + update.getChannel().name() + " "
                    + (enabled ? "on" : "off"));
            }
        }
        preferenceRepository.saveAll(toSave);
        if (!changes.isEmpty()) {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(actorId)
                .patientId(patientId)
                .hospitalId(hospitalId)
                .eventType(AuditEventType.PATIENT_CHANNEL_PREFERENCES_UPDATED)
                .status(AuditStatus.SUCCESS)
                .entityType("PATIENT")
                .resourceId(patientId.toString())
                .eventDescription("Patient channel choices recorded at the desk: " + String.join(", ", changes))
                .build());
        }
        return NotificationPreferenceViews.describe(matrix(rows), userRepository);
    }

    /** The patient's account, when the patient is registered at the desk's hospital; 404 otherwise. */
    private User accountAt(UUID patientId, UUID hospitalId) {
        Patient patient = patientId == null ? null : patientRepository.findByIdUnscoped(patientId).orElse(null);
        if (patient == null || hospitalId == null || patient.getUser() == null
                || !registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)) {
            throw new ResourceNotFoundException(PATIENT_NOT_FOUND, patientId);
        }
        return patient.getUser();
    }

    private Map<NotificationType, Map<NotificationChannel, NotificationPreference>> stored(UUID userId) {
        Map<NotificationType, Map<NotificationChannel, NotificationPreference>> rows =
            new EnumMap<>(NotificationType.class);
        for (NotificationPreference row : preferenceRepository.findByUser_Id(userId)) {
            if (row.getNotificationType() != null && row.getChannel() != null) {
                rows.computeIfAbsent(row.getNotificationType(), type -> new EnumMap<>(NotificationChannel.class))
                    .putIfAbsent(row.getChannel(), row);
            }
        }
        return rows;
    }

    private static List<NotificationPreferenceDTO> matrix(
            Map<NotificationType, Map<NotificationChannel, NotificationPreference>> rows) {
        List<NotificationPreferenceDTO> out = new ArrayList<>();
        DESK_TYPES.stream().sorted(Comparator.comparing(Enum::ordinal)).forEach(type -> DESK_CHANNELS.stream()
            .sorted(Comparator.comparing(Enum::ordinal))
            .forEach(channel -> {
                NotificationPreference row = rows.getOrDefault(type, Map.of()).get(channel);
                out.add(NotificationPreferenceDTO.builder()
                    .id(row == null ? null : row.getId())
                    .notificationType(type)
                    .channel(channel)
                    .enabled(row == null || row.isEnabled())
                    .recordedByUserId(row == null ? null : row.getRecordedByUserId())
                    .recordedAt(row == null ? null : row.getRecordedAt())
                    .build());
            }));
        return out.stream().filter(Objects::nonNull).toList();
    }
}
