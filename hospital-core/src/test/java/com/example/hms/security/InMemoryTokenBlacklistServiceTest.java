package com.example.hms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenBlacklistServiceTest {

    private InMemoryTokenBlacklistService service;

    @BeforeEach
    void setUp() {
        service = new InMemoryTokenBlacklistService();
    }

    @Test
    void blacklistedTokenShouldBeDetected() {
        service.blacklist("jti-1", System.currentTimeMillis() + 60_000);
        assertThat(service.isBlacklisted("jti-1")).isTrue();
    }

    @Test
    void unknownTokenShouldNotBeBlacklisted() {
        assertThat(service.isBlacklisted("unknown")).isFalse();
    }

    @Test
    void nullOrBlankJtiShouldNotThrow() {
        service.blacklist(null, 0);
        service.blacklist("", 0);
        assertThat(service.isBlacklisted(null)).isFalse();
        assertThat(service.isBlacklisted("")).isFalse();
    }

    @Test
    void evictExpiredShouldRemoveOldEntries() {
        long past = System.currentTimeMillis() - 1000;
        long future = System.currentTimeMillis() + 60_000;

        service.blacklist("expired-jti", past);
        service.blacklist("valid-jti", future);

        service.evictExpired();

        assertThat(service.isBlacklisted("expired-jti")).isFalse();
        assertThat(service.isBlacklisted("valid-jti")).isTrue();
    }
}
