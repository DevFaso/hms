package com.example.hms.service.integration;

import com.example.hms.exception.NotificationTransportUnavailableException;
import com.example.hms.utility.ActivationDeliveryTracker;
import com.example.hms.utility.PhoneNumbers;
import com.example.hms.utility.SmsCountryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * {@link IkoddiGateway} backed by the Orange SMS API (Burkina Faso offer), the
 * primary SMS provider. The interface keeps its historical name so no caller
 * changes; {@code app.sms.provider=ikoddi} switches back to
 * {@link IkoddiGatewayImpl}. There is no automatic fallback to IKODDI.
 *
 * <p>Design, mirroring the sister project ez-ticket's migration:
 * <ul>
 *   <li><b>Token:</b> OAuth v3 client credentials, reused until shortly before
 *       it expires, and refreshed once when a send answers 401.</li>
 *   <li><b>No other retry:</b> a 5xx, a timeout or a reset connection may come
 *       after Orange accepted the message, and every duplicate is charged, so
 *       the failure is reported and never resent here.</li>
 *   <li><b>Pacing:</b> at most five sends per second <em>per process</em>. This
 *       is not an account-wide limit: several replicas sharing one Orange
 *       account together can exceed Orange's aggregate five per second.</li>
 *   <li><b>One request per recipient.</b> If a later recipient fails, the
 *       earlier ones were already accepted: the dispatch reports the partial
 *       failure ({@code delivered=false}) and nothing is retried.</li>
 *   <li><b>Sender:</b> {@code senderAddress} ({@code tel:+2260000} for Burkina
 *       Faso) is URL-encoded once in the path and repeated in the body; an
 *       optional Orange-approved {@code senderName} of at most 11 letters,
 *       digits or spaces.</li>
 *   <li><b>Acceptance is not delivery:</b> {@code SmsDispatch.delivered} means
 *       Orange answered 201, not that the handset received it. The resource URL
 *       Orange returns is kept in {@code rawResponse}.</li>
 *   <li><b>Codes:</b> Orange sends plain SMS only. One-time codes are generated,
 *       hashed and checked locally ({@code PhoneVerificationServiceImpl});
 *       {@link #sendOtp} is unsupported, and {@link #verifyOtp} only verifies a
 *       challenge IKODDI issued before the switch.</li>
 * </ul>
 *
 * <p>Logs never carry the token, the client secret or a full phone number.
 * Failures surface as {@link IllegalStateException} carrying a message key,
 * which the callers already catch; an unconfigured gateway surfaces as
 * {@link NotificationTransportUnavailableException} (503), as IKODDI's does.
 */
@Component
@Primary
@Conditional(SmsProvider.OrangeSelected.class)
public class OrangeSmsGatewayImpl implements IkoddiGateway {

    private static final Logger LOG = LoggerFactory.getLogger(OrangeSmsGatewayImpl.class);

    /** Five sends per second: one admission every 200 ms. */
    static final long MIN_SEND_SPACING_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    /** A token is replaced this long before Orange says it expires. */
    static final long TOKEN_EXPIRY_MARGIN_SECONDS = 60;

    static final String KEY_REJECTED = "sms.provider.rejected";
    static final String KEY_UNKNOWN_OUTCOME = "sms.provider.unknownOutcome";
    static final String KEY_AUTH_FAILED = "sms.provider.authFailed";
    static final String KEY_UNREACHABLE = "sms.provider.unreachable";
    static final String KEY_RECIPIENT_NOT_SUPPORTED = "sms.recipient.notSupported";
    static final String KEY_MESSAGE_REQUIRED = "sms.message.required";

    private static final Pattern SENDER_ADDRESS = Pattern.compile("tel:\\+[1-9]\\d{1,14}");
    private static final Pattern SENDER_NAME = Pattern.compile("[A-Za-z0-9 ]{1,11}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Sleeps for a number of nanoseconds; swapped in tests so pacing needs no wall clock. */
    @FunctionalInterface
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final IkoddiGatewayImpl legacy;
    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final String senderAddress;
    private final String senderName;
    private final String countryNumberCode;
    private final boolean senderValid;
    private final URI tokenUri;
    private final URI sendUri;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    /** Non-production safety net: when set, only these numbers are texted (see {@link SmsRecipientAllowlist}). */
    private final SmsRecipientAllowlist allowlist;

    private final Object tokenLock = new Object();
    private String accessToken;
    private Instant tokenRefreshAt = Instant.MIN;

    private final Object paceLock = new Object();
    private boolean anySent;
    private long lastSendNanos;

    @Autowired
    public OrangeSmsGatewayImpl(
        IkoddiGatewayImpl legacy,
        RestClient.Builder restClientBuilder,
        @Value("${app.sms.provider:orange}") String provider,
        @Value("${app.orange-sms.base-url:https://api.orange.com}") String baseUrl,
        @Value("${app.orange-sms.client-id:}") String clientId,
        @Value("${app.orange-sms.client-secret:}") String clientSecret,
        @Value("${app.orange-sms.sender-address:tel:+2260000}") String senderAddress,
        @Value("${app.orange-sms.sender-name:}") String senderName,
        @Value("${app.orange-sms.on-net:false}") boolean onNet,
        @Value("${app.ikoddi.country-number-code:226}") String countryNumberCode,
        @Value("${app.orange-sms.connect-timeout-ms:5000}") int connectTimeoutMs,
        @Value("${app.orange-sms.read-timeout-ms:15000}") int readTimeoutMs,
        @Value("${app.sms.recipient-allowlist:}") String recipientAllowlist
    ) {
        this(legacy, buildClient(restClientBuilder, connectTimeoutMs, readTimeoutMs), baseUrl, clientId, clientSecret,
            new Sender(senderAddress, senderName, onNet), countryNumberCode,
            SmsRecipientAllowlist.parse(recipientAllowlist, "Orange"),
            Clock.systemUTC(), System::nanoTime, TimeUnit.NANOSECONDS::sleep);
        String selected = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!selected.isEmpty() && !"orange".equals(selected)) {
            LOG.warn("app.sms.provider='{}' is not 'orange' or 'ikoddi'; Orange is used", provider);
        }
    }

    /** Package-private: tests pass a stub server's client, a fixed clock and a fake sleeper. */
    @SuppressWarnings("java:S107")
    OrangeSmsGatewayImpl(IkoddiGatewayImpl legacy, RestClient restClient, String baseUrl,
                         String clientId, String clientSecret, Sender sender, String countryNumberCode,
                         SmsRecipientAllowlist allowlist, Clock clock, LongSupplier nanoTime, Sleeper sleeper) {
        this.legacy = legacy;
        this.allowlist = allowlist == null ? SmsRecipientAllowlist.unrestricted() : allowlist;
        this.restClient = restClient;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.countryNumberCode = StringUtils.hasText(countryNumberCode) ? countryNumberCode.trim()
            : PhoneNumbers.DEFAULT_CALLING_CODE;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;

        String address = sender.address() == null ? "" : sender.address().trim();
        String name = sender.name() == null ? "" : sender.name().trim();
        boolean valid = true;
        if (!SENDER_ADDRESS.matcher(address).matches()) {
            LOG.error("Orange SMS disabled: ORANGE_SMS_SENDER_ADDRESS must be tel:+ followed by international "
                + "digits (tel:+2260000 for Burkina Faso)");
            valid = false;
        }
        if (!name.isEmpty() && !SENDER_NAME.matcher(name).matches()) {
            LOG.error("Orange SMS disabled: ORANGE_SMS_SENDER_NAME must be at most 11 letters, digits or spaces "
                + "(it is {} characters)", name.length());
            valid = false;
        }
        this.senderValid = valid;
        this.senderAddress = address;
        this.senderName = name;

        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.tokenUri = URI.create(base + "/oauth/v3/token");
        // Encoded exactly once: tel:+2260000 -> tel%3A%2B2260000. Built as a URI so the
        // client does not expand or re-encode it.
        this.sendUri = URI.create(base + "/smsmessaging/v1/outbound/"
            + URLEncoder.encode(valid ? address : "tel:+0", StandardCharsets.UTF_8) + "/requests"
            + (sender.onNet() ? "?resource_type_parameter_management=SMS_OCB2" : ""));
    }

    private static RestClient buildClient(RestClient.Builder builder, int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.clone().requestFactory(requestFactory).build();
    }

    @Override
    public boolean isConfigured() {
        return senderValid && StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    /** Orange has no OTP service: codes are generated and checked locally, then sent with {@link #sendSms}. */
    @Override
    public OtpDispatch sendOtp(String identityE164, OtpChannel channel) {
        throw new UnsupportedOperationException("Orange SMS sends locally generated codes through sendSms");
    }

    /**
     * Only for a challenge IKODDI issued before the switch, during its short
     * lifetime. Throws {@link NotificationTransportUnavailableException} when the
     * IKODDI credentials are gone; the caller then treats the challenge as expired.
     */
    @Override
    public OtpVerification verifyOtp(String identityE164, String code, String verificationKey) {
        return legacy.verifyOtp(identityE164, code, verificationKey);
    }

    @Override
    public SmsDispatch sendSms(List<String> recipientsE164, String message, String campaignName) {
        requireConfigured();
        if (recipientsE164 == null || recipientsE164.isEmpty() || !StringUtils.hasText(message)) {
            throw new IllegalArgumentException(KEY_MESSAGE_REQUIRED);
        }
        // The whole batch is checked before the first paid request.
        List<String> targets = new ArrayList<>(recipientsE164.size());
        for (String raw : recipientsE164) {
            String e164 = toE164(raw);
            if (!SmsCountryPolicy.allows(e164)) {
                LOG.warn("Orange SMS not sent: {} is not a supported number (E.164, WAEMU countries only)", mask(e164));
                throw new IllegalArgumentException(KEY_RECIPIENT_NOT_SUPPORTED);
            }
            targets.add(e164);
        }
        // Recipient allowlist (dev/uat): a number not on it is never sent, whoever the caller is.
        ArrayNode notAllowlisted = JsonNodeFactory.instance.arrayNode();
        List<String> allowed = new ArrayList<>(targets.size());
        for (String target : targets) {
            if (allowlist.permits(target)) {
                allowed.add(target);
            } else {
                allowlist.logRefused("Orange", target);
                notAllowlisted.add(mask(target));
            }
        }
        if (allowed.isEmpty()) {
            throw new IllegalStateException(SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED);
        }
        ArrayNode accepted = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < allowed.size(); i++) {
            String target = allowed.get(i);
            try {
                accepted.add(sendOne(target, message));
            } catch (IllegalStateException ex) {
                if (i == 0) {
                    throw ex;
                }
                int notAttempted = allowed.size() - i - 1;
                LOG.warn("Orange SMS partial failure: {} of {} recipients accepted, {} failed ({}), {} not attempted. "
                        + "Do not resend the whole batch: the accepted messages were charged.",
                    i, allowed.size(), mask(target), ex.getMessage(), notAttempted);
                return new SmsDispatch(false,
                    summary(accepted, notAllowlisted, mask(target), ex.getMessage(), notAttempted));
            }
        }
        return new SmsDispatch(notAllowlisted.isEmpty(), summary(accepted, notAllowlisted, null, null, 0));
    }

    /** One paid request. Returns Orange's acceptance record (masked recipient, resource URL). */
    private ObjectNode sendOne(String e164, String message) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("address", "tel:" + e164);
        request.put("senderAddress", senderAddress);
        if (!senderName.isEmpty()) {
            request.put("senderName", senderName);
        }
        request.put("outboundSMSTextMessage", Map.of("message", message));
        Map<String, Object> body = Map.of("outboundSMSMessageRequest", request);

        for (int attempt = 0; attempt < 2; attempt++) {
            String token = token();
            pace();
            try {
                ResponseEntity<String> response = restClient.post()
                    .uri(sendUri)
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
                if (response.getStatusCode().value() != 201) {
                    LOG.warn("Orange SMS to {} answered status {} instead of 201; acceptance is unknown and it is "
                        + "not resent", mask(e164), response.getStatusCode().value());
                    throw new IllegalStateException(KEY_UNKNOWN_OUTCOME);
                }
                return acceptance(e164, response);
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 401 && attempt == 0) {
                    // Not accepted, so sending again is safe: refresh the token once.
                    LOG.info("Orange SMS token refused (401); requesting a new token and sending once more");
                    invalidate(token);
                    continue;
                }
                LOG.warn("Orange SMS to {} failed with status {}: {}", mask(e164), status,
                    IkoddiGatewayImpl.safeGatewayBody(ex.getResponseBodyAsString()));
                // No cause attached: Spring's message quotes the response body, which can hold the number.
                if (status == 401 || status == 403) {
                    throw new IllegalStateException(KEY_AUTH_FAILED);
                }
                throw new IllegalStateException(status >= 500 ? KEY_UNKNOWN_OUTCOME : KEY_REJECTED);
            } catch (RestClientException ex) {
                LOG.warn("Orange SMS to {} got no usable answer ({}); acceptance is unknown and it is not resent",
                    mask(e164), ex.getClass().getSimpleName());
                throw new IllegalStateException(KEY_UNKNOWN_OUTCOME);
            }
        }
        throw new IllegalStateException(KEY_AUTH_FAILED);
    }

    private ObjectNode acceptance(String e164, ResponseEntity<String> response) {
        String resourceUrl = null;
        String body = response.getBody();
        if (StringUtils.hasText(body)) {
            try {
                resourceUrl = text(JSON.readTree(body).path("outboundSMSMessageRequest"), "resourceURL");
            } catch (RuntimeException ex) {
                LOG.warn("Orange SMS accepted the message to {} but its answer is not JSON", mask(e164));
            }
        }
        if (resourceUrl == null) {
            resourceUrl = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("recipient", mask(e164));
        node.put("resourceURL", resourceUrl);
        return node;
    }

    private static String summary(ArrayNode accepted, ArrayNode notAllowlisted, String failedRecipient,
                                  String failure, int notAttempted) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("provider", "orange");
        node.set("accepted", accepted);
        if (!notAllowlisted.isEmpty()) {
            node.set("notAllowlisted", notAllowlisted);
            node.put("notAllowlistedReason", SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED);
        }
        if (failedRecipient != null) {
            node.put("failedRecipient", failedRecipient);
            node.put("failure", failure);
            node.put("notAttempted", notAttempted);
        }
        return node.toString();
    }

    /** The cached token, or a new one when it is missing or about to expire. */
    private String token() {
        synchronized (tokenLock) {
            if (accessToken != null && clock.instant().isBefore(tokenRefreshAt)) {
                return accessToken;
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            String raw;
            try {
                raw = restClient.post()
                    .uri(tokenUri)
                    .headers(headers -> headers.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            } catch (RestClientResponseException ex) {
                LOG.warn("Orange OAuth token request failed with status {}", ex.getStatusCode().value());
                throw new IllegalStateException(KEY_AUTH_FAILED);
            } catch (RestClientException ex) {
                LOG.warn("Orange OAuth token request got no answer ({})", ex.getClass().getSimpleName());
                throw new IllegalStateException(KEY_UNREACHABLE);
            }
            String token;
            long expiresIn;
            try {
                // Orange's own example sends expires_in as a string ("3600"); both forms are read.
                JsonNode node = JSON.readTree(raw == null ? "" : raw);
                token = text(node, "access_token");
                String expires = text(node, "expires_in");
                expiresIn = expires == null ? 0 : Long.parseLong(expires.trim());
            } catch (RuntimeException ex) {
                token = null;
                expiresIn = 0;
            }
            if (!StringUtils.hasText(token) || expiresIn <= 0) {
                LOG.warn("Orange OAuth answered without a usable access token");
                throw new IllegalStateException(KEY_AUTH_FAILED);
            }
            long margin = Math.min(TOKEN_EXPIRY_MARGIN_SECONDS, expiresIn / 2);
            accessToken = token;
            tokenRefreshAt = clock.instant().plusSeconds(Math.max(1, expiresIn - margin));
            LOG.info("Orange OAuth token obtained, valid {} s", expiresIn);
            return accessToken;
        }
    }

    private void invalidate(String rejected) {
        synchronized (tokenLock) {
            if (rejected.equals(accessToken)) {
                accessToken = null;
            }
        }
    }

    /** Admits one send every 200 ms in this process: five per second, NOT an account-wide limit. */
    private void pace() {
        synchronized (paceLock) {
            if (anySent) {
                long remaining = MIN_SEND_SPACING_NANOS - (nanoTime.getAsLong() - lastSendNanos);
                while (remaining > 0) {
                    try {
                        sleeper.sleepNanos(remaining);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(KEY_UNREACHABLE);
                    }
                    remaining = MIN_SEND_SPACING_NANOS - (nanoTime.getAsLong() - lastSendNanos);
                }
            }
            anySent = true;
            lastSendNanos = nanoTime.getAsLong();
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            LOG.warn("Orange SMS unavailable: client-id={}, client-secret={}, sender={}. Set ORANGE_SMS_CLIENT_ID, "
                    + "ORANGE_SMS_CLIENT_SECRET and a valid ORANGE_SMS_SENDER_ADDRESS / ORANGE_SMS_SENDER_NAME.",
                present(clientId), present(clientSecret), senderValid ? "valid" : "INVALID");
            throw new NotificationTransportUnavailableException("Text messaging is unavailable right now.");
        }
    }

    /** A number as the desk typed it, in E.164 ({@code +22670707070}). */
    private String toE164(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return "+" + PhoneNumbers.toInternationalDigits(raw, countryNumberCode);
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asString();
    }

    private static String mask(String phone) {
        String masked = ActivationDeliveryTracker.maskPhone(phone);
        return masked == null ? "<none>" : masked;
    }

    /** Log-safe presence marker, never the credential itself. */
    private static String present(String value) {
        return StringUtils.hasText(value) ? "set" : "MISSING";
    }

    /** The configured sender: address, optional approved name, and the Orange-only (on-net) offer flag. */
    record Sender(String address, String name, boolean onNet) {
    }
}
