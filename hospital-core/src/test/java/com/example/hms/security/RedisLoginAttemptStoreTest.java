package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Redis outage degrades the throttle to this instance, never to no
 * throttle: brute force stays bounded while Redis is down.
 */
class RedisLoginAttemptStoreTest {

    @Test
    @DisplayName("with Redis down, failures still lock, on this instance")
    @SuppressWarnings("unchecked")
    void anOutageFallsBackToALocalLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnectionFailureException down = new RedisConnectionFailureException("down");
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenThrow(down);
        when(redis.getExpire(anyString(), eq(TimeUnit.MILLISECONDS))).thenThrow(down);
        when(redis.delete(any(List.class))).thenThrow(down);
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis);

        for (int i = 0; i < 5; i++) {
            store.recordFailure("user:u1", 5, 60_000, 60_000);
        }

        assertThat(store.remainingLockMs("user:u1")).isPositive();
        store.clear("user:u1");
        assertThat(store.remainingLockMs("user:u1")).as("a clear reaches the local fallback").isZero();
        assertThat(store.isShared()).isTrue();
    }
}
