package com.example.hms.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RefreshTokenCookieService} (security task S-01). */
class RefreshTokenCookieServiceTest {

    @Test
    void write_sets_httpOnly_secure_sameSite_strict_cookie_scoped_to_auth() {
        var service = new RefreshTokenCookieService(true, "Strict");
        HttpServletResponse response = mock(HttpServletResponse.class);

        service.write(response, "rt-value", 60_000L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq("Set-Cookie"), captor.capture());

        String setCookie = captor.getValue();
        assertThat(setCookie)
                .contains("hms_refresh=rt-value")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api/auth")
                .contains("Max-Age=60");
    }

    @Test
    void write_skips_negative_maxAge_by_clamping_to_zero() {
        var service = new RefreshTokenCookieService(true, "Strict");
        HttpServletResponse response = mock(HttpServletResponse.class);

        service.write(response, "rt-value", -5_000L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq("Set-Cookie"), captor.capture());
        assertThat(captor.getValue()).contains("Max-Age=0");
    }

    @Test
    void write_supports_insecure_mode_for_local_dev() {
        var service = new RefreshTokenCookieService(false, "Lax");
        HttpServletResponse response = mock(HttpServletResponse.class);

        service.write(response, "rt-value", 30_000L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq("Set-Cookie"), captor.capture());
        String setCookie = captor.getValue();
        assertThat(setCookie)
                .doesNotContain("Secure")
                .contains("SameSite=Lax");
    }

    @Test
    void read_returns_cookie_value_when_present() {
        var service = new RefreshTokenCookieService(true, "Strict");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie("other", "x"),
                new Cookie("hms_refresh", "the-token")
        });

        assertThat(service.read(request)).isEqualTo("the-token");
    }

    @Test
    void read_returns_null_when_no_cookies_at_all() {
        var service = new RefreshTokenCookieService(true, "Strict");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(null);

        assertThat(service.read(request)).isNull();
    }

    @Test
    void read_returns_null_when_cookie_absent() {
        var service = new RefreshTokenCookieService(true, "Strict");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("session", "x")});

        assertThat(service.read(request)).isNull();
    }

    @Test
    void read_returns_null_for_blank_cookie_value() {
        var service = new RefreshTokenCookieService(true, "Strict");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("hms_refresh", "")});

        assertThat(service.read(request)).isNull();
    }

    @Test
    void clear_emits_expired_cookie_with_same_attributes() {
        var service = new RefreshTokenCookieService(true, "Strict");
        HttpServletResponse response = mock(HttpServletResponse.class);

        service.clear(response);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq("Set-Cookie"), captor.capture());
        String setCookie = captor.getValue();
        assertThat(setCookie)
                .contains("hms_refresh=")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api/auth");
    }
}
