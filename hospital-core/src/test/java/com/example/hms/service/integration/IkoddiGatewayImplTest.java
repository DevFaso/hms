package com.example.hms.service.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

@DisplayName("IkoddiGatewayImpl")
class IkoddiGatewayImplTest {

    private MockRestServiceServer server;
    private IkoddiGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ikoddi.test/api/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new IkoddiGatewayImpl(builder.build(), true, "key-123",
            "org-1", "otp-app-1", "HMS", "BF", "226");
    }

    @Test
    @DisplayName("sendOtp posts to the sms channel path with the api key and strips the leading +")
    void sendOtpPostsWithApiKey() {
        server.expect(requestTo("https://ikoddi.test/api/v1/groups/org-1/otp/otp-app-1/sms/22670707070"))
            .andExpect(method(POST))
            .andExpect(header("x-api-key", "key-123"))
            .andRespond(withSuccess("{\"status\":0,\"otpToken\":\"tok-1\"}", MediaType.APPLICATION_JSON));

        IkoddiGateway.OtpDispatch dispatch = gateway.sendOtp("+22670707070", IkoddiGateway.OtpChannel.SMS);

        assertThat(dispatch.accepted()).isTrue();
        assertThat(dispatch.otpToken()).isEqualTo("tok-1");
        server.verify();
    }

    @Test
    @DisplayName("verifyOtp posts verificationKey + code and reports a non-zero status as no match")
    void verifyOtpReportsMismatch() {
        server.expect(requestTo("https://ikoddi.test/api/v1/groups/org-1/otp/otp-app-1/verify"))
            .andExpect(method(POST))
            .andExpect(header("x-api-key", "key-123"))
            .andExpect(jsonPath("$.verificationKey").value("tok-1"))
            .andExpect(jsonPath("$.otp").value("123456"))
            .andExpect(jsonPath("$.identity").value("22670707070"))
            .andRespond(withSuccess("{\"status\":1,\"message\":\"OTP mismatch\"}", MediaType.APPLICATION_JSON));

        IkoddiGateway.OtpVerification verification = gateway.verifyOtp("+22670707070", "123456", "tok-1");

        assertThat(verification.matched()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("sendSms normalizes desk-typed local formats to the IKODDI identity")
    void sendSmsNormalizesLocalFormats() {
        server.expect(requestTo("https://ikoddi.test/api/v1/groups/org-1/sms"))
            .andExpect(method(POST))
            .andExpect(jsonPath("$.sentTo[0]").value("22670707070"))
            .andExpect(jsonPath("$.sentTo[1]").value("22670707070"))
            .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        // "70 70 70 70" (local, spaced) and "00226-70-70-70-70" (00-prefixed, dashed)
        gateway.sendSms(List.of("70 70 70 70", "00226-70-70-70-70"), "Bonjour", null);

        server.verify();
    }

    @Test
    @DisplayName("sendSms posts recipients, sender and country routing fields")
    void sendSmsPostsRoutingFields() {
        server.expect(requestTo("https://ikoddi.test/api/v1/groups/org-1/sms"))
            .andExpect(method(POST))
            .andExpect(jsonPath("$.sentTo[0]").value("22670707070"))
            .andExpect(jsonPath("$.from").value("HMS"))
            .andExpect(jsonPath("$.countryStringCode").value("BF"))
            .andExpect(jsonPath("$.countryNumberCode").value("226"))
            .andExpect(jsonPath("$.messageType").value("sms"))
            .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        IkoddiGateway.SmsDispatch dispatch = gateway.sendSms(List.of("+22670707070"), "Bonjour", null);

        assertThat(dispatch.delivered()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("unconfigured gateway refuses OTP calls with a clear IllegalStateException")
    void unconfiguredGatewayRefuses() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ikoddi.test/api/v1");
        IkoddiGatewayImpl unconfigured = new IkoddiGatewayImpl(builder.build(), false, "",
            "", "", "HMS", "BF", "226");

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.sendOtp("+22670707070", IkoddiGateway.OtpChannel.SMS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not configured");
    }
}
