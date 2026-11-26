package com.example.hms.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Production starts only with the shared session store; the store switches read true or false")
class SessionStoreStartupGuardTest {

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    /** A production environment with everything the guard requires. */
    private static MockEnvironment configuredProduction() {
        return environment("prod")
            .withProperty(SessionStoreStartupGuard.TOKEN_BLACKLIST_ENABLED, "true")
            .withProperty(SessionStoreStartupGuard.IDLE_TRACKING_ENABLED, "true")
            .withProperty(SessionStoreStartupGuard.REDIS_HOST, "redis.railway.internal")
            .withProperty(SessionStoreStartupGuard.REDIS_PORT, "6379");
    }

    @Test
    @DisplayName("developer, test and dev profiles with no switch set start on the in-memory stores")
    void nonProductionWithoutSwitchesStarts() {
        for (String profile : List.of("dev", "test", "local", "local-h2", "uat", "staging")) {
            assertThatCode(() -> SessionStoreStartupGuard.check(environment(profile)))
                .as(profile).doesNotThrowAnyException();
        }
        MockEnvironment devOff = environment("dev")
            .withProperty(SessionStoreStartupGuard.TOKEN_BLACKLIST_ENABLED, "false")
            .withProperty(SessionStoreStartupGuard.IDLE_TRACKING_ENABLED, "FALSE")
            .withProperty(SessionStoreStartupGuard.REDIS_HOST, "localhost");
        assertThatCode(() -> SessionStoreStartupGuard.check(devOff)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("production with both switches on and a real Redis address starts")
    void configuredProductionStarts() {
        assertThatCode(() -> SessionStoreStartupGuard.check(configuredProduction())).doesNotThrowAnyException();
        MockEnvironment upperCase = configuredProduction()
            .withProperty(SessionStoreStartupGuard.TOKEN_BLACKLIST_ENABLED, "TRUE");
        assertThatCode(() -> SessionStoreStartupGuard.check(upperCase)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a switch that is neither true nor false stops the start, in any profile")
    void anUnrecognisedSwitchValueIsRefused() {
        for (String profile : List.of("dev", "test", "local-h2", "prod")) {
            for (String value : List.of("enable", "yes", "1", "", " true", "on")) {
                MockEnvironment blacklist = environment(profile)
                    .withProperty(SessionStoreStartupGuard.TOKEN_BLACKLIST_ENABLED, value);
                assertThatThrownBy(() -> SessionStoreStartupGuard.check(blacklist))
                    .as(profile + " blacklist=" + value)
                    .isInstanceOf(StartupConfigurationException.class)
                    .hasMessageContaining("REDIS_TOKEN_BLACKLIST_ENABLED")
                    .hasMessageContaining("must be true or false");

                MockEnvironment idle = environment(profile)
                    .withProperty(SessionStoreStartupGuard.IDLE_TRACKING_ENABLED, value);
                assertThatThrownBy(() -> SessionStoreStartupGuard.check(idle))
                    .as(profile + " idle=" + value)
                    .isInstanceOf(StartupConfigurationException.class)
                    .hasMessageContaining("AUTH_IDLE_TRACKING_ENABLED")
                    .hasMessageContaining("must be true or false");
            }
        }
    }

    @Test
    @DisplayName("production without the token blacklist on Redis is refused, unset or false")
    void productionRequiresTheRedisBlacklist() {
        MockEnvironment missing = environment("prod")
            .withProperty(SessionStoreStartupGuard.IDLE_TRACKING_ENABLED, "true")
            .withProperty(SessionStoreStartupGuard.REDIS_HOST, "redis.railway.internal")
            .withProperty(SessionStoreStartupGuard.REDIS_PORT, "6379");
        assertThatThrownBy(() -> SessionStoreStartupGuard.check(missing))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("REDIS_TOKEN_BLACKLIST_ENABLED=true")
            .hasMessageContaining("not set");

        MockEnvironment off = configuredProduction()
            .withProperty(SessionStoreStartupGuard.TOKEN_BLACKLIST_ENABLED, "false");
        assertThatThrownBy(() -> SessionStoreStartupGuard.check(off))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("REDIS_TOKEN_BLACKLIST_ENABLED=true");
    }

    @Test
    @DisplayName("production without the Redis idle tracker is refused, unset or false")
    void productionRequiresTheRedisIdleTracker() {
        MockEnvironment missing = environment("prod")
            .withProperty(SessionStoreStartupGuard.TOKEN_BLACKLIST_ENABLED, "true")
            .withProperty(SessionStoreStartupGuard.REDIS_HOST, "redis.railway.internal")
            .withProperty(SessionStoreStartupGuard.REDIS_PORT, "6379");
        assertThatThrownBy(() -> SessionStoreStartupGuard.check(missing))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("AUTH_IDLE_TRACKING_ENABLED=true");

        MockEnvironment off = configuredProduction()
            .withProperty(SessionStoreStartupGuard.IDLE_TRACKING_ENABLED, "false");
        assertThatThrownBy(() -> SessionStoreStartupGuard.check(off))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("AUTH_IDLE_TRACKING_ENABLED=true");
    }

    @Test
    @DisplayName("production with an empty or loopback Redis host is refused")
    void productionRequiresARealRedisHost() {
        for (String host : List.of("", "   ", "localhost", "LOCALHOST", "127.0.0.1", "::1")) {
            MockEnvironment env = configuredProduction().withProperty(SessionStoreStartupGuard.REDIS_HOST, host);
            assertThatThrownBy(() -> SessionStoreStartupGuard.check(env))
                .as("host='" + host + "'")
                .isInstanceOf(StartupConfigurationException.class)
                .hasMessageContaining("REDIS_HOST");
        }
        MockEnvironment noHost = environment("prod")
            .withProperty(SessionStoreStartupGuard.TOKEN_BLACKLIST_ENABLED, "true")
            .withProperty(SessionStoreStartupGuard.IDLE_TRACKING_ENABLED, "true");
        assertThatThrownBy(() -> SessionStoreStartupGuard.check(noHost))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("REDIS_HOST");
    }

    @Test
    @DisplayName("production with a Redis port that is not a port number is refused")
    void productionRequiresAValidRedisPort() {
        for (String port : List.of("", "abc", "0", "65536", "-1", "6379x")) {
            MockEnvironment env = configuredProduction().withProperty(SessionStoreStartupGuard.REDIS_PORT, port);
            assertThatThrownBy(() -> SessionStoreStartupGuard.check(env))
                .as("port='" + port + "'")
                .isInstanceOf(StartupConfigurationException.class)
                .hasMessageContaining("REDIS_PORT");
        }
    }

    @Test
    @DisplayName("a Redis URL, when set, is what production checks: its host must not be loopback")
    void aRedisUrlIsCheckedInsteadOfHostAndPort() {
        MockEnvironment remote = configuredProduction()
            .withProperty(SessionStoreStartupGuard.REDIS_HOST, "localhost")
            .withProperty(SessionStoreStartupGuard.REDIS_URL, "redis://default:secret@redis.railway.internal:6379");
        assertThatCode(() -> SessionStoreStartupGuard.check(remote)).doesNotThrowAnyException();

        MockEnvironment loopback = configuredProduction()
            .withProperty(SessionStoreStartupGuard.REDIS_URL, "redis://default:secret@localhost:6379");
        assertThatThrownBy(() -> SessionStoreStartupGuard.check(loopback))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("SPRING_DATA_REDIS_URL")
            .hasMessageNotContaining("secret");
    }

    @Test
    @DisplayName("a Railway environment named prod is production whatever the profile says")
    void aRailwayProductionEnvironmentIsProduction() {
        for (String variable : SessionStoreStartupGuard.PLATFORM_ENVIRONMENT_NAMES) {
            for (String name : List.of("prod", "production", "Production")) {
                MockEnvironment env = environment("dev").withProperty(variable, name);
                assertThat(SessionStoreStartupGuard.isProduction(env)).as(variable + "=" + name).isTrue();
                assertThatThrownBy(() -> SessionStoreStartupGuard.check(env))
                    .as(variable + "=" + name)
                    .isInstanceOf(StartupConfigurationException.class)
                    .hasMessageContaining("REDIS_TOKEN_BLACKLIST_ENABLED=true");
            }
            MockEnvironment dev = environment("dev").withProperty(variable, "dev");
            assertThat(SessionStoreStartupGuard.isProduction(dev)).isFalse();
            assertThatCode(() -> SessionStoreStartupGuard.check(dev)).doesNotThrowAnyException();
        }
        assertThat(SessionStoreStartupGuard.isProduction(environment("production"))).isTrue();
        assertThat(SessionStoreStartupGuard.isProduction(environment("uat"))).isFalse();
    }

    @Test
    @DisplayName("with the committed configuration files: dev starts with no variable, prod needs them")
    void committedConfigurationFiles() throws Exception {
        assertThatCode(() -> SessionStoreStartupGuard.check(fromCommittedFiles("dev", Map.of())))
            .doesNotThrowAnyException();
        assertThatCode(() -> SessionStoreStartupGuard.check(fromCommittedFiles("test", Map.of())))
            .doesNotThrowAnyException();

        StandardEnvironment bareProd = fromCommittedFiles("prod", Map.of());
        assertThatThrownBy(() -> SessionStoreStartupGuard.check(bareProd))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("REDIS_TOKEN_BLACKLIST_ENABLED");

        StandardEnvironment prodWithoutHost = fromCommittedFiles("prod", Map.of(
            "REDIS_TOKEN_BLACKLIST_ENABLED", "true",
            "AUTH_IDLE_TRACKING_ENABLED", "true"));
        assertThatThrownBy(() -> SessionStoreStartupGuard.check(prodWithoutHost))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("REDIS_HOST");

        StandardEnvironment configuredProd = fromCommittedFiles("prod", Map.of(
            "REDIS_TOKEN_BLACKLIST_ENABLED", "true",
            "AUTH_IDLE_TRACKING_ENABLED", "true",
            "REDIS_HOST", "redis.railway.internal",
            "REDIS_PORT", "6379"));
        assertThatCode(() -> SessionStoreStartupGuard.check(configuredProd)).doesNotThrowAnyException();
        assertThat(configuredProd.getProperty("management.health.redis.enabled"))
            .as("production counts Redis in its health").isEqualTo("true");

        StandardEnvironment devWithTypo = fromCommittedFiles("dev", Map.of("AUTH_IDLE_TRACKING_ENABLED", "enable"));
        assertThatThrownBy(() -> SessionStoreStartupGuard.check(devWithTypo))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("AUTH_IDLE_TRACKING_ENABLED");
    }

    @Test
    @DisplayName("the guard is registered, so every start runs it")
    void theGuardIsRegistered() throws Exception {
        Properties factories = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/META-INF/spring.factories"))) {
            factories.load(in);
        }
        assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
            .contains(StartupProfileGuard.class.getName())
            .contains(SessionStoreStartupGuard.class.getName());
    }

    /**
     * The environment a start would see: the given variables (standing in for the process
     * environment, so the machine running the test does not leak in), the profile's own file when
     * there is one, then {@code application.properties}.
     */
    private static StandardEnvironment fromCommittedFiles(String profile, Map<String, Object> variables)
        throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("variables", new HashMap<>(variables)));
        Path profileFile = Path.of("src/main/resources/application-" + profile + ".yml");
        if (Files.exists(profileFile)) {
            for (PropertySource<?> source
                : new YamlPropertySourceLoader().load(profile, new FileSystemResource(profileFile))) {
                sources.addLast(source);
            }
        }
        for (PropertySource<?> source : new PropertiesPropertySourceLoader()
            .load("application", new FileSystemResource("src/main/resources/application.properties"))) {
            sources.addLast(source);
        }
        environment.setActiveProfiles(profile);
        return environment;
    }
}
