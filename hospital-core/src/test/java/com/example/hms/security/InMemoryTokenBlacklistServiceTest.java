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

    @Test
    void claimHasExactlyOneWinner_evenUnderContention() throws Exception {
        long future = System.currentTimeMillis() + 60_000;
        int threads = 16;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        try {
            java.util.List<java.util.concurrent.Future<?>> all = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                all.add(pool.submit(() -> {
                    start.await();
                    if (service.claim("challenge-jti", future)) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : all) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners.get()).isEqualTo(1);
        assertThat(service.isBlacklisted("challenge-jti")).isTrue();
    }

    @Test
    void anExpiredOrBlankTokenIsNeverClaimed() {
        assertThat(service.claim("old-jti", System.currentTimeMillis() - 1)).isFalse();
        assertThat(service.claim(" ", System.currentTimeMillis() + 60_000)).isFalse();
        assertThat(service.claim(null, System.currentTimeMillis() + 60_000)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void releaseUndoesOnlyItsOwnClaim_neverARevocation() {
        long exp = System.currentTimeMillis() + 60_000L;

        // A claim, then a failed mint: released, the token is usable again.
        org.assertj.core.api.Assertions.assertThat(service.claim("jti-a", exp)).isTrue();
        service.markRotated("jti-a", 30_000L);
        service.release("jti-a");
        org.assertj.core.api.Assertions.assertThat(service.isBlacklisted("jti-a")).isFalse();
        org.assertj.core.api.Assertions.assertThat(service.wasRecentlyRotated("jti-a")).isFalse();

        // A claim, then a logout revokes the same jti, then the mint fails:
        // the logout's revocation stands.
        org.assertj.core.api.Assertions.assertThat(service.claim("jti-b", exp)).isTrue();
        service.blacklist("jti-b", exp);
        service.release("jti-b");
        org.assertj.core.api.Assertions.assertThat(service.isBlacklisted("jti-b")).isTrue();
    }
}
