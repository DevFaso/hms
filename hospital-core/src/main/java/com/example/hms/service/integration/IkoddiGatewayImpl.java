package com.example.hms.service.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link IkoddiGateway} backed by Spring {@link RestClient}, following the
 * {@code DhisHttpClient} conventions: timeouts from properties, secrets from
 * env-injected config keys, package-private test constructor so unit tests can
 * bind a {@code MockRestServiceServer}. Transport/credential failures surface
 * as {@link IllegalStateException} for callers to translate to business errors.
 */
@Component
public class IkoddiGatewayImpl implements IkoddiGateway {

    private static final Logger LOG = LoggerFactory.getLogger(IkoddiGatewayImpl.class);

    // Fixed IKODDI v1 REST contract paths (the base URL is the configurable part).
    @SuppressWarnings("java:S1075")
    private static final String OTP_SEND_PATH = "/groups/{organizationId}/otp/{otpAppId}/{type}/{identity}";

    @SuppressWarnings("java:S1075")
    private static final String OTP_VERIFY_PATH = "/groups/{organizationId}/otp/{otpAppId}/verify";

    @SuppressWarnings("java:S1075")
    private static final String SMS_SEND_PATH = "/groups/{organizationId}/sms";

    private static final String API_KEY_HEADER = "x-api-key";

    private final RestClient restClient;
    private final boolean enabled;
    private final String apiKey;
    private final String organizationId;
    private final String otpAppId;
    private final String smsSender;
    private final String countryStringCode;
    private final String countryNumberCode;

    @org.springframework.beans.factory.annotation.Autowired
    public IkoddiGatewayImpl(
        RestClient.Builder restClientBuilder,
        @Value("${app.ikoddi.enabled:false}") boolean enabled,
        @Value("${app.ikoddi.api-key:}") String apiKey,
        @Value("${app.ikoddi.base-url:https://api.ikoddi.com/api/v1}") String baseUrl,
        @Value("${app.ikoddi.organization-id:}") String organizationId,
        @Value("${app.ikoddi.otp-app-id:}") String otpAppId,
        @Value("${app.ikoddi.sms-sender:HMS}") String smsSender,
        @Value("${app.ikoddi.country-string-code:BF}") String countryStringCode,
        @Value("${app.ikoddi.country-number-code:226}") String countryNumberCode,
        @Value("${app.ikoddi.connect-timeout-ms:5000}") int connectTimeoutMs,
        @Value("${app.ikoddi.read-timeout-ms:15000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.organizationId = organizationId;
        this.otpAppId = otpAppId;
        this.smsSender = smsSender;
        this.countryStringCode = countryStringCode;
        this.countryNumberCode = countryNumberCode;
    }

    /** Package-private test constructor: bind a MockRestServiceServer to the injected client. */
    IkoddiGatewayImpl(RestClient restClient, boolean enabled, String apiKey,
                      String organizationId, String otpAppId, String smsSender,
                      String countryStringCode, String countryNumberCode) {
        this.restClient = restClient;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.organizationId = organizationId;
        this.otpAppId = otpAppId;
        this.smsSender = smsSender;
        this.countryStringCode = countryStringCode;
        this.countryNumberCode = countryNumberCode;
    }

    @Override
    public boolean isConfigured() {
        return enabled
            && StringUtils.hasText(apiKey)
            && StringUtils.hasText(organizationId)
            && StringUtils.hasText(otpAppId);
    }

    @Override
    public OtpDispatch sendOtp(String identityE164, OtpChannel channel) {
        requireOtpConfigured();
        String identity = toIkoddiIdentity(identityE164);
        try {
            IkoddiOtpResponse response = restClient
                .post()
                .uri(OTP_SEND_PATH, organizationId, otpAppId, channel.wireToken(), identity)
                .headers(headers -> headers.set(API_KEY_HEADER, apiKey))
                .retrieve()
                .body(IkoddiOtpResponse.class);
            if (response == null) {
                throw new IllegalStateException("IKODDI returned an empty OTP send response");
            }
            return new OtpDispatch(response.status(), response.otpToken());
        } catch (RestClientResponseException ex) {
            throw requestFailure("OTP send", ex);
        }
    }

    @Override
    public OtpVerification verifyOtp(String identityE164, String code, String verificationKey) {
        requireOtpConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verificationKey", verificationKey);
        body.put("otp", code);
        body.put("identity", toIkoddiIdentity(identityE164));
        try {
            IkoddiOtpVerifyResponse response = restClient
                .post()
                .uri(OTP_VERIFY_PATH, organizationId, otpAppId)
                .headers(headers -> headers.set(API_KEY_HEADER, apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(IkoddiOtpVerifyResponse.class);
            if (response == null) {
                throw new IllegalStateException("IKODDI returned an empty OTP verify response");
            }
            return new OtpVerification(response.status(), response.message());
        } catch (RestClientResponseException ex) {
            throw requestFailure("OTP verify", ex);
        }
    }

    @Override
    public SmsDispatch sendSms(List<String> recipientsE164, String message, String campaignName) {
        requireSmsConfigured();
        List<String> recipients = recipientsE164.stream().map(this::toIkoddiIdentity).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sentTo", recipients);
        body.put("message", message);
        body.put("from", smsSender);
        if (StringUtils.hasText(campaignName)) {
            body.put("smsBroadCast", campaignName);
        }
        body.put("countryStringCode", countryStringCode);
        body.put("countryNumberCode", countryNumberCode);
        body.put("messageType", "sms");
        try {
            String response = restClient
                .post()
                .uri(SMS_SEND_PATH, organizationId)
                .headers(headers -> headers.set(API_KEY_HEADER, apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return new SmsDispatch(true, response);
        } catch (RestClientResponseException ex) {
            throw requestFailure("SMS send", ex);
        }
    }

    private void requireOtpConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                "IKODDI OTP is not configured. Set app.ikoddi.enabled plus api-key, organization-id and otp-app-id.");
        }
    }

    private void requireSmsConfigured() {
        if (!enabled || !StringUtils.hasText(apiKey) || !StringUtils.hasText(organizationId)) {
            throw new IllegalStateException(
                "IKODDI SMS is not configured. Set app.ikoddi.enabled plus api-key and organization-id.");
        }
    }

    private IllegalStateException requestFailure(String operation, RestClientResponseException ex) {
        LOG.warn("IKODDI {} failed for organizationId={} with status {}", operation, organizationId,
            ex.getStatusCode().value());
        return new IllegalStateException(
            "IKODDI " + operation + " request failed with status " + ex.getStatusCode().value(), ex);
    }

    /**
     * IKODDI addresses recipients by the international number without the leading
     * {@code +} (its examples use {@code 22670707070}). HMS stores phone numbers
     * exactly as typed at the desk ("70 70 70 70", "0022670707070", …), so the
     * gateway is the one place that guarantees wire format: digits only, {@code 00}
     * prefix stripped, and local-format numbers (≤ 10 digits) given the configured
     * country code.
     */
    private String toIkoddiIdentity(String rawPhone) {
        if (rawPhone == null) {
            return null;
        }
        String digits = rawPhone.trim().replaceAll("\\D", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (!digits.isEmpty() && digits.length() <= 10) {
            digits = countryNumberCode + digits;
        }
        return digits;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IkoddiOtpResponse(int status, String otpToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IkoddiOtpVerifyResponse(int status, String message) {}
}
