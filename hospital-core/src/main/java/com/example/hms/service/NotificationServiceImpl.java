package com.example.hms.service;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.provider.ProviderCallerResolver;
import com.example.hms.security.provider.ProviderConfinementPolicy;
import java.security.Principal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.hms.model.Notification;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.controller.NotificationWebSocketController;
import com.example.hms.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {
    private final NotificationRepository notificationRepository;
    private final NotificationWebSocketController notificationWebSocketController;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final UserRepository userRepository;
    // Who may flip a broadcast's shared read flag is decided on the caller's
    // live context; an ObjectProvider, so no bean cycle forms through it.
    private final ObjectProvider<ProviderCallerResolver> callerResolverProvider;
    private final com.example.hms.service.i18n.NotificationLocalizer localizer;
    private final com.example.hms.service.notification.ConsentRecorderLabels recorderLabels;
    private final com.example.hms.repository.PatientRepository patientRepository;
    private final AuditEventLogService auditEventLogService;

    @Override
        public List<Notification> getNotificationsForUser(String username) {
            return notificationRepository.findByRecipientUsername(username, Pageable.unpaged(NEWEST_FIRST)).getContent();
    }

    /**
     * QA D278: the list came back in storage order, so today's urgent alert was
     * on the last page. Every reader gets newest first (id breaks a tie so
     * paging is stable), whatever order the caller asked for.
     */
    static final org.springframework.data.domain.Sort NEWEST_FIRST = org.springframework.data.domain.Sort.by(
        org.springframework.data.domain.Sort.Order.desc("createdAt"),
        org.springframework.data.domain.Sort.Order.desc("id"));

    static Pageable newestFirst(Pageable requested) {
        if (requested == null || requested.isUnpaged()) {
            return Pageable.unpaged(NEWEST_FIRST);
        }
        return org.springframework.data.domain.PageRequest.of(
            requested.getPageNumber(), requested.getPageSize(), NEWEST_FIRST);
    }

    @Override
        public Page<Notification> getNotificationsForUser(String username, Boolean read, String search, Pageable requested) {
            Pageable pageable = newestFirst(requested);
            if (search != null && !search.isEmpty()) {
                return searched(username, read, search, pageable);
            } else if (read != null) {
                return notificationRepository.findByRecipientUsernameAndRead(username, read, pageable);
            } else {
                return notificationRepository.findByRecipientUsername(username, pageable);
            }
    }

    /** The search filter looks at the recipient's newest rows, this many at most. */
    static final int SEARCH_SCAN_LIMIT = 2000;

    /**
     * The search filter. The body is encrypted at rest, so the database cannot match it: the recipient's own
     * newest rows are read (decrypted on the way) and matched here, case ignored, then paged.
     */
    private Page<Notification> searched(String username, Boolean read, String search, Pageable pageable) {
        Pageable scan = org.springframework.data.domain.PageRequest.of(0, SEARCH_SCAN_LIMIT, NEWEST_FIRST);
        List<Notification> rows = (read != null
            ? notificationRepository.findByRecipientUsernameAndRead(username, read, scan)
            : notificationRepository.findByRecipientUsername(username, scan)).getContent();
        String needle = search.toLowerCase(java.util.Locale.ROOT);
        List<Notification> hits = rows.stream()
            .filter(row -> row.getMessage() != null
                && row.getMessage().toLowerCase(java.util.Locale.ROOT).contains(needle))
            .toList();
        if (pageable.isUnpaged()) {
            return new org.springframework.data.domain.PageImpl<>(hits, pageable, hits.size());
        }
        int from = (int) Math.min(pageable.getOffset(), hits.size());
        int to = Math.min(from + pageable.getPageSize(), hits.size());
        return new org.springframework.data.domain.PageImpl<>(hits.subList(from, to), pageable, hits.size());
    }

    @Override
    public Notification createNotification(String messageKey, Object[] args, String recipientUsername, String type) {
        return storeKeyed(messageKey, args, recipientUsername, type, null);
    }

    @Override
    public Notification createNotification(String messageKey, Object[] args, String recipientUsername, String type,
                                           UUID subjectId) {
        return storeKeyed(messageKey, args, recipientUsername, type, subjectId);
    }

    private Notification storeKeyed(String messageKey, Object[] args, String recipientUsername, String type,
                                    UUID subjectId) {
        List<Object> stored = com.example.hms.service.i18n.NotificationLocalizer.storable(args);
        String french = localizer.render(messageKey, stored,
            com.example.hms.service.i18n.NotificationLocales.STAFF);
        return store(capped(french), messageKey, stored, recipientUsername, type, subjectId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int markReadForSubject(UUID subjectId, java.util.Collection<String> types) {
        if (subjectId == null || types == null || types.isEmpty()) {
            return 0;
        }
        return notificationRepository.markReadForSubject(subjectId, types);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int withdrawForSubjects(String recipientUsername, String type, java.util.Collection<UUID> subjectIds) {
        if (recipientUsername == null || recipientUsername.isBlank() || type == null
                || subjectIds == null || subjectIds.isEmpty()) {
            return 0;
        }
        return notificationRepository.deleteForRecipientAndSubjects(recipientUsername, type, subjectIds);
    }

    /** {@code security.notifications.message} is VARCHAR(255); a longer body fails the INSERT. */
    static final int MAX_MESSAGE_LENGTH = 255;

    /** A body the column can hold: an overlong argument (a medication name) is cut, never a failed INSERT. */
    static String capped(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }

    @Override
    public Notification createLiteralNotification(String message, String recipientUsername, String type) {
        return store(message, null, null, recipientUsername, type, null);
    }

    @Override
    public Notification localize(Notification notification, java.util.Locale locale) {
        return localizer.localized(notification, locale);
    }

    /**
     * Writes the notification row in the CALLER's transaction and pushes it
     * over STOMP once that transaction commits.
     *
     * <p>The row belongs with whatever prompted it — a critical lab result
     * commits its alert and its {@code criticalNotifiedAt} stamp together, so
     * there is no window where the result is on the chart and the alert is
     * not. The push is different: it is a network hop, and doing it inline
     * held it inside the caller's transaction, which for the lab path means
     * while an order row is pessimistically locked. It also meant a
     * transaction that later rolled back had already told somebody about a
     * result that no longer exists. After the commit, neither is true.
     *
     * <p>With no transaction on the thread {@code TransactionCallbacks} runs
     * the push inline, which is the old behaviour and right for a caller that
     * has nothing to wait for.
     */
    private Notification store(String message, String messageKey, List<Object> messageArgs,
                               String recipientUsername, String type, UUID subjectId) {
        Notification notification = Notification.builder()
                .message(message)
                .messageKey(messageKey)
                .messageArgs(messageArgs)
                .recipientUsername(recipientUsername)
                .type(type)
                .subjectId(subjectId)
                .createdAt(LocalDateTime.now())
                .read(false)
                .build();
        Notification saved = notificationRepository.save(notification);
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            // Guarded, because after the commit there is nothing left to
            // undo: the row is stored and whatever prompted it is on the
            // chart. An exception escaping here propagates to whoever
            // committed, so a broker hiccup would answer 500 for a result
            // that was written — and with the interactive duplicate check
            // deliberately retired, the clinician's retry then writes a
            // second result. A missed push is a notification the recipient
            // still finds in their list; a duplicated result is not
            // recoverable that cheaply.
            try {
                notificationWebSocketController.sendNotification(saved);
            } catch (RuntimeException ex) {
                log.warn("Notification {} was stored but not pushed: {}", saved.getId(), ex.getMessage(), ex);
            }
        });
        return saved;
    }

    @Override
    public ReadOutcome markAsRead(UUID notificationId, Principal caller, boolean broadcastsMayBeMarked) {
        if (notificationId == null || caller == null || caller.getName() == null) {
            return ReadOutcome.NOT_FOUND;
        }
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return ReadOutcome.NOT_FOUND;
        }
        if (isBroadcast(notification)) {
            if (!broadcastsMayBeMarked || !isUnconfinedStaff(caller)) {
                return ReadOutcome.BROADCAST_LEFT_UNREAD;
            }
        } else if (!caller.getName().equals(notification.getRecipientUsername())) {
            return ReadOutcome.NOT_FOUND;
        }
        notification.setRead(true);
        notificationRepository.save(notification);
        return ReadOutcome.MARKED;
    }

    /**
     * The caller's LIVE context, resolved from the principal as the context
     * filters resolve it, judged by the one rule
     * ({@link ProviderConfinementPolicy#isUnconfinedStaff}). A database outage
     * propagates (a 5xx, retryable), never "not staff"; any other failure to
     * resolve the caller means not staff.
     */
    private boolean isUnconfinedStaff(Principal caller) {
        ProviderCallerResolver resolver = callerResolverProvider.getIfAvailable();
        if (resolver == null) {
            return false;
        }
        HospitalContext context;
        try {
            context = resolver.liveContext(caller);
        } catch (DataAccessException | CannotCreateTransactionException databaseDown) {
            throw databaseDown;
        } catch (RuntimeException unresolvable) {
            log.warn("Caller unresolvable ({}); broadcast left unread", unresolvable.getClass().getSimpleName());
            return false;
        }
        return ProviderConfinementPolicy.isUnconfinedStaff(context);
    }

    /** No recipient: sent to everyone on the broadcast topic (as {@code NotificationWebSocketController} sends it). */
    private static boolean isBroadcast(Notification notification) {
        return notification.getRecipientUsername() == null || notification.getRecipientUsername().isBlank();
    }

    @Override
    public long countUnreadForUser(String username) {
        return notificationRepository.countByRecipientUsernameAndReadFalse(username);
    }

    @Override
    @Transactional
    public int markAllReadForUser(String username) {
        return notificationRepository.markAllReadForUser(username);
    }

    // ── Notification preferences ─────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPreferenceDTO> getPreferences(UUID userId) {
        return ownView(notificationPreferenceRepository.findByUser_Id(userId), userId);
    }

    @Override
    @Transactional
    public List<NotificationPreferenceDTO> updatePreferences(UUID userId, List<NotificationPreferenceUpdateDTO> updates) {
        User user = userRepository.lockForNotificationPreferences(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.notFound", userId));
        // A settings page is a partial matrix. Omitted choices (including a desk-recorded refusal)
        // are never deleted or implicitly enabled. The account lock serializes both writers.
        var existing = notificationPreferenceRepository.findByUser_Id(userId).stream()
            .collect(java.util.stream.Collectors.toMap(
                row -> row.getNotificationType().name() + "/" + row.getChannel().name(), row -> row));
        var changed = new java.util.LinkedHashMap<String, NotificationPreference>();
        var audited = new java.util.ArrayList<String>();
        for (var update : updates == null ? List.<NotificationPreferenceUpdateDTO>of() : updates) {
            if (update == null || update.getNotificationType() == null || update.getChannel() == null) continue;
            String key = update.getNotificationType().name() + "/" + update.getChannel().name();
            var row = existing.get(key);
            boolean enabled = Boolean.TRUE.equals(update.getEnabled());
            if (row != null && row.isEnabled() == enabled) continue;
            boolean before = row == null || row.isEnabled();
            if (row == null) {
                row = NotificationPreference.builder().user(user).notificationType(update.getNotificationType())
                    .channel(update.getChannel()).build();
                existing.put(key, row);
            }
            row.recordChoice(enabled, userId, LocalDateTime.now());
            changed.put(key, row);
            if (before != enabled) {
                audited.add(com.example.hms.service.notification.NotificationPreferenceViews.change(
                    update.getNotificationType(), update.getChannel(), before, enabled));
            }
        }
        notificationPreferenceRepository.saveAll(new java.util.ArrayList<>(changed.values()));
        auditOwnChannelChoices(userId, audited);
        return ownView(existing.values(), userId);
    }

    /**
     * QA r73 D3702: a patient's own change is audited exactly like the desk's (PATIENT_CHANNEL_PREFERENCES_UPDATED,
     * the actor, the patient, each kind/channel old->new; never a contact). A staff account's own bell settings
     * are not a patient's consent and write no patient audit row.
     */
    private void auditOwnChannelChoices(UUID userId, List<String> changes) {
        if (changes.isEmpty()) return;
        UUID patientId = patientRepository.findIdsByUserId(userId).stream().findFirst().orElse(null);
        if (patientId == null) return;
        auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
            .userId(userId)
            .patientId(patientId)
            .eventType(com.example.hms.enums.AuditEventType.PATIENT_CHANNEL_PREFERENCES_UPDATED)
            .status(com.example.hms.enums.AuditStatus.SUCCESS)
            .entityType("PATIENT")
            .resourceId(patientId.toString())
            .eventDescription("Patient channel choices recorded by the patient: " + String.join(", ", changes))
            .build());
    }

    /** QA r73 D3700: the owner may see who recorded a choice by name, never by account id. */
    private List<NotificationPreferenceDTO> ownView(java.util.Collection<NotificationPreference> rows, UUID userId) {
        var labels = recorderLabels.forAccountOwner(
            com.example.hms.service.notification.NotificationPreferenceViews.recorders(rows), userId);
        return rows.stream()
            .map(row -> com.example.hms.service.notification.NotificationPreferenceViews.of(row, userId, labels))
            .toList();
    }
}
