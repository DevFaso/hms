package com.example.hms.service.notification;

import com.example.hms.BaseIT;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Disposable test database only; preferences never invoke an SMS/mail transport. */
class NotificationPreferenceConcurrencyIT extends BaseIT {
    @Autowired private UserRepository users;
    @Autowired private NotificationService service;

    @Test
    void concurrentFirstChoicesDoNotDuplicateRowsOrEraseAnotherChannelRefusal() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User account = users.save(User.builder().username("cdx-consent-" + suffix)
            .passwordHash("test-only-not-a-login").firstName("cdx").lastName("Consent Recorder")
            .email("cdx-" + suffix + "@example.invalid").build());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                var change = i % 2 == 0
                    ? new NotificationPreferenceUpdateDTO(NotificationType.LAB_RESULT, NotificationChannel.SMS, false)
                    : new NotificationPreferenceUpdateDTO(NotificationType.BILLING, NotificationChannel.EMAIL, false);
                results.add(executor.submit(() -> {
                    start.await();
                    service.updatePreferences(account.getId(), List.of(change));
                    return null;
                }));
            }
            start.countDown();
            for (var result : results) result.get(30, TimeUnit.SECONDS);
        }
        assertThat(service.getPreferences(account.getId())).hasSize(2).allSatisfy(choice -> {
            assertThat(choice.isEnabled()).isFalse();
            assertThat(choice.getRecordedByUserId()).isEqualTo(account.getId());
            assertThat(choice.getRecordedAt()).isNotNull();
        });
    }
}
