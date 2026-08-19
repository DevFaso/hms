package com.example.hms.service;

import com.example.hms.model.PasswordHistory;
import com.example.hms.repository.PasswordHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Enforces password history policy: prevents reuse of the last N passwords.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordHistoryService {

    private static final int MAX_HISTORY = 5;

    private final PasswordHistoryRepository repository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Check whether the raw password matches any of the last {@value #MAX_HISTORY} stored hashes.
     *
     * @return {@code true} if the password was previously used
     */
    @Transactional(readOnly = true)
    public boolean isPasswordReused(UUID userId, String rawPassword) {
        List<PasswordHistory> history = repository.findByUserIdOrderByCreatedAtDesc(userId);
        return history.stream()
                .limit(MAX_HISTORY)
                .anyMatch(h -> passwordEncoder.matches(rawPassword, h.getPasswordHash()));
    }

    /**
     * Record a new password hash in history. Prunes entries beyond {@value #MAX_HISTORY}.
     */
    @Transactional
    public void recordPassword(UUID userId, String encodedPassword) {
        repository.save(PasswordHistory.builder()
                .userId(userId)
                .passwordHash(encodedPassword)
                .build());

        // Prune old entries beyond retention limit
        List<PasswordHistory> all = repository.findByUserIdOrderByCreatedAtDesc(userId);
        if (all.size() > MAX_HISTORY) {
            List<PasswordHistory> toDelete = all.subList(MAX_HISTORY, all.size());
            repository.deleteAll(toDelete);
            log.debug("[PASSWORD-HISTORY] Pruned {} old entries for user={}", toDelete.size(), userId);
        }
    }
}
