package com.example.hms.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Redis store against a real Redis, as two application instances would
 * use it: two store objects, one server. A lock recorded through one refuses
 * on the other, and a reset through one clears it for the other, which is the
 * whole point of moving the counter out of the JVM.
 */
@Testcontainers
@DisplayName("Login throttle in Redis (two instances)")
class RedisLoginAttemptStoreIT {

    private static final int MAX = 5;
    private static final long WINDOW_MS = 15 * 60 * 1000L;
    private static final long LOCK_MS = 15 * 60 * 1000L;

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    private RedisLoginAttemptStore instanceA;
    private RedisLoginAttemptStore instanceB;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        instanceA = new RedisLoginAttemptStore(redis);
        instanceB = new RedisLoginAttemptStore(redis);
    }

    @Test
    @DisplayName("failures counted on two instances add up, and the lock holds on both")
    void aLockIsSharedAcrossInstances() {
        for (int i = 0; i < MAX - 1; i++) {
            (i % 2 == 0 ? instanceA : instanceB).recordFailure("user:u1", MAX, WINDOW_MS, LOCK_MS);
        }
        assertThat(instanceA.remainingLockMs("user:u1")).isZero();

        int count = instanceB.recordFailure("user:u1", MAX, WINDOW_MS, LOCK_MS);

        assertThat(count).isEqualTo(MAX);
        assertThat(instanceA.remainingLockMs("user:u1")).isBetween(LOCK_MS - 5_000, LOCK_MS);
        assertThat(instanceB.remainingLockMs("user:u1")).isPositive();
    }

    @Test
    @DisplayName("a reset on one instance (an activation there) clears the lock for the other")
    void aResetIsSharedAcrossInstances() {
        for (int i = 0; i < MAX; i++) {
            instanceA.recordFailure("user:u2", MAX, WINDOW_MS, LOCK_MS);
        }
        assertThat(instanceB.remainingLockMs("user:u2")).isPositive();

        instanceB.clear("user:u2");

        assertThat(instanceA.remainingLockMs("user:u2")).isZero();
        assertThat(instanceA.recordFailure("user:u2", MAX, WINDOW_MS, LOCK_MS))
            .as("the count starts over too").isEqualTo(1);
    }

    @Test
    @DisplayName("the counter always carries the window as its expiry, and keys never collide")
    void theCounterExpiresWithItsWindow() {
        instanceA.recordFailure("user:u3", MAX, WINDOW_MS, LOCK_MS);
        instanceA.recordFailure("user:u3", MAX, WINDOW_MS, LOCK_MS);

        Long ttl = redis.getExpire(RedisLoginAttemptStore.FAIL_PREFIX + "user:u3", TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(WINDOW_MS - 5_000, WINDOW_MS);
        assertThat(instanceB.recordFailure("user:u4", MAX, WINDOW_MS, LOCK_MS)).isEqualTo(1);
    }
}
