package com.example.hms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisTokenBlacklistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private RedisTokenBlacklistService service;

    @BeforeEach
    void setUp() {
        // Lenient stubbing via lenient() isn't needed because tests that touch
        // opsForValue call this in their own setup.
    }

    @Test
    void blacklistShouldStoreWithPositiveTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        long expiration = System.currentTimeMillis() + 60_000;

        service.blacklist("jti-1", expiration);

        verify(valueOps)
                .set(org.mockito.ArgumentMatchers.eq("hms:blacklist:jti:jti-1"),
                        org.mockito.ArgumentMatchers.eq(Long.toString(expiration)),
                        any(Duration.class));
    }

    @Test
    void blacklistShouldSkipAlreadyExpiredToken() {
        long expiration = System.currentTimeMillis() - 1000;

        service.blacklist("jti-expired", expiration);

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void blacklistShouldIgnoreNullOrBlankJti() {
        service.blacklist(null, System.currentTimeMillis() + 60_000);
        service.blacklist("", System.currentTimeMillis() + 60_000);
        service.blacklist("   ", System.currentTimeMillis() + 60_000);

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void isBlacklistedShouldReturnTrueWhenKeyExists() {
        when(redisTemplate.hasKey("hms:blacklist:jti:jti-1")).thenReturn(true);

        assertThat(service.isBlacklisted("jti-1")).isTrue();
    }

    @Test
    void isBlacklistedShouldReturnFalseWhenKeyMissing() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        assertThat(service.isBlacklisted("jti-missing")).isFalse();
    }

    @Test
    void isBlacklistedShouldReturnFalseWhenRedisReturnsNull() {
        when(redisTemplate.hasKey(anyString())).thenReturn(null);

        assertThat(service.isBlacklisted("jti-null")).isFalse();
    }

    @Test
    void isBlacklistedShouldReturnFalseForNullOrBlankJti() {
        assertThat(service.isBlacklisted(null)).isFalse();
        assertThat(service.isBlacklisted("")).isFalse();
        assertThat(service.isBlacklisted("   ")).isFalse();

        verify(redisTemplate, never()).hasKey(anyString());
    }

    @Test
    void claimIsASetIfAbsent_soOnlyOneCallerWinsAcrossInstances() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        long expiration = System.currentTimeMillis() + 60_000;
        when(valueOps.setIfAbsent(org.mockito.ArgumentMatchers.eq("hms:blacklist:jti:jti-1"),
                org.mockito.ArgumentMatchers.eq("claim:" + expiration), any(Duration.class)))
                .thenReturn(true, false);

        assertThat(service.claim("jti-1", expiration)).isTrue();
        assertThat(service.claim("jti-1", expiration)).isFalse();
    }

    @Test
    void claimNeverStoresAnExpiredToken() {
        assertThat(service.claim("jti-expired", System.currentTimeMillis() - 1000)).isFalse();

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void releaseLeavesARevocation_andRemovesOnlyAClaim() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // A logout's revocation (a bare expiry): never deleted by release.
        when(valueOps.get("hms:blacklist:jti:jti-r")).thenReturn("1760000000000");
        service.release("jti-r");
        verify(redisTemplate, never()).execute(
            org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
            org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.<Object>any());

        // A claim's own value: removed (compare-and-delete).
        when(valueOps.get("hms:blacklist:jti:jti-c")).thenReturn("claim:1760000000000");
        service.release("jti-c");
        verify(redisTemplate).execute(
            org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
            org.mockito.ArgumentMatchers.eq(java.util.List.of("hms:blacklist:jti:jti-c")),
            org.mockito.ArgumentMatchers.<Object>eq("claim:1760000000000"));
    }
}
