package com.example.hms.architecture;

import com.example.hms.security.DowntimeStateService;
import com.example.hms.security.ReadOnlyModeFilter;
import com.example.hms.testsupport.ControllerMappingRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * User decision 2026-11-06: {@code ReadOnlyModeFilter} left every write under {@code /api/auth/} open.
 * Spring's mapping registry names every one of them, and each is classified here: a new one fails
 * until somebody says which it is, and each classification goes through the real filter.
 */
@DisplayName("read-only mode: every write under /auth is classified, and the filter does what the class says")
class ReadOnlyAuthWritesGuardTest {

    private enum Kind {
        /** Passes in read-only mode. */
        OPEN,
        /** Refused by the filter with the read-only answer. */
        REFUSED,
        /** Passes the filter, which runs before authentication; MfaController decides who it is. */
        OPEN_ONLY_WHEN_IT_BLOCKS_SIGN_IN
    }

    /** {@code METHOD path} of every write under /auth, with its class. */
    private static final Map<String, Kind> CLASSIFIED = Map.ofEntries(
        // The user's list: open.
        Map.entry("POST /auth/login", Kind.OPEN),
        Map.entry("POST /auth/logout", Kind.OPEN),
        Map.entry("POST /auth/token/refresh", Kind.OPEN),
        Map.entry("POST /auth/mfa/verify", Kind.OPEN),
        Map.entry("POST /auth/request-reset", Kind.OPEN),
        Map.entry("POST /auth/reset-password", Kind.OPEN),
        Map.entry("POST /auth/password/request", Kind.OPEN),
        Map.entry("POST /auth/password/request-reset", Kind.OPEN),
        Map.entry("POST /auth/password/confirm", Kind.OPEN),
        Map.entry("POST /auth/password/reset-password", Kind.OPEN),
        Map.entry("POST /auth/me/complete-setup", Kind.OPEN),
        // The user's list: open only for a required enrolment.
        Map.entry("POST /auth/mfa/enroll", Kind.OPEN_ONLY_WHEN_IT_BLOCKS_SIGN_IN),
        Map.entry("POST /auth/mfa/verify-enrollment", Kind.OPEN_ONLY_WHEN_IT_BLOCKS_SIGN_IN),
        // The user's list: refused.
        Map.entry("POST /auth/register", Kind.REFUSED),
        Map.entry("POST /auth/bootstrap-signup", Kind.REFUSED),
        Map.entry("POST /auth/me/change-username", Kind.REFUSED),
        Map.entry("POST /auth/me/change-email", Kind.REFUSED),
        Map.entry("POST /auth/me/change-email/confirm", Kind.REFUSED),
        Map.entry("PUT /auth/credentials/recovery", Kind.REFUSED),
        Map.entry("POST /auth/credentials/recovery/{contactId}/send-code", Kind.REFUSED),
        Map.entry("POST /auth/credentials/recovery/{contactId}/verify", Kind.REFUSED),
        Map.entry("PUT /auth/credentials/mfa", Kind.REFUSED),
        // Not on the user's lists, kept open and reported in the PR. A forced change on the native
        // apps goes through change-password, and the filter cannot tell it from a voluntary one.
        Map.entry("POST /auth/me/change-password", Kind.OPEN),
        // An account activated from a link sent before the downtime: without it, no sign-in.
        Map.entry("POST /auth/verify-email", Kind.OPEN),
        Map.entry("POST /auth/resend-verification", Kind.OPEN),
        // The other half of a forgotten credential: writes nothing.
        Map.entry("POST /auth/username/reminder", Kind.OPEN),
        // The lock screen and the notification socket: an open session stays readable. Neither writes data.
        Map.entry("POST /auth/verify-password", Kind.OPEN),
        Map.entry("POST /auth/ws-ticket", Kind.OPEN));

    private record AuthWrite(String method, String pattern) {
        String key() {
            return method + " " + pattern;
        }

        String uri() {
            return "/api" + pattern.replaceAll("\\{[^/}]*}", "00000000-0000-0000-0000-000000000001");
        }
    }

