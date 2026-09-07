package com.example.hms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    void shouldNotBeLocked_initially() {
        assertThat(service.isLocked("user1")).isFalse();
    }

    @Test
    void shouldLockAfterMaxAttempts() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("user1");
        }
        assertThat(service.isLocked("user1")).isTrue();
        assertThat(service.remainingLockMs("user1")).isGreaterThan(0);
    }

    @Test
    void shouldNotLockBelowThreshold() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS - 1; i++) {
            service.recordFailure("user1");
        }
        assertThat(service.isLocked("user1")).isFalse();
    }

    @Test
    void resetShouldClearAttempts() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("user1");
        }
        service.resetAttempts("user1");
        assertThat(service.isLocked("user1")).isFalse();
    }

    @Test
    void shouldHandleNullAndBlank() {
        service.recordFailure(null);
        service.recordFailure("");
        assertThat(service.isLocked(null)).isFalse();
        assertThat(service.isLocked("")).isFalse();
    }

    @Test
    void shouldBeCaseInsensitive() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("User1");
        }
        assertThat(service.isLocked("user1")).isTrue();
        assertThat(service.isLocked("USER1")).isTrue();
    }

    @Test
    void renameKey_carriesALiveLockoutOntoTheNewUsername() {
        LoginAttemptService svc = service;
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            svc.recordFailure("someone");
        }
        assertThat(svc.isLocked("someone")).isTrue();

        svc.renameKey("someone", "renamed");

        // The whole point: a rename must not be a way to walk free.
        assertThat(svc.isLocked("renamed")).isTrue();
        assertThat(svc.isLocked("someone")).isFalse();
    }

    @Test
    void renameKey_isANoOpForSameNameBlanksAndUnknownKey() {
        LoginAttemptService svc = service;
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            svc.recordFailure("someone");
        }

        svc.renameKey("someone", "SOMEONE");   // same key, different case
        assertThat(svc.isLocked("someone")).isTrue();

        svc.renameKey(null, "x");
        svc.renameKey("someone", "  ");
        assertThat(svc.isLocked("someone")).isTrue();

        svc.renameKey("nobody", "another");    // nothing under the old key
        assertThat(svc.isLocked("another")).isFalse();
    }

    @Test
    void renameKey_clearsTheDestinationEvenWhenTheOldKeyHoldsNothing() {
        LoginAttemptService svc = service;
        // Failures are recorded for unknown usernames too, so an unused name
        // can already be locked. Renaming into it must not inherit that.
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            svc.recordFailure("taken");
        }
        assertThat(svc.isLocked("taken")).isTrue();

        svc.renameKey("nobody", "taken");

        assertThat(svc.isLocked("taken")).isFalse();
    }

    @Test
    void renameKey_foldsInAFailureRecordedAgainstTheOldKeyDuringTheSwap() {
        LoginAttemptService svc = service;
        // Stand in for the race: a failure lands on the old key after the
        // move has taken its snapshot. It must not be stranded under a key
        // nobody reads again.
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            svc.recordFailure("someone");
        }
        svc.renameKey("someone", "renamed");
        assertThat(svc.isLocked("renamed")).isTrue();

        // A straggler arriving after the rename, addressed to the old name.
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            svc.recordFailure("someone");
        }
        svc.renameKey("someone", "renamed");

        assertThat(svc.isLocked("renamed")).isTrue();
        assertThat(svc.isLocked("someone")).isFalse();
    }
}
