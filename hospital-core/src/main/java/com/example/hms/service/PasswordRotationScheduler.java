package com.example.hms.service;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.example.hms.config.PasswordRotationPolicy.MAX_PASSWORD_AGE_DAYS;
import static com.example.hms.config.PasswordRotationPolicy.WARNING_WINDOW_DAYS;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordRotationScheduler {

    private final UserRepository userRepository;
    private final EmailService emailService;

    @Scheduled(cron = "${app.security.password-rotation.cron:0 15 3 * * *}")
    @Transactional
    public void runDailyPasswordRotationCheck() {
        process(LocalDateTime.now());
    }

    void process(LocalDateTime snapshot) {
        List<User> users = userRepository.findByIsDeletedFalse();
        if (users.isEmpty()) {
            log.debug("[PASSWORD ROTATION] No active users to evaluate.");
            return;
        }

        LocalDate today = snapshot.toLocalDate();
        Map<Long, List<String>> countdown = new TreeMap<>();
        LinkedHashSet<User> dirtyUsers = new LinkedHashSet<>();
        int reminders = 0;
        int forced = 0;

        for (User user : users) {
            if (!isEligible(user)) {
                continue;
            }

            RotationInfo info = computeRotationInfo(user, snapshot, today);
            countdown.computeIfAbsent(info.daysUntilDue(), key -> new ArrayList<>())
                .add(user.getEmail().toLowerCase(Locale.ROOT));

            if (info.daysUntilDue() <= 0) {
                EnforcementResult enforcement = applyEnforcement(user, info, snapshot);
                if (enforcement.stateChanged()) {
                    dirtyUsers.add(user);
                }
                if (enforcement.notificationSent()) {
                    forced++;
                }
            } else {
                ReminderResult reminder = maybeSendReminder(user, info, snapshot);
                if (reminder.stateChanged()) {
                    dirtyUsers.add(user);
                }
                if (reminder.notificationSent()) {
                    reminders++;
                } else if (clearStaleWarning(user, info)) {
                    dirtyUsers.add(user);
                }
            }
        }

        if (!dirtyUsers.isEmpty()) {
            userRepository.saveAll(dirtyUsers);
        }

        if (!countdown.isEmpty()) {
            countdown.forEach((days, emails) -> {
                if (days >= 0) {
                    log.info("[PASSWORD ROTATION] {} day(s) remaining -> {}", days, emails);
                } else {
                    log.warn("[PASSWORD ROTATION] {} day(s) overdue -> {}", Math.abs(days), emails);
                }
            });
        }

        log.info("[PASSWORD ROTATION] Daily job complete. Reminders sent: {}, forced resets queued: {}", reminders, forced);
    }

    private void sendReminderEmail(User user, LocalDate dueOn, long daysUntilDue) {
        try {
            emailService.sendPasswordRotationReminderEmail(
                user.getEmail(),
                resolveDisplayName(user),
                daysUntilDue,
                dueOn
            );
        } catch (Exception ex) {
            log.warn("[PASSWORD ROTATION] Failed to send reminder to {}: {}", user.getEmail(), ex.getMessage());
        }
    }

    private void sendForceEmail(User user, LocalDate dueOn, long daysOverdue) {
        try {
            emailService.sendPasswordRotationForceChangeEmail(
                user.getEmail(),
                resolveDisplayName(user),
                dueOn,
                daysOverdue
            );
        } catch (Exception ex) {
            log.warn("[PASSWORD ROTATION] Failed to send enforcement email to {}: {}", user.getEmail(), ex.getMessage());
        }
    }

    private LocalDateTime resolveEffectiveChangedAt(User user, LocalDateTime fallback) {
        if (user.getPasswordChangedAt() != null) {
            return user.getPasswordChangedAt();
        }
        if (user.getPasswordRotationForcedAt() != null) {
            return user.getPasswordRotationForcedAt();
        }
        if (user.getCreatedAt() != null) {
            return user.getCreatedAt();
        }
        if (user.getUpdatedAt() != null) {
            return user.getUpdatedAt();
        }
        return fallback;
    }

    private String resolveDisplayName(User user) {
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            return user.getFirstName();
        }
        if (user.getLastName() != null && !user.getLastName().isBlank()) {
            return user.getLastName();
        }
        return user.getUsername() != null ? user.getUsername() : "there";
    }

    private boolean isEligible(User user) {
        return user.isActive()
            && user.getEmail() != null
            && !user.getEmail().isBlank();
    }

    private RotationInfo computeRotationInfo(User user, LocalDateTime snapshot, LocalDate today) {
        LocalDateTime effectiveChangedAt = resolveEffectiveChangedAt(user, snapshot);
        LocalDate dueOn = effectiveChangedAt.toLocalDate().plusDays(MAX_PASSWORD_AGE_DAYS);
        long daysUntilDue = ChronoUnit.DAYS.between(today, dueOn);
        boolean warnedToday = user.getPasswordRotationWarningAt() != null
            && user.getPasswordRotationWarningAt().toLocalDate().isEqual(today);
        boolean forcedToday = user.getPasswordRotationForcedAt() != null
            && user.getPasswordRotationForcedAt().toLocalDate().isEqual(today);
        return new RotationInfo(dueOn, daysUntilDue, warnedToday, forcedToday);
    }

    private ReminderResult maybeSendReminder(User user, RotationInfo info, LocalDateTime snapshot) {
        if (info.warnedToday() || info.daysUntilDue() > WARNING_WINDOW_DAYS) {
            return ReminderResult.NO_OP;
        }

        user.setPasswordRotationWarningAt(snapshot);
        sendReminderEmail(user, info.dueOn(), info.daysUntilDue());
        return new ReminderResult(true, true);
    }

    private boolean clearStaleWarning(User user, RotationInfo info) {
        if (info.daysUntilDue() > WARNING_WINDOW_DAYS && user.getPasswordRotationWarningAt() != null) {
            user.setPasswordRotationWarningAt(null);
            return true;
        }
        return false;
    }

    private EnforcementResult applyEnforcement(User user, RotationInfo info, LocalDateTime snapshot) {
        boolean stateChanged = false;
        boolean notificationSent = false;

        if (!user.isForcePasswordChange()) {
            user.setForcePasswordChange(true);
            stateChanged = true;
        }

        if (!info.forcedToday()) {
            user.setPasswordRotationForcedAt(snapshot);
            user.setPasswordRotationWarningAt(null);
            sendForceEmail(user, info.dueOn(), Math.abs(info.daysUntilDue()));
            stateChanged = true;
            notificationSent = true;
        }

        return new EnforcementResult(stateChanged, notificationSent);
    }

    private record RotationInfo(LocalDate dueOn, long daysUntilDue, boolean warnedToday, boolean forcedToday) {
    }

    private record ReminderResult(boolean stateChanged, boolean notificationSent) {
        private static final ReminderResult NO_OP = new ReminderResult(false, false);
    }

    private record EnforcementResult(boolean stateChanged, boolean notificationSent) {
    }
}
