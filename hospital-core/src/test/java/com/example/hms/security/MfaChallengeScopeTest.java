package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;

class MfaChallengeScopeTest {

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath("/api");
        return request;
    }

    @Test
    @DisplayName("the challenge token authenticates only the two first-enrolment requests")
    void admitsOnlyTheFirstEnrolmentRequests() {
        assertThat(MfaChallengeScope.admits(request("POST", "/api/auth/mfa/enroll"))).isTrue();
        assertThat(MfaChallengeScope.admits(request("POST", "/api/auth/mfa/verify-enrollment"))).isTrue();

        assertThat(MfaChallengeScope.admits(request("POST", "/api/auth/mfa/verify"))).isFalse();
        assertThat(MfaChallengeScope.admits(request("GET", "/api/auth/mfa/status"))).isFalse();
        assertThat(MfaChallengeScope.admits(request("GET", "/api/auth/mfa/enroll"))).isFalse();
        assertThat(MfaChallengeScope.admits(request("PUT", "/api/auth/credentials/mfa"))).isFalse();
        assertThat(MfaChallengeScope.admits(request("POST", "/api/auth/ws-ticket"))).isFalse();
        assertThat(MfaChallengeScope.admits(request("POST", "/api/auth/me/change-password"))).isFalse();
    }

    @Test
    @DisplayName("anything unusual in the path fails closed")
    void unusualPathsFailClosed() {
        assertThat(MfaChallengeScope.admits(request("POST", "/api/auth/mfa/enroll/"))).isFalse();
        assertThat(MfaChallengeScope.admits(request("POST", "/api/auth/mfa/%65nroll"))).isFalse();
        assertThat(MfaChallengeScope.admits(request("POST", "/auth/mfa/enroll"))).isFalse();
        assertThat(MfaChallengeScope.admits(request("POST", "/api/auth/mfa/enroll;x=1"))).isFalse();
    }

    @Test
    @DisplayName("a challenge session is told apart from a full one by its marker authority")
    void challengeSessionIsRecognised() {
        assertThat(MfaChallengeScope.isChallengeSession(new UsernamePasswordAuthenticationToken(
            "u", null, AuthorityUtils.createAuthorityList(MfaChallengeScope.AUTHORITY)))).isTrue();
        assertThat(MfaChallengeScope.isChallengeSession(new UsernamePasswordAuthenticationToken(
            "u", null, AuthorityUtils.createAuthorityList("ROLE_HOSPITAL_ADMIN")))).isFalse();
        assertThat(MfaChallengeScope.isChallengeSession(null)).isFalse();
    }
}
