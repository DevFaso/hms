package com.example.hms.security.oidc;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
    private final KeycloakHospitalContextFilter filter = new KeycloakHospitalContextFilter(resolver);

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

    private static Jwt jwt(Map<String, Object> extraClaims) {
        java.util.Map<String, Object> claims = new java.util.HashMap<>(extraClaims);
        claims.putIfAbsent("sub", UUID.randomUUID().toString());
        Instant now = Instant.now();
        return new Jwt("token", now, now.plusSeconds(60),
                Map.of("alg", "RS256"), claims);
    }
}
