package com.example.hms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Once the application is ready, sends one PING to Redis when a Redis-backed session store is
 * switched on ({@code REDIS_TOKEN_BLACKLIST_ENABLED} or {@code AUTH_IDLE_TRACKING_ENABLED}), and logs
 * an ERROR naming the variables to check when it does not answer.
 *
 * <p>It never fails the start: a Redis that is still coming up when the backend starts would
 * otherwise turn a start that becomes healthy seconds later into a crash loop. The ping runs on its
 * own daemon thread, so a slow connection does not hold the ready event. In production the Redis
 * health indicator is on as well ({@code application-prod.yml}), so a deploy whose Redis cannot be
 * reached fails its healthcheck and the previous deployment keeps serving.
 */
@Component
public class SessionStoreReachabilityCheck {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreReachabilityCheck.class);

    private final Environment env;
    private final ObjectProvider<RedisConnectionFactory> connectionFactory;

    public SessionStoreReachabilityCheck(Environment env, ObjectProvider<RedisConnectionFactory> connectionFactory) {
        this.env = env;
        this.connectionFactory = connectionFactory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void pingOnceWhenReady() {
        if (!redisStoreEnabled()) {
            return;
        }
        Thread thread = new Thread(this::ping, "session-store-ping");
        thread.setDaemon(true);
        thread.start();
    }

    boolean redisStoreEnabled() {
        return "true".equalsIgnoreCase(env.getProperty("app.redis.token-blacklist.enabled"))
            || "true".equalsIgnoreCase(env.getProperty("app.auth.idle-tracking.enabled"));
    }

    /** Returns whether Redis answered; logs the outcome either way. */
    boolean ping() {
        String address = env.getProperty("spring.data.redis.host", "") + ":" + env.getProperty("spring.data.redis.port", "");
        RedisConnectionFactory factory = connectionFactory.getIfAvailable();
        if (factory == null) {
            log.error("Shared session store: no Redis connection is configured although a Redis-backed store "
                + "is switched on. Check REDIS_HOST, REDIS_PORT and REDIS_PASSWORD.");
            return false;
        }
        try (RedisConnection connection = factory.getConnection()) {
            connection.ping();
            log.info("Shared session store: Redis at {} answered.", address);
            return true;
        } catch (RuntimeException e) {
            log.error("Shared session store: Redis at {} did not answer a PING ({}). Sign-out, login lockout "
                + "and the idle timeout depend on it. Check REDIS_HOST, REDIS_PORT and REDIS_PASSWORD, and "
                + "that the Redis service is running.", address, e.getClass().getSimpleName());
            return false;
        }
    }
}
