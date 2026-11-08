package com.example.hms.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class JsonAccessDeniedHandlerTest {

    @Test
    void urlRuleRefusalCarriesAMessageAndTheCodeButNeverTheExceptionText() throws Exception {
        // QA D427: a 403 from the URL rules used to have no message at all.
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/users/x/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsonAccessDeniedHandler().handle(request, response,
            new AccessDeniedException("internal rule detail \"secret\""));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        String body = response.getContentAsString();
        assertThat(body).contains("\"message\":\"").contains("\"code\":\"ACCESS_DENIED\"")
            .contains("\"path\":\"/api/users/x/status\"")
            .doesNotContain("secret")
            .doesNotContain("\"message\":\"\"");
    }

    @Test
    void aCsrfRefusalHasItsOwnCodeSoThePortalRetriesInsteadOfShowingForbidden_D1145() throws Exception {
        // QA D1145: POST /billing-invoices/search answered 403 ACCESS_DENIED and
        // the portal showed "Acceso denegado" to a billing user who may search.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/billing-invoices/search");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsonAccessDeniedHandler().handle(request, response,
            new org.springframework.security.web.csrf.InvalidCsrfTokenException(
                new org.springframework.security.web.csrf.DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected"),
                "stale"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
            .contains("\"code\":\"CSRF_TOKEN_INVALID\"")
            .doesNotContain("ACCESS_DENIED")
            .doesNotContain("expected")
            .doesNotContain("stale");
    }

    @Test
    void escapesJson() {
        assertThat(JsonAccessDeniedHandler.jsonEscape("a\"b\\c\n")).isEqualTo("a\\\"b\\\\c\\n");
    }
}
