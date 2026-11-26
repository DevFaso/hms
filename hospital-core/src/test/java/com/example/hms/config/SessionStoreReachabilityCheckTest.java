package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Once ready, the shared session store is pinged once and an outage is logged, never fatal")
class SessionStoreReachabilityCheckTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedisConnectionFactory> provider(RedisConnectionFactory factory) {
        ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(factory);
        return provider;
    }

    @Test
    @DisplayName("with no Redis-backed store switched on, nothing is pinged")
    void inMemoryStoresArePingedNever() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        SessionStoreReachabilityCheck check = new SessionStoreReachabilityCheck(new MockEnvironment(), provider(factory));

        assertThat(check.redisStoreEnabled()).isFalse();
        check.pingOnceWhenReady();

        verify(factory, never()).getConnection();
    }

    @Test
    @DisplayName("either switch on enables the ping")
    void eitherSwitchEnablesThePing() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        assertThat(new SessionStoreReachabilityCheck(
            new MockEnvironment().withProperty("app.redis.token-blacklist.enabled", "true"), provider(factory))
            .redisStoreEnabled()).isTrue();
        assertThat(new SessionStoreReachabilityCheck(
            new MockEnvironment().withProperty("app.auth.idle-tracking.enabled", "TRUE"), provider(factory))
            .redisStoreEnabled()).isTrue();
    }

    @Test
    @DisplayName("a Redis that answers is reported reachable and the connection is closed")
    void aRedisThatAnswers() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn("PONG");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        SessionStoreReachabilityCheck check = new SessionStoreReachabilityCheck(
            new MockEnvironment().withProperty("app.redis.token-blacklist.enabled", "true"), provider(factory));

        assertThat(check.ping()).isTrue();
        verify(connection).close();
    }

    @Test
    @DisplayName("an unreachable Redis is reported, not thrown")
    void anUnreachableRedisIsLoggedNotThrown() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException("unreachable"));
        SessionStoreReachabilityCheck check = new SessionStoreReachabilityCheck(
            new MockEnvironment().withProperty("app.redis.token-blacklist.enabled", "true"), provider(factory));

        assertThat(check.ping()).isFalse();
    }

    @Test
    @DisplayName("no Redis connection factory at all is reported, not thrown")
    void noConnectionFactory() {
        SessionStoreReachabilityCheck check = new SessionStoreReachabilityCheck(
            new MockEnvironment().withProperty("app.auth.idle-tracking.enabled", "true"), provider(null));

        assertThat(check.ping()).isFalse();
    }

    @Test
    @DisplayName("a failed PING after connecting is reported, not thrown")
    void aFailedPing() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenThrow(new RedisConnectionFailureException("timeout"));
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        SessionStoreReachabilityCheck check = new SessionStoreReachabilityCheck(
            new MockEnvironment().withProperty("app.redis.token-blacklist.enabled", "true"), provider(factory));

        assertThat(check.ping()).isFalse();
        verify(connection).close();
    }
}
