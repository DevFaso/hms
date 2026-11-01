package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /actuator/prometheus} used to be {@code permitAll()}. On Railway the management port is
 * served on the same public hostname as the API, so that published ~1,800 series anonymously on
 * dev and prod: the full URI inventory, JVM and Hikari internals, and the business counters.
 *
 * <p>These tests pin both halves of the fix — the matcher is no longer public, and the token gate
 * actually discriminates. The gate is exercised directly rather than through a {@code
 * @SpringBootTest}, because standing up the whole context to assert one authorization decision is
 * several orders of magnitude slower for no extra coverage.
 */
class MetricsScrapeAuthorizationTest {

    private static final String TOKEN = "s3cret-scrape-token";

    private static AuthorizationManager<RequestAuthorizationContext> gateWithToken(String configured)
            throws ReflectiveOperationException {
        SecurityConfig config = newSecurityConfig();
        Field tokenField = SecurityConfig.class.getDeclaredField("metricsScrapeToken");
        tokenField.setAccessible(true);
        tokenField.set(config, configured);

        Method gate = SecurityConfig.class.getDeclaredMethod("metricsScrapeAuthorization");
        gate.setAccessible(true);
        @SuppressWarnings("unchecked")
        AuthorizationManager<RequestAuthorizationContext> manager =
            (AuthorizationManager<RequestAuthorizationContext>) gate.invoke(config);
        return manager;
    }

    /**
     * SecurityConfig is a {@code @RequiredArgsConstructor} bean with a wide collaborator list, and
     * none of them are touched by the authorization gate. Allocating without running a constructor
     * keeps this test from having to mock a dozen unrelated beans.
     */
    private static SecurityConfig newSecurityConfig() throws ReflectiveOperationException {
        var ctor = SecurityConfig.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        return (SecurityConfig) ctor.newInstance(args);
    }

    private static boolean granted(AuthorizationManager<RequestAuthorizationContext> gate,
                                   MockHttpServletRequest request) {
        Supplier<Authentication> anonymous = () -> null;
        AuthorizationResult result = gate.authorize(anonymous, new RequestAuthorizationContext(request));
        return result != null && result.isGranted();
    }

    @Test
    @DisplayName("a request with no credential is refused")
    void anonymousScrapeIsRefused() throws ReflectiveOperationException {
        var gate = gateWithToken(TOKEN);
        assertThat(granted(gate, new MockHttpServletRequest("GET", "/actuator/prometheus"))).isFalse();
    }

    @Test
    @DisplayName("the configured token is accepted as a bearer token and as X-Metrics-Token")
    void correctTokenIsAccepted() throws ReflectiveOperationException {
        var gate = gateWithToken(TOKEN);

        var bearer = new MockHttpServletRequest("GET", "/actuator/prometheus");
        bearer.addHeader("Authorization", "Bearer " + TOKEN);
        assertThat(granted(gate, bearer)).isTrue();

        var custom = new MockHttpServletRequest("GET", "/actuator/prometheus");
        custom.addHeader("X-Metrics-Token", TOKEN);
        assertThat(granted(gate, custom)).isTrue();
    }

    @Test
    @DisplayName("a wrong or truncated token is refused")
    void wrongTokenIsRefused() throws ReflectiveOperationException {
        var gate = gateWithToken(TOKEN);

        for (String presented : List.of("nope", "", TOKEN.substring(0, TOKEN.length() - 1), TOKEN + "x")) {
            var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
            request.addHeader("Authorization", "Bearer " + presented);
            assertThat(granted(gate, request))
                .as("presented=%s", presented)
                .isFalse();
        }
    }

    @Test
    @DisplayName("when no token is configured the endpoint is closed, not open")
    void unconfiguredTokenClosesTheEndpoint() throws ReflectiveOperationException {
        for (String configured : List.of("", "   ")) {
            var gate = gateWithToken(configured);

            var noHeader = new MockHttpServletRequest("GET", "/actuator/prometheus");
            assertThat(granted(gate, noHeader)).as("configured=%s, no header", configured).isFalse();

            // The dangerous case: a blank config must not make a blank credential match.
            var blankBearer = new MockHttpServletRequest("GET", "/actuator/prometheus");
            blankBearer.addHeader("Authorization", "Bearer " + configured);
            assertThat(granted(gate, blankBearer)).as("configured=%s, blank bearer", configured).isFalse();
        }
    }

    @Test
    @DisplayName("the metrics endpoint is never restored to permitAll()")
    void metricsEndpointIsNotPermitAll() throws Exception {
        Path source = Path.of("src/main/java/com/example/hms/config/SecurityConfig.java");
        assertThat(source).as("SecurityConfig source must be readable from the module dir").exists();

        List<String> offending = Files.readAllLines(source, StandardCharsets.UTF_8).stream()
            .filter(line -> line.contains("/actuator/prometheus"))
            .filter(line -> line.contains("permitAll"))
            .toList();

        assertThat(offending)
            .as("/actuator/prometheus must stay behind metricsScrapeAuthorization() — "
                + "it exposes the whole URI inventory and business counters")
            .isEmpty();
    }
}