    private static List<AuthWrite> authWrites() {
        Set<RequestMethod> writes = Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);
        List<AuthWrite> found = new ArrayList<>();
        ControllerMappingRegistry.mapping().getHandlerMethods().forEach((info, handler) -> {
            if (info.getPathPatternsCondition() == null) {
                return;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (pattern.startsWith("/auth/")) {
                    info.getMethodsCondition().getMethods().stream().filter(writes::contains)
                        .forEach(method -> found.add(new AuthWrite(method.name(), pattern)));
                }
            }
        });
        return found;
    }

    @Test
    @DisplayName("every write under /auth is classified, and nothing classified has gone")
    void everyWriteIsClassified() {
        Set<String> present = new TreeSet<>();
        authWrites().forEach(write -> present.add(write.key()));
        assertThat(present).as("the registry reads the auth controllers").contains("POST /auth/login", "PUT /auth/credentials/mfa");
        Set<String> unclassified = new TreeSet<>(present);
        unclassified.removeAll(CLASSIFIED.keySet());
        assertThat(unclassified).as("classify it here and, if open, in ReadOnlyModeFilter.OPEN_AUTH_WRITES").isEmpty();
        Set<String> stale = new TreeSet<>(CLASSIFIED.keySet());
        stale.removeAll(present);
        assertThat(stale).as("classified, and no such route any more").isEmpty();
    }

    @Test
    @DisplayName("through the real filter, each write does what its class says")
    void theFilterAgreesWithTheClass() throws Exception {
        ReadOnlyModeFilter filter = readOnlyFilter();
        Map<String, Integer> wrong = new TreeMap<>();
        for (AuthWrite write : authWrites()) {
            MockHttpServletResponse response = send(filter, write.method(), write.uri());
            boolean refused = response.getStatus() == 503;
            if (refused != (CLASSIFIED.get(write.key()) == Kind.REFUSED)) {
                wrong.put(write.key(), response.getStatus());
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("the filter's open list holds nothing but routes classified as passing")
    void theOpenListIsExactlyTheOpenRoutes() {
        Set<String> expected = new TreeSet<>();
        authWrites().stream().filter(write -> CLASSIFIED.get(write.key()) != Kind.REFUSED)
            .forEach(write -> expected.add(write.uri()));
        assertThat(new TreeSet<>(ReadOnlyModeFilter.OPEN_AUTH_WRITES)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a refusal says it is the read-only mode, in the reader's language")
    void theRefusalIsTheReadOnlyAnswer() throws Exception {
        MockHttpServletResponse response = send(readOnlyFilter(), "POST", "/api/auth/register");
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("X-Readonly-Mode")).isEqualTo("true");
        assertThat(response.getContentAsString()).contains("READ_ONLY_MODE").contains("Lecture seule");
    }

    /**
     * The whole forced first sign-in during a downtime: the login, the setup page's write, the required
     * enrolment, the challenge's verification, then reading. The handlers are proved in
     * {@code MfaControllerTest} and {@code AuthControllerTest}; this is that the filter lets the walk through.
     */
    @Test
    @DisplayName("a user with a forced first-login step reaches reading during read-only")
    void aForcedFirstLoginReachesReading() throws Exception {
        ReadOnlyModeFilter filter = readOnlyFilter();
        for (String step : List.of("/api/auth/login", "/api/auth/me/complete-setup", "/api/auth/me/change-password",
                "/api/auth/mfa/enroll", "/api/auth/mfa/verify-enrollment", "/api/auth/mfa/verify", "/api/auth/token/refresh")) {
            assertThat(send(filter, "POST", step).getStatus()).as(step).isNotEqualTo(503);
        }
        assertThat(send(filter, "GET", "/api/patients").getStatus()).isNotEqualTo(503);
        assertThat(send(filter, "GET", "/api/auth/session/bootstrap").getStatus()).isNotEqualTo(503);
        assertThat(send(filter, "POST", "/api/patients").getStatus()).as("a write is still a write").isEqualTo(503);
    }

    @Test
    @DisplayName("a near-miss of an open path is refused, not let through by a prefix")
    void noPrefixLetsAWriteThrough() throws Exception {
        ReadOnlyModeFilter filter = readOnlyFilter();
        for (String uri : List.of("/api/auth/login/x", "/api/auth/new-write", "/api/auth/mfa", "/api/auth/")) {
            assertThat(send(filter, "POST", uri).getStatus()).as(uri).isEqualTo(503);
        }
    }

    private static MockHttpServletResponse send(ReadOnlyModeFilter filter, String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath("/api");
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @SuppressWarnings("unchecked")
    private static ReadOnlyModeFilter readOnlyFilter() {
        DowntimeStateService downtime = mock(DowntimeStateService.class);
        when(downtime.snapshot()).thenReturn(new DowntimeStateService.DowntimeSnapshot(true, null, null));
        ObjectProvider<HandlerMapping> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ControllerMappingRegistry.mapping());
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage(ReadOnlyModeFilter.DEFAULT_MESSAGE_KEY, Locale.FRENCH, "Lecture seule : la consultation reste possible.");
        return new ReadOnlyModeFilter(downtime, provider, messages, new FixedLocaleResolver(Locale.FRENCH));
    }
}
