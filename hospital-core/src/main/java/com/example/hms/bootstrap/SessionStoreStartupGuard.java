package com.example.hms.bootstrap;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses to start when the shared session store is not configured the way the environment needs.
 *
 * <p>Two switches pick where sign-out revocations, login-lockout counters and idle-session stamps
 * live: {@code app.redis.token-blacklist.enabled} ({@code REDIS_TOKEN_BLACKLIST_ENABLED}) and
 * {@code app.auth.idle-tracking.enabled} ({@code AUTH_IDLE_TRACKING_ENABLED}). {@code true} selects
 * the Redis-backed beans, {@code false} (or unset) the in-memory ones.
 *
 * <ul>
 *   <li><strong>Every profile:</strong> a switch that is set must read {@code true} or
 *       {@code false} (any case, as the bean conditions compare). Any other value, blank included,
 *       matches neither condition: the service would start with no store, or the wrong one.</li>
 *   <li><strong>Production</strong> (a {@code prod} / {@code production} profile, or a Railway
 *       environment named {@code prod} / {@code production}): both switches must be {@code true},
 *       the Redis host must be set and not a loopback address, and the port must be a number from
 *       1 to 65535. In-memory state is lost at every restart and is not shared between instances,
 *       so a signed-out session would come back after a deploy.</li>
 * </ul>
 *
 * <p>Runs as an {@link EnvironmentPostProcessor} after the config-data post-processor, next to
 * {@link StartupProfileGuard}, so it fails before any bean is created. Registered in
 * {@code META-INF/spring.factories}. Developer and test profiles set neither switch and keep the
 * in-memory stores.
 */
public class SessionStoreStartupGuard implements EnvironmentPostProcessor, Ordered {

    static final String TOKEN_BLACKLIST_ENABLED = "app.redis.token-blacklist.enabled";
    static final String IDLE_TRACKING_ENABLED = "app.auth.idle-tracking.enabled";
    static final String REDIS_HOST = "spring.data.redis.host";
    static final String REDIS_PORT = "spring.data.redis.port";
    static final String REDIS_URL = "spring.data.redis.url";

    /** Profiles that mean production. */
    static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    /** Variables Railway sets to the environment's name on every service. */
    static final List<String> PLATFORM_ENVIRONMENT_NAMES = List.of("RAILWAY_ENVIRONMENT_NAME", "RAILWAY_ENVIRONMENT");

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        check(environment);
    }

    static void check(Environment environment) {
        Switch tokenBlacklist = readSwitch(environment, TOKEN_BLACKLIST_ENABLED, "REDIS_TOKEN_BLACKLIST_ENABLED");
        Switch idleTracking = readSwitch(environment, IDLE_TRACKING_ENABLED, "AUTH_IDLE_TRACKING_ENABLED");
        if (!isProduction(environment)) {
            return;
        }
        requireOn(tokenBlacklist, TOKEN_BLACKLIST_ENABLED, "REDIS_TOKEN_BLACKLIST_ENABLED",
            "sign-out revocations and login-lockout counters would be kept in memory, lost at every restart "
                + "and not shared between instances");
        requireOn(idleTracking, IDLE_TRACKING_ENABLED, "AUTH_IDLE_TRACKING_ENABLED",
            "the idle-session timeout would be kept in memory, lost at every restart and not shared between "
                + "instances");
        requireRedisAddress(environment);
    }

    /** Whether this start is production: a production profile, or a Railway environment named so. */
    static boolean isProduction(Environment environment) {
        if (Arrays.stream(environment.getActiveProfiles()).anyMatch(PRODUCTION_PROFILES::contains)) {
            return true;
        }
        for (String variable : PLATFORM_ENVIRONMENT_NAMES) {
            String name = environment.getProperty(variable);
            if (name != null && PRODUCTION_PROFILES.contains(name.strip().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** A switch's state: unset, or one of the two values the bean conditions recognise. */
    enum Switch { UNSET, ON, OFF }

    /**
     * The switch's state: {@link Switch#UNSET} when it is not set, otherwise on or off.
     * Anything else is refused, because the bean conditions compare the raw value with
     * {@code "true"} / {@code "false"} and would select no store at all.
     */
    private static Switch readSwitch(Environment environment, String property, String variable) {
        if (!environment.containsProperty(property)) {
            return Switch.UNSET;
        }
        String value = environment.getProperty(property);
        if ("true".equalsIgnoreCase(value)) {
            return Switch.ON;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Switch.OFF;
        }
        throw new StartupConfigurationException(
            variable + " (" + property + ") must be true or false, but is " + quoted(value)
                + ". Any other value selects neither the Redis store nor the in-memory one.");
    }

    private static void requireOn(Switch value, String property, String variable, String consequence) {
        if (value != Switch.ON) {
            throw new StartupConfigurationException(
                "Production requires the shared session store: set " + variable + "=true (" + property
                    + " is " + (value == Switch.UNSET ? "not set" : "false") + "). Without it " + consequence + ".");
        }
    }

    private static void requireRedisAddress(Environment environment) {
        String url = environment.getProperty(REDIS_URL);
        if (StringUtils.hasText(url)) {
            // spring.data.redis.url, when set, takes precedence over host and port.
            String host = hostOf(url);
            if (host == null || isLoopback(host)) {
                throw new StartupConfigurationException(
                    "Production requires a reachable shared Redis: SPRING_DATA_REDIS_URL (" + REDIS_URL
                        + ") must name the Redis service's host, not "
                        + (host == null ? "an unreadable URL" : quoted(host)) + ".");
            }
            return;
        }
        String host = environment.getProperty(REDIS_HOST);
        if (!StringUtils.hasText(host) || isLoopback(host.strip())) {
            throw new StartupConfigurationException(
                "Production requires a reachable shared Redis: set REDIS_HOST (" + REDIS_HOST
                    + ") to the Redis service's host; it is "
                    + (StringUtils.hasText(host) ? quoted(host) : "empty") + ".");
        }
        String port = environment.getProperty(REDIS_PORT);
        if (!isValidPort(port)) {
            throw new StartupConfigurationException(
                "Production requires a reachable shared Redis: REDIS_PORT (" + REDIS_PORT
                    + ") must be a port number from 1 to 65535, but is "
                    + (StringUtils.hasText(port) ? quoted(port) : "empty") + ".");
        }
    }

    private static String hostOf(String url) {
        try {
            return new URI(url.strip()).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean isLoopback(String host) {
        return LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private static boolean isValidPort(String port) {
        if (port == null) {
            return false;
        }
        try {
            int value = Integer.parseInt(port.strip());
            return value >= 1 && value <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A switch or host value is not a secret, but it is bounded so a pasted blob does not flood the log. */
    private static String quoted(String value) {
        String shown = value.length() > 40 ? value.substring(0, 40) + "..." : value;
        return "'" + shown + "'";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
