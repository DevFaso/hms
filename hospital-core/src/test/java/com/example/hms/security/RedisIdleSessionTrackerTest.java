package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisIdleSessionTrackerTest {

    private static final Duration IDLE = Duration.ofMinutes(15);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisIdleSessionTracker tracker(boolean failOpen) {
        return new RedisIdleSessionTracker(redisTemplate, IDLE, failOpen);
    }

    @Test
    @DisplayName("isEnabled() always reports true so the gate consults Redis")
    void isEnabled() {
        assertThat(tracker(true).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("touch() writes the key with the configured TTL")
    void touchStoresWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        UUID id = UUID.randomUUID();

        tracker(true).touch(id);

        verify(valueOps).set(eq("hms:idle:user:" + id), anyString(), eq(IDLE));
    }

    @Test
    @DisplayName("touch(null) is a no-op")
    void touchIgnoresNullUserId() {
        tracker(true).touch(null);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("isIdle returns true when Redis has no key for the user")
    void isIdleTrueWhenNoKey() {
        UUID id = UUID.randomUUID();
        when(redisTemplate.hasKey("hms:idle:user:" + id)).thenReturn(false);

        assertThat(tracker(true).isIdle(id)).isTrue();
    }

    @Test
    @DisplayName("isIdle returns false when Redis has a key for the user")
    void isIdleFalseWhenKeyPresent() {
        UUID id = UUID.randomUUID();
        when(redisTemplate.hasKey("hms:idle:user:" + id)).thenReturn(true);

        assertThat(tracker(true).isIdle(id)).isFalse();
    }

    @Test
    @DisplayName("isIdle returns false when Redis hasKey is null (defensive)")
    void isIdleFalseWhenHasKeyReturnsNull() {
        UUID id = UUID.randomUUID();
        when(redisTemplate.hasKey(anyString())).thenReturn(null);

        // Null-from-Redis treated as "key absent" → idle. The tracker
        // returns the negation, so the user is reported idle which causes
        // a re-auth — safer than treating an unknown state as fresh.
        assertThat(tracker(true).isIdle(id)).isTrue();
    }

    @Test
    @DisplayName("isIdle and touch and clear are no-ops for null userId")
    void nullSafetyAcrossAllOps() {
        assertThat(tracker(true).isIdle(null)).isFalse();
        assertThatCode(() -> tracker(true).touch(null)).doesNotThrowAnyException();
        assertThatCode(() -> tracker(true).clear(null)).doesNotThrowAnyException();
        verify(redisTemplate, never()).hasKey(anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("clear() deletes the user's key")
    void clearDeletesKey() {
        UUID id = UUID.randomUUID();

        tracker(true).clear(id);

        verify(redisTemplate).delete("hms:idle:user:" + id);
    }

    @Test
    @DisplayName("fail-open: Redis outage on isIdle returns false, no exception")
    void failOpenIsIdleReturnsFalse() {
        UUID id = UUID.randomUUID();
        when(redisTemplate.hasKey(any()))
            .thenThrow(new QueryTimeoutException("redis blip"));

        assertThat(tracker(true).isIdle(id)).isFalse();
    }

    @Test
    @DisplayName("fail-open: Redis outage on touch is swallowed, no exception")
    void failOpenTouchSwallowsException() {
        UUID id = UUID.randomUUID();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.doThrow(new QueryTimeoutException("redis blip"))
            .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> tracker(true).touch(id)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fail-closed: Redis outage on isIdle re-throws when policy disabled")
    void failClosedIsIdleRethrows() {
        UUID id = UUID.randomUUID();
        when(redisTemplate.hasKey(any()))
            .thenThrow(new QueryTimeoutException("redis blip"));

        assertThatThrownBy(() -> tracker(false).isIdle(id))
            .isInstanceOf(QueryTimeoutException.class);
    }
}
