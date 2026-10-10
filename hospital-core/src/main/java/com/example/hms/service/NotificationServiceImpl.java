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

    @Override
        public List<Notification> getNotificationsForUser(String username) {
            return notificationRepository.findByRecipientUsername(username, Pageable.unpaged()).getContent();
    }

    @Override
        public Page<Notification> getNotificationsForUser(String username, Boolean read, String search, Pageable pageable) {
            if (read != null && search != null && !search.isEmpty()) {
                return notificationRepository.findByRecipientUsernameAndReadAndMessageContainingIgnoreCase(username, read, search, pageable);
            } else if (read != null) {
                return notificationRepository.findByRecipientUsernameAndRead(username, read, pageable);
            } else if (search != null && !search.isEmpty()) {
                return notificationRepository.findByRecipientUsernameAndMessageContainingIgnoreCase(username, search, pageable);
            } else {
                return notificationRepository.findByRecipientUsername(username, pageable);
            }
    }

    @Override
    public Notification createNotification(String message, String recipientUsername) {
        return createNotification(message, recipientUsername, null);
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
    @Override
    public Notification createNotification(String message, String recipientUsername, String type) {
        Notification notification = Notification.builder()
                .message(message)
                .recipientUsername(recipientUsername)
                .type(type)
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
        return notificationPreferenceRepository.findByUser_Id(userId)
                .stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional
    public List<NotificationPreferenceDTO> updatePreferences(UUID userId, List<NotificationPreferenceUpdateDTO> updates) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.notFound", userId));

        notificationPreferenceRepository.deleteByUser_Id(userId);
        notificationPreferenceRepository.flush();

        List<NotificationPreference> entities = updates.stream().map(u ->
                NotificationPreference.builder()
                        .user(user)
                        .notificationType(u.getNotificationType())
                        .channel(u.getChannel())
                        .enabled(u.isEnabled())
                        .build()
        ).toList();

        return notificationPreferenceRepository.saveAll(entities)
                .stream().map(this::toDTO).toList();
    }

    private NotificationPreferenceDTO toDTO(NotificationPreference p) {
        return NotificationPreferenceDTO.builder()
                .id(p.getId())
                .notificationType(p.getNotificationType())
                .channel(p.getChannel())
                .enabled(p.isEnabled())
                .build();
    }
}
