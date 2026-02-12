package com.example.hms.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String GHOST_TOKEN = "ghost-token";
    private static final String API_PATIENTS = "/api/patients";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Mock
    private JwtTokenProvider tokenProvider;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @Test
    void shouldSkipStaticAssetRequests() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/assets/config/feature-flags.json");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldSkipSpaFeatureFlagsRoute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/feature-flags");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldProcessApiRequests() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(API_PATIENTS);

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void shouldReturnUnauthorizedWhenUserMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI(API_PATIENTS);
    request.addHeader(AUTH_HEADER, BEARER_PREFIX + GHOST_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(tokenProvider.validateToken(GHOST_TOKEN)).thenReturn(true);
        when(tokenProvider.getAuthenticationFromJwt(GHOST_TOKEN))
            .thenThrow(new UsernameNotFoundException("missing"));
        when(tokenProvider.getUsernameFromJWT(GHOST_TOKEN)).thenReturn("dev_midwife_0007");

        filter.doFilter(request, response, chain);

        verify(tokenProvider).getAuthenticationFromJwt(GHOST_TOKEN);
        verify(tokenProvider).getUsernameFromJWT(GHOST_TOKEN);
        verify(tokenProvider, never()).extractHospitalContext(anyString(), any());
        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(SC_UNAUTHORIZED);
    }

    @Test
    void shouldShortCircuitCachedMissingPrincipal() throws Exception {
        MockHttpServletRequest firstRequest = new MockHttpServletRequest();
    firstRequest.setRequestURI(API_PATIENTS);
    firstRequest.addHeader(AUTH_HEADER, BEARER_PREFIX + GHOST_TOKEN);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(tokenProvider.validateToken(GHOST_TOKEN)).thenReturn(true);
        when(tokenProvider.getAuthenticationFromJwt(GHOST_TOKEN))
            .thenThrow(new UsernameNotFoundException("missing"));
        when(tokenProvider.getUsernameFromJWT(GHOST_TOKEN)).thenReturn("dev_midwife_0007");

        filter.doFilter(firstRequest, firstResponse, chain);

        assertThat(firstResponse.getStatus()).isEqualTo(SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());

        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
    secondRequest.setRequestURI(API_PATIENTS);
    secondRequest.addHeader(AUTH_HEADER, BEARER_PREFIX + GHOST_TOKEN);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        filter.doFilter(secondRequest, secondResponse, chain);

        verify(tokenProvider, times(1)).getAuthenticationFromJwt(GHOST_TOKEN);
        verify(tokenProvider, times(2)).getUsernameFromJWT(GHOST_TOKEN);
        verify(chain, never()).doFilter(any(), any());
        assertThat(secondResponse.getStatus()).isEqualTo(SC_UNAUTHORIZED);
    }
}
