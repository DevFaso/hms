package com.example.hms.security.oidc;

import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.context.HospitalContextRequestOverrides;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit-level coverage for {@link KeycloakHospitalContextFilter}. Verifies
 * that the filter (a) populates {@link HospitalContextHolder} from a
 * {@link JwtAuthenticationToken}, (b) leaves the holder alone for non-JWT
 * authentications, and (c) clears whatever it set in {@code finally},
 * even if downstream throws.
 */
class KeycloakHospitalContextFilterTest {

    private final KeycloakHospitalContextResolver resolver = new KeycloakHospitalContextResolver();
    // Tracker disabled so the gate short-circuits — these tests focus on the
    // existing populate/clear contract; idle behaviour is covered by
    // IdleSessionGateTest + JwtAuthenticationFilter integration tests.
    private final IdleSessionTracker disabledTracker = mock(IdleSessionTracker.class);
    private final IdleSessionGate idleSessionGate = new IdleSessionGate(disabledTracker, "");
    // Mockito's default Optional.empty() = "no local row", which matches the
    // pre-gate behaviour every existing test in this class was written for.
    private final com.example.hms.repository.UserRepository userRepository =
            mock(com.example.hms.repository.UserRepository.class);
    private final KeycloakHospitalContextFilter filter =
            new KeycloakHospitalContextFilter(resolver, idleSessionGate, userRepository);

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    @Test
    void populatesContextForKeycloakJwtAndClearsAfterwards() throws Exception {
        UUID hospital = UUID.randomUUID();
        Jwt jwt = jwt(Map.of(
                "preferred_username", "dr.alice",
                "hospital_id", hospital.toString(),
                "role_assignments", List.of("DOCTOR@" + hospital)));

        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")), "dr.alice");
        SecurityContextHolder.getContext().setAuthentication(auth);

        AtomicReference<HospitalContext> seenInsideChain = new AtomicReference<>();
        FilterChain chain = (req, resp) ->
                seenInsideChain.set(HospitalContextHolder.getContextOrEmpty());

        filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);

        assertThat(seenInsideChain.get().getActiveHospitalId()).isEqualTo(hospital);
        assertThat(seenInsideChain.get().getPermittedHospitalIds()).containsExactly(hospital);
        assertThat(HospitalContextHolder.getContext())
                .as("context must be cleared after the chain completes")
                .isEmpty();
    }

    @Test
    void honoursXHospitalIdHeaderOverrideForMultiHospitalUsers() throws Exception {
        UUID primary = UUID.randomUUID();
        UUID secondary = UUID.randomUUID();

        Jwt jwt = jwt(Map.of(
            "preferred_username", "dr.alice",
            "hospital_id", primary.toString(),
            "role_assignments", List.of(
                "DOCTOR@" + primary,
                "DOCTOR@" + secondary)));

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")), "dr.alice"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HospitalContextRequestOverrides.HEADER_HOSPITAL_ID, secondary.toString());

        AtomicReference<HospitalContext> seenInsideChain = new AtomicReference<>();
        FilterChain chain = (req, resp) ->
            seenInsideChain.set(HospitalContextHolder.getContextOrEmpty());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seenInsideChain.get().getActiveHospitalId())
            .as("X-Hospital-Id must override hospital_id claim when in permitted scope")
            .isEqualTo(secondary);
        assertThat(seenInsideChain.get().getPermittedHospitalIds())
            .as("permitted scope is preserved across the override")
            .containsExactlyInAnyOrder(primary, secondary);
    }

    @Test
    void ignoresXHospitalIdHeaderOutsidePermittedScope() throws Exception {
        UUID primary = UUID.randomUUID();
        UUID outOfScope = UUID.randomUUID();

        Jwt jwt = jwt(Map.of(
            "preferred_username", "dr.alice",
            "hospital_id", primary.toString(),
            "role_assignments", List.of("DOCTOR@" + primary)));

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")), "dr.alice"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HospitalContextRequestOverrides.HEADER_HOSPITAL_ID, outOfScope.toString());

        AtomicReference<HospitalContext> seenInsideChain = new AtomicReference<>();
        FilterChain chain = (req, resp) ->
            seenInsideChain.set(HospitalContextHolder.getContextOrEmpty());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seenInsideChain.get().getActiveHospitalId())
            .as("out-of-scope hospital must not become active even with the header set")
            .isEqualTo(primary);
    }

    @Test
    void leavesContextUntouchedForNonJwtAuthentication() throws Exception {
        Authentication anon = new AnonymousAuthenticationToken("k", "anon",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);

        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        assertThat(HospitalContextHolder.getContext()).isEmpty();
    }

    @Test
    void clearsContextEvenWhenDownstreamThrows() throws Exception {
        UUID hospital = UUID.randomUUID();
        Jwt jwt = jwt(Map.of("hospital_id", hospital.toString()));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")), "dr.alice"));

        FilterChain throwing = mock(FilterChain.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(throwing).doFilter(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        try {
            filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), throwing);
        } catch (RuntimeException expected) {
            // swallow — we only care about the cleanup assertion below
        }

        assertThat(HospitalContextHolder.getContext())
                .as("context must be cleared even when downstream throws")
                .isEmpty();
    }

    @Test
    void refusesKeycloakTokenWhenLocalAccountIsInactive() throws Exception {
        // Option A: accounts start inactive until the emailed code is
        // verified. The legacy path enforces that via
        // CustomUserDetails.isEnabled(); this pins that a valid Keycloak
        // token cannot outrun the local verification state.
        com.example.hms.model.User localUser = new com.example.hms.model.User();
        localUser.setActive(false);
        org.mockito.Mockito.when(userRepository.findByUsernameIgnoreCase("dr.alice"))
                .thenReturn(java.util.Optional.of(localUser));

        UUID hospital = UUID.randomUUID();
        Jwt jwt = jwt(Map.of(
                "preferred_username", "dr.alice",
                "hospital_id", hospital.toString(),
                "role_assignments", List.of("DOCTOR@" + hospital)));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")), "dr.alice"));

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, times(0)).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        assertThat(HospitalContextHolder.getContext())
                .as("no hospital context may be populated for a refused request")
                .isEmpty();
    }

    private static Jwt jwt(Map<String, Object> extraClaims) {
        java.util.Map<String, Object> claims = new java.util.HashMap<>(extraClaims);
        claims.putIfAbsent("sub", UUID.randomUUID().toString());
        Instant now = Instant.now();
        return new Jwt("token", now, now.plusSeconds(60),
                Map.of("alg", "RS256"), claims);
    }
}
