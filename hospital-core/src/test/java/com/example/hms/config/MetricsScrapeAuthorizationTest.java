package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * SecurityConfig is a {@code @RequiredArgsConstructor} bean with a wide collaborator list, none
     * of which the authorization gate touches. The generated constructor <em>is</em> invoked, with
     * a null for every collaborator — that is safe here precisely because the gate reads only the
     * {@code metricsScrapeToken} field and the request, so no null is ever dereferenced. This
     * keeps the test from having to mock a dozen unrelated beans.
     *
     * <p>The constructor is selected by arity rather than by {@code getDeclaredConstructors()[0]},
     * whose ordering the JLS does not specify.
     */
    private static SecurityConfig newSecurityConfig() throws ReflectiveOperationException {
        var ctor = Arrays.stream(SecurityConfig.class.getDeclaredConstructors())
            .max(Comparator.comparingInt(Constructor::getParameterCount))
            .orElseThrow(() -> new AssertionError("SecurityConfig has no constructor"));
        ctor.setAccessible(true);
        return (SecurityConfig) ctor.newInstance(new Object[ctor.getParameterCount()]);
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
    @DisplayName("a configured token stored with surrounding whitespace still matches")
    void configuredTokenIsTrimmed() throws ReflectiveOperationException {
        // Railway's UI keeps a trailing newline on a pasted secret. Comparing the presented
        // value trimmed against an untrimmed configured value 403s every correct scrape.
        for (String stored : List.of(TOKEN + "\n", " " + TOKEN, TOKEN + "  ")) {
            var gate = gateWithToken(stored);
            var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
            request.addHeader("X-Metrics-Token", TOKEN);
            assertThat(granted(gate, request))
                .as("configured token stored as [%s]", stored)
                .isTrue();
        }
    }

    @Test
    @DisplayName("an unrelated proxy-injected Bearer does not shadow a correct X-Metrics-Token")
    void bearerDoesNotShadowCustomHeader() throws ReflectiveOperationException {
        var gate = gateWithToken(TOKEN);

        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("X-Metrics-Token", TOKEN);
        request.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.some.jwt");

        assertThat(granted(gate, request))
            .as("a scraper behind an auth proxy still presents a valid scrape token")
            .isTrue();
    }

    @Test
    @DisplayName("/actuator/metrics is not exposed — it carries the same data as the gated endpoint")
    void metricsEndpointIsNotExposed() throws IOException {
        // Every source that declares the list, not just the global one. application-prod.yml
        // carries its own `exposure.include`, which WINS in prod — so checking only
        // application.properties would let `metrics` (or env, or heapdump) be re-added to the
        // prod profile with this test still green. Same prod-only divergence this PR fixed for
        // management.otlp.*.
        Properties props = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        String exposed = props.getProperty("management.endpoints.web.exposure.include", "");
        assertExposureIsSafe(exposed, "application.properties");

        for (String yaml : List.of("application-prod.yml", "application-dev.yml", "application-local.yml")) {
            Path path = Path.of("src/main/resources", yaml);
            if (!Files.exists(path)) {
                continue;
            }
            String body = Files.readString(path, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("include:\s*([^\n#]+)").matcher(body);
            while (m.find()) {
                assertExposureIsSafe(m.group(1), yaml);
            }
        }
    }

    private static void assertExposureIsSafe(String exposed, String source) {

        List<String> endpoints = Arrays.stream(exposed.split(","))
            .map(String::trim)
            .filter(e -> !e.isEmpty())
            .toList();

        // Trimmed, because "health,info, metrics" is the same exposure as "…,metrics" to
        // Spring but a different string to split(",").
        assertThat(endpoints)
            .as("%s: /actuator/metrics only ever sat behind anyRequest().authenticated(), so "
                + "any valid token — including a patient app's — could read the URI inventory",
                source)
            .doesNotContain("metrics");

        // And a wildcard exposes it without ever naming it.
        assertThat(endpoints)
            .as("%s: `*` exposes every endpoint including metrics, env, beans and heapdump",
                source)
            .doesNotContain("*");
    }

    @Test
    @DisplayName("every other actuator endpoint is default-denied, not merely authenticated")
    void actuatorSurfaceIsDefaultDenied() throws Exception {
        Path source = Path.of("src/main/java/com/example/hms/config/SecurityConfig.java");
        String body = Files.readString(source, StandardCharsets.UTF_8);

        assertThat(body)
            .as("a catch-all must stop a newly exposed actuator endpoint falling through to "
                + "anyRequest().authenticated()")
            .contains(".requestMatchers(\"/actuator/**\").hasAuthority(ROLE_SUPER_ADMIN)");
    }

    @Test
    @DisplayName("the metrics endpoint is never restored to permitAll()")
    void metricsEndpointIsNotPermitAll() throws Exception {
        Path source = Path.of("src/main/java/com/example/hms/config/SecurityConfig.java");
        assertThat(source).as("SecurityConfig source must be readable from the module dir").exists();

        // Whitespace-normalised, not line-by-line: a line filter is defeated by a formatter
        // wrapping the call as `.requestMatchers("/actuator/prometheus")\n    .permitAll()`,
        // which reopens the hole with the test still green.
        String normalised = Files.readString(source, StandardCharsets.UTF_8)
            .replaceAll("\\s+", " ");

        assertThat(normalised)
            .as("/actuator/prometheus must stay behind metricsScrapeAuthorization() — "
                + "it exposes the whole URI inventory and business counters")
            .doesNotContain("\"/actuator/prometheus\" ) .permitAll()")
            .doesNotContain("\"/actuator/prometheus\").permitAll()");

        // The matcher must still be present and gated, so deleting the line cannot pass either.
        assertThat(normalised)
            .as("the token gate must remain wired to the metrics endpoint")
            .contains(".requestMatchers(\"/actuator/prometheus\").access(metricsScrapeAuthorization())");
    }
}
