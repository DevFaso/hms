package com.example.hms.security;

import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The throttle's policy over a real in-memory store: the key is the account
 * (so a lock follows it through a rename and nobody inherits it), an unknown
 * name locks exactly like a known one, and a reset by id clears the lock
 * whatever name the failures were typed under.
 */
class LoginAttemptServiceTest {

    private UserRepository users;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        when(users.findIdsByUsernameIgnoreCase(anyString())).thenReturn(List.of());
        service = new LoginAttemptService(new InMemoryLoginAttemptStore(), users);
    }

    private void account(String username, UUID id) {
        when(users.findIdsByUsernameIgnoreCase(username)).thenReturn(List.of(id));
    }

    private void fail(String username, int times) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(username);
        }
    }

    @Test
    void shouldNotBeLocked_initially() {
        assertThat(service.isLocked("user1")).isFalse();
    }

    @Test
    void shouldLockAfterMaxAttempts() {
        account("user1", UUID.randomUUID());
        fail("user1", LoginAttemptService.MAX_ATTEMPTS);
        assertThat(service.isLocked("user1")).isTrue();
        assertThat(service.remainingLockMs("user1")).isGreaterThan(0);
    }

    @Test
    void shouldNotLockBelowThreshold() {
        account("user1", UUID.randomUUID());
        fail("user1", LoginAttemptService.MAX_ATTEMPTS - 1);
        assertThat(service.isLocked("user1")).isFalse();
    }

    @Test
    void shouldHandleNullAndBlank() {
        service.recordFailure(null);
        service.recordFailure("");
        service.resetAttempts(null);
        assertThat(service.isLocked(null)).isFalse();
        assertThat(service.isLocked("")).isFalse();
        verify(users, never()).findIdsByUsernameIgnoreCase("");
    }

    @Test
    @DisplayName("a reset by the account id clears the lock")
    void resetByIdClearsTheLock() {
        UUID id = UUID.randomUUID();
        account("user1", id);
        fail("user1", LoginAttemptService.MAX_ATTEMPTS);

        service.resetAttempts(id);

        assertThat(service.isLocked("user1")).isFalse();
    }

    @Test
    @DisplayName("every letter case of the name is the same account, so the same lock")
    void caseVariantsOfOneAccountShareTheLock() {
        UUID id = UUID.randomUUID();
        account("User1", id);
        account("user1", id);
        account("USER1", id);
        fail("User1", LoginAttemptService.MAX_ATTEMPTS);
        assertThat(service.isLocked("user1")).isTrue();
        assertThat(service.isLocked("USER1")).isTrue();
    }

    @Test
    @DisplayName("a rename does not void a live lock: it follows the account to its new name")
    void aLockFollowsTheAccountThroughARename() {
        UUID victim = UUID.randomUUID();
        account("victim", victim);
        fail("victim", LoginAttemptService.MAX_ATTEMPTS);

        // An administrator renames the account.
        when(users.findIdsByUsernameIgnoreCase("victim")).thenReturn(List.of());
        account("victim2", victim);

        assertThat(service.isLocked("victim2"))
            .as("the brute force continues against the new name with no fresh budget")
            .isTrue();
    }

    @Test
    @DisplayName("an account created under a locked account's old name inherits nothing")
    void aNewAccountUnderAnOldNameInheritsNothing() {
        UUID victim = UUID.randomUUID();
        account("victim", victim);
        fail("victim", LoginAttemptService.MAX_ATTEMPTS);

        account("victim2", victim);
        account("victim", UUID.randomUUID());

        assertThat(service.isLocked("victim")).isFalse();
        assertThat(service.isLocked("victim2")).isTrue();
    }

    @Test
    @DisplayName("resetting one account never touches another whose name differs only in case")
    void caseVariantAccountsNeverShareState() {
        UUID upper = UUID.randomUUID();
        UUID lower = UUID.randomUUID();
        account("Victim", upper);
        account("victim", lower);
        fail("Victim", LoginAttemptService.MAX_ATTEMPTS);

        service.resetAttempts(lower);

        assertThat(service.isLocked("Victim")).isTrue();
        assertThat(service.isLocked("victim")).isFalse();
    }

    @Test
    @DisplayName("an unknown name locks after the same number of failures, so the 423 reveals nothing")
    void anUnknownNameLocksLikeAKnownOne() {
        fail("nobody", LoginAttemptService.MAX_ATTEMPTS - 1);
        assertThat(service.isLocked("nobody")).isFalse();

        service.recordFailure("nobody");

        assertThat(service.isLocked("nobody")).isTrue();
        assertThat(service.isLocked("NOBODY")).as("unknown names fold case like known ones").isTrue();
        assertThat(service.remainingLockMs("nobody"))
            .isGreaterThan(LoginAttemptService.LOCK_DURATION_MS - 60_000);
    }

    @Test
    @DisplayName("a name probed before its account existed does not lock the new holder out")
    void probingANameBeforeItsAccountExistsLocksNobodyOut() {
        fail("newstaff", LoginAttemptService.MAX_ATTEMPTS);
        assertThat(service.isLocked("newstaff")).isTrue();

        account("newstaff", UUID.randomUUID());

        assertThat(service.isLocked("newstaff")).isFalse();
    }

    @Test
    @DisplayName("an unknown name is stored as a hash, never as typed")
    void unknownNamesAreHashed() {
        String key = service.keyFor("MyPassword123!");
        assertThat(key).startsWith(LoginAttemptService.NAME_KEY_PREFIX).doesNotContainIgnoringCase("password");
        UUID id = UUID.randomUUID();
        account("known", id);
        assertThat(service.keyFor("known")).isEqualTo(LoginAttemptService.USER_KEY_PREFIX + id);
    }
}
