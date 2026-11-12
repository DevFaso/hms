package com.example.hms.service.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.hms.exception.NotificationTransportUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Against a local stub HTTP server (JDK {@code HttpServer}): nothing here reaches
 * Orange, and no SMS is ever sent. The stub records every request and answers
 * from a script, so the tests can count paid send attempts exactly.
 */
@DisplayName("OrangeSmsGatewayImpl")
class OrangeSmsGatewayImplTest {

    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret-value";
    private static final String TOKEN_1 = "tok-AAAA-1111";
    private static final String TOKEN_2 = "tok-BBBB-2222";
    private static final String SEND_PATH = "/smsmessaging/v1/outbound/tel%3A%2B2260000/requests";

    /** One scripted answer. */
    private record Answer(int status, String body, Map<String, String> headers, long delayMs) {
        static Answer of(int status, String body) {
            return new Answer(status, body, Map.of(), 0);
        }
    }

    /** One recorded request. */
    private record Seen(String rawPath, String rawQuery, String authorization, String body) {
    }

    private HttpServer server;
    private String baseUrl;
    private final Deque<Answer> tokenAnswers = new ArrayDeque<>();
    private final Deque<Answer> sendAnswers = new ArrayDeque<>();
    private final List<Seen> tokenRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<Seen> sendRequests = Collections.synchronizedList(new ArrayList<>());

    private MutableClock clock;
    private final AtomicLong fakeNanos = new AtomicLong(1_000_000_000L);
    private final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
    private IkoddiGatewayImpl legacy;
    private SmsRecipientAllowlist allowlist = SmsRecipientAllowlist.unrestricted();

    private ListAppender<ILoggingEvent> logs;
    private Logger gatewayLogger;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/oauth/v3/token", exchange -> answer(exchange, tokenRequests, tokenAnswers));
        server.createContext("/smsmessaging/v1/outbound/", exchange -> answer(exchange, sendRequests, sendAnswers));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        clock = new MutableClock(Instant.parse("2026-11-11T08:00:00Z"));
        legacy = mock(IkoddiGatewayImpl.class);

        gatewayLogger = (Logger) LoggerFactory.getLogger(OrangeSmsGatewayImpl.class);
        logs = new ListAppender<>();
        logs.start();
        gatewayLogger.addAppender(logs);
    }

    @AfterEach
    void stopStub() {
        gatewayLogger.detachAppender(logs);
        server.stop(0);
    }

    private static void answer(HttpExchange exchange, List<Seen> seen, Deque<Answer> script) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(),
            exchange.getRequestHeaders().getFirst("Authorization"), body));
        Answer a = script.isEmpty() ? Answer.of(500, "{\"error\":\"unscripted\"}") : script.poll();
        if (a.delayMs() > 0) {
            try {
                Thread.sleep(a.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = a.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        a.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(a.status(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private OrangeSmsGatewayImpl gateway(String senderName, boolean onNet) {
        return gateway(CLIENT_ID, CLIENT_SECRET, "tel:+2260000", senderName, onNet);
    }

    private OrangeSmsGatewayImpl gateway(String id, String secret, String address, String senderName, boolean onNet) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofMillis(400));
        RestClient client = RestClient.builder().requestFactory(factory).build();
        return new OrangeSmsGatewayImpl(legacy, client, baseUrl, id, secret,
            new OrangeSmsGatewayImpl.Sender(address, senderName, onNet), "226", allowlist, clock, fakeNanos::get,
            nanos -> {
                sleeps.add(nanos);
                fakeNanos.addAndGet(nanos);
            });
    }

    private static String token(String value, String expiresIn) {
        return "{\"token_type\":\"Bearer\",\"access_token\":\"" + value + "\",\"expires_in\":" + expiresIn
            + ",\"scope\":\"ope:smsmessaging:v1:access\"}";
    }

    private static Answer accepted(String resourceId) {
        return Answer.of(201, "{\"outboundSMSMessageRequest\":{\"address\":\"tel:+22670707070\","
            + "\"senderAddress\":\"tel:+2260000\",\"resourceURL\":\"https://api.orange.com/smsmessaging/v1/outbound/"
            + "tel%3A%2B2260000/requests/" + resourceId + "\"}}");
    }

    @Test
    @DisplayName("the OAuth token is fetched once with Basic client credentials and reused for later sends")
    void tokenIsReused() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "\"3600\"")));
        sendAnswers.add(accepted("r1"));
        sendAnswers.add(accepted("r2"));
        OrangeSmsGatewayImpl gateway = gateway("", false);

        gateway.sendSms(List.of("+22670707070"), "Bonjour", null);
        clock.advance(Duration.ofMinutes(30));
        gateway.sendSms(List.of("+22670707071"), "Bonjour", null);

        assertThat(tokenRequests).hasSize(1);
        Seen tokenRequest = tokenRequests.get(0);
        assertThat(tokenRequest.authorization()).isEqualTo("Basic " + Base64.getEncoder()
            .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)));
        assertThat(tokenRequest.body()).isEqualTo("grant_type=client_credentials");
        assertThat(sendRequests).hasSize(2).allSatisfy(s -> assertThat(s.authorization()).isEqualTo("Bearer " + TOKEN_1));
    }

    @Test
    @DisplayName("the token is replaced shortly before it expires, not after a failed send")
    void tokenRefreshedBeforeExpiry() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        tokenAnswers.add(Answer.of(200, token(TOKEN_2, "3600")));
        sendAnswers.add(accepted("r1"));
        sendAnswers.add(accepted("r2"));
        sendAnswers.add(accepted("r3"));
        OrangeSmsGatewayImpl gateway = gateway("", false);

        gateway.sendSms(List.of("+22670707070"), "a", null);
        clock.advance(Duration.ofSeconds(3530));
        gateway.sendSms(List.of("+22670707070"), "b", null);
        clock.advance(Duration.ofSeconds(20));
        gateway.sendSms(List.of("+22670707070"), "c", null);

        assertThat(tokenRequests).hasSize(2);
        assertThat(sendRequests).extracting(Seen::authorization)
            .containsExactly("Bearer " + TOKEN_1, "Bearer " + TOKEN_1, "Bearer " + TOKEN_2);
    }

    @Test
    @DisplayName("a 401 on send refreshes the token once and sends once more")
    void refreshOnceOn401() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        tokenAnswers.add(Answer.of(200, token(TOKEN_2, "3600")));
        sendAnswers.add(Answer.of(401, "{\"code\":42,\"message\":\"Expired credentials\"}"));
        sendAnswers.add(accepted("r1"));

        IkoddiGateway.SmsDispatch dispatch = gateway("", false).sendSms(List.of("+22670707070"), "a", null);

        assertThat(dispatch.delivered()).isTrue();
        assertThat(tokenRequests).hasSize(2);
        assertThat(sendRequests).extracting(Seen::authorization)
            .containsExactly("Bearer " + TOKEN_1, "Bearer " + TOKEN_2);
    }

    @Test
    @DisplayName("a second 401 is an authentication failure, never a third attempt")
    void secondUnauthorizedStops() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        tokenAnswers.add(Answer.of(200, token(TOKEN_2, "3600")));
        sendAnswers.add(Answer.of(401, "{}"));
        sendAnswers.add(Answer.of(401, "{}"));
        sendAnswers.add(accepted("never"));
        OrangeSmsGatewayImpl gateway = gateway("", false);

        assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070"), "a", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(OrangeSmsGatewayImpl.KEY_AUTH_FAILED);
        assertThat(sendRequests).hasSize(2);
    }

    @Test
    @DisplayName("a 5xx is not retried: Orange may have accepted, and a duplicate is charged")
    void noRetryOn5xx() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(Answer.of(503, "{\"error\":\"busy\"}"));
        sendAnswers.add(accepted("never"));
        OrangeSmsGatewayImpl gateway = gateway("", false);

        assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070"), "a", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(OrangeSmsGatewayImpl.KEY_UNKNOWN_OUTCOME);
        assertThat(sendRequests).hasSize(1);
        assertThat(tokenRequests).hasSize(1);
    }

    @Test
    @DisplayName("a timeout is not retried and reports an unknown outcome")
    void noRetryOnTimeout() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(new Answer(201, "{}", Map.of(), 1500));
        sendAnswers.add(accepted("never"));
        OrangeSmsGatewayImpl gateway = gateway("", false);

        assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070"), "a", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(OrangeSmsGatewayImpl.KEY_UNKNOWN_OUTCOME);
        assertThat(sendRequests).hasSize(1);
    }

    @Test
    @DisplayName("a 400 (an unapproved sender name, an exhausted bundle) is a refusal, sent once")
    void badRequestIsRejected() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(Answer.of(400, "{\"requestError\":{\"serviceException\":{\"messageId\":\"SVC0001\"}}}"));
        OrangeSmsGatewayImpl gateway = gateway("", false);

        assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070"), "a", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(OrangeSmsGatewayImpl.KEY_REJECTED);
        assertThat(sendRequests).hasSize(1);
    }

    @Test
    @DisplayName("a refused token request is an authentication failure and sends nothing")
    void refusedTokenSendsNothing() {
        tokenAnswers.add(Answer.of(401, "{\"error\":\"invalid_client\"}"));
        OrangeSmsGatewayImpl gateway = gateway("", false);

        assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070"), "a", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(OrangeSmsGatewayImpl.KEY_AUTH_FAILED);
        assertThat(sendRequests).isEmpty();
    }

    @Test
    @DisplayName("sends are spaced 200 ms apart: five per second in this process")
    void pacing() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(accepted("r1"));
        sendAnswers.add(accepted("r2"));
        sendAnswers.add(accepted("r3"));
        sendAnswers.add(accepted("r4"));
        OrangeSmsGatewayImpl gateway = gateway("", false);

        gateway.sendSms(List.of("+22670707070", "+22670707071", "+22670707072"), "a", null);

        // The fake clock stands still between sends, so the 2nd and 3rd each wait a full slot.
        assertThat(sleeps).containsExactly(OrangeSmsGatewayImpl.MIN_SEND_SPACING_NANOS,
            OrangeSmsGatewayImpl.MIN_SEND_SPACING_NANOS);

        sleeps.clear();
        fakeNanos.addAndGet(OrangeSmsGatewayImpl.MIN_SEND_SPACING_NANOS + 1);
        gateway.sendSms(List.of("+22670707073"), "b", null);
        assertThat(sleeps).as("a send after the slot has passed does not wait").isEmpty();
    }

    @Test
    @DisplayName("one request per recipient; a later failure reports the partial batch and attempts nothing more")
    void partialFailureIsReported() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(accepted("res-1"));
        sendAnswers.add(Answer.of(500, "{}"));
        sendAnswers.add(accepted("never"));

        IkoddiGateway.SmsDispatch dispatch = gateway("", false)
            .sendSms(List.of("+22670707070", "+22670707071", "+22670707072"), "a", null);

        assertThat(dispatch.delivered()).isFalse();
        assertThat(sendRequests).hasSize(2);
        assertThat(sendRequests.get(0).body()).contains("tel:+22670707070");
        assertThat(sendRequests.get(1).body()).contains("tel:+22670707071");
        assertThat(dispatch.rawResponse())
            .contains("res-1")
            .contains("\"failedRecipient\":\"+226*****71\"")
            .contains("\"notAttempted\":1")
            .contains(OrangeSmsGatewayImpl.KEY_UNKNOWN_OUTCOME)
            .doesNotContain("22670707071");
    }

    @Test
    @DisplayName("a failure on the first recipient throws: nothing was accepted")
    void firstFailureThrows() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(Answer.of(400, "{}"));
        OrangeSmsGatewayImpl gateway = gateway("", false);

        assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070", "+22670707071"), "a", null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(sendRequests).hasSize(1);
    }

    @Test
    @DisplayName("a refused number anywhere in the batch stops it before the first paid request")
    void batchValidatedFirst() {
        OrangeSmsGatewayImpl gateway = gateway("", false);

        assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070", "+33612345678"), "a", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(OrangeSmsGatewayImpl.KEY_RECIPIENT_NOT_SUPPORTED);
        assertThatThrownBy(() -> gateway.sendSms(List.of("+2267070707"), "a", null))
            .as("a Burkina number needs eight national digits")
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(tokenRequests).isEmpty();
        assertThat(sendRequests).isEmpty();
    }

    @Test
    @DisplayName("the sender address is URL-encoded once in the path and repeated as is in the body")
    void senderAddressEncoding() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(accepted("r1"));

        IkoddiGateway.SmsDispatch dispatch = gateway("", false).sendSms(List.of("70 70 70 70"), "Bonjour", null);

        Seen send = sendRequests.get(0);
        assertThat(send.rawPath()).isEqualTo(SEND_PATH);
        assertThat(send.rawQuery()).isNull();
        assertThat(send.body())
            .contains("\"address\":\"tel:+22670707070\"")
            .contains("\"senderAddress\":\"tel:+2260000\"")
            .contains("\"message\":\"Bonjour\"")
            .doesNotContain("senderName");
        assertThat(dispatch.delivered()).isTrue();
        assertThat(dispatch.rawResponse()).contains("requests/r1").contains("+226*****70");
    }

    @Test
    @DisplayName("the on-net flag adds the Orange-only resource parameter")
    void onNetQuery() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(accepted("r1"));

        gateway("", true).sendSms(List.of("+22670707070"), "a", null);

        assertThat(sendRequests.get(0).rawPath()).isEqualTo(SEND_PATH);
        assertThat(sendRequests.get(0).rawQuery()).isEqualTo("resource_type_parameter_management=SMS_OCB2");
    }

    @Test
    @DisplayName("the resource URL falls back to the Location header when the body has none")
    void locationHeaderKept() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(new Answer(201, "", Map.of("Location", "https://api.orange.com/x/requests/loc-9"), 0));

        IkoddiGateway.SmsDispatch dispatch = gateway("", false).sendSms(List.of("+22670707070"), "a", null);

        assertThat(dispatch.delivered()).isTrue();
        assertThat(dispatch.rawResponse()).contains("requests/loc-9");
    }

    @Test
    @DisplayName("an approved sender name of up to 11 letters, digits or spaces is sent; a longer one disables the gateway")
    void senderNameLength() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(accepted("r1"));

        OrangeSmsGatewayImpl eleven = gateway("eKeneya HMS", false);
        assertThat(eleven.isConfigured()).isTrue();
        eleven.sendSms(List.of("+22670707070"), "a", null);
        assertThat(sendRequests.get(0).body()).contains("\"senderName\":\"eKeneya HMS\"");

        OrangeSmsGatewayImpl twelve = gateway("eKeneya HMS1", false);
        OrangeSmsGatewayImpl hyphen = gateway("e-Keneya", false);
        assertThat(twelve.isConfigured()).isFalse();
        assertThat(hyphen.isConfigured()).as("special characters are refused by Orange").isFalse();
        assertThatThrownBy(() -> twelve.sendSms(List.of("+22670707070"), "a", null))
            .isInstanceOf(NotificationTransportUnavailableException.class);
        assertThat(sendRequests).hasSize(1);
    }

    @Test
    @DisplayName("without client credentials or with a malformed sender address the gateway is not configured")
    void unconfigured() {
        assertThat(gateway("", "", "tel:+2260000", "", false).isConfigured()).isFalse();
        assertThat(gateway(CLIENT_ID, "", "tel:+2260000", "", false).isConfigured()).isFalse();
        assertThat(gateway(CLIENT_ID, CLIENT_SECRET, "2260000", "", false).isConfigured()).isFalse();
        OrangeSmsGatewayImpl none = gateway("", "", "tel:+2260000", "", false);
        assertThatThrownBy(() -> none.sendSms(List.of("+22670707070"), "a", null))
            .isInstanceOf(NotificationTransportUnavailableException.class);
        assertThat(tokenRequests).isEmpty();
        assertThat(sendRequests).isEmpty();
    }

    @Test
    @DisplayName("Orange has no OTP service: sendOtp is unsupported and verifyOtp only serves legacy IKODDI challenges")
    void otpContract() {
        OrangeSmsGatewayImpl gateway = gateway("", false);
        assertThatThrownBy(() -> gateway.sendOtp("+22670707070", IkoddiGateway.OtpChannel.SMS))
            .isInstanceOf(UnsupportedOperationException.class);

        when(legacy.verifyOtp("+22670707070", "123456", "tok-legacy"))
            .thenReturn(new IkoddiGateway.OtpVerification(0, "ok"));
        assertThat(gateway.verifyOtp("+22670707070", "123456", "tok-legacy").matched()).isTrue();
        verify(legacy).verifyOtp("+22670707070", "123456", "tok-legacy");
        assertThat(tokenRequests).isEmpty();
    }

    @Test
    @DisplayName("logs never carry the token, the client secret or a full phone number")
    void logsAreMasked() {
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        tokenAnswers.add(Answer.of(200, token(TOKEN_2, "3600")));
        sendAnswers.add(Answer.of(401, "{}"));
        sendAnswers.add(Answer.of(400, "{\"requestError\":{\"text\":\"invalid address tel:+22670707070\"}}"));
        OrangeSmsGatewayImpl gateway = gateway("", false);

        assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070"), "a", null))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.sendSms(List.of("+33612345678"), "a", null))
            .isInstanceOf(IllegalArgumentException.class);
        OrangeSmsGatewayImpl none = gateway("", "", "tel:+2260000", "", false);
        assertThatThrownBy(() -> none.sendSms(List.of("+22670707070"), "a", null))
            .isInstanceOf(NotificationTransportUnavailableException.class);

        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(line)
            .doesNotContain(TOKEN_1).doesNotContain(TOKEN_2)
            .doesNotContain(CLIENT_SECRET).doesNotContain(CLIENT_ID)
            .doesNotContain("22670707070").doesNotContain("33612345678")
            .doesNotContain(Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8))));
        assertThat(String.join("\n", lines)).contains("+226*****70").contains("[number]");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    @DisplayName("allowlist set: an unlisted recipient is not sent, not even a token is fetched, and it is a refusal")
    void allowlistRefusesUnlisted() {
        allowlist = SmsRecipientAllowlist.parse("+22670707070", "Orange");
        OrangeSmsGatewayImpl gateway = gateway("", false);

        assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707071"), "a", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED);
        assertThat(tokenRequests).isEmpty();
        assertThat(sendRequests).isEmpty();
    }

    @Test
    @DisplayName("allowlist set: a listed number is sent, whatever spelling the desk stored")
    void allowlistSendsListed() {
        allowlist = SmsRecipientAllowlist.parse(" +226 70 70 70 70 , +22670707072", "Orange");
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(accepted("r1"));

        IkoddiGateway.SmsDispatch dispatch = gateway("", false).sendSms(List.of("70 70 70 70"), "a", null);

        assertThat(dispatch.delivered()).isTrue();
        assertThat(sendRequests).hasSize(1);
        assertThat(sendRequests.get(0).body()).contains("tel:+22670707070");
    }

    @Test
    @DisplayName("allowlist set: in a mixed batch only listed numbers are sent and the dispatch reports the rest")
    void allowlistMixedBatch() {
        allowlist = SmsRecipientAllowlist.parse("+22670707072", "Orange");
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(accepted("r1"));
        sendAnswers.add(accepted("never"));

        IkoddiGateway.SmsDispatch dispatch = gateway("", false)
            .sendSms(List.of("+22670707070", "+22670707072"), "a", null);

        assertThat(sendRequests).hasSize(1);
        assertThat(sendRequests.get(0).body()).contains("tel:+22670707072");
        assertThat(dispatch.delivered()).as("not every recipient was accepted").isFalse();
        assertThat(dispatch.rawResponse())
            .contains("\"notAllowlisted\":[\"+226*****70\"]")
            .contains(SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED)
            .doesNotContain("22670707070");
    }

    @Test
    @DisplayName("allowlist empty (unset or blank): no restriction, for production")
    void emptyAllowlistIsUnrestricted() {
        for (String empty : new String[] {null, "", "   "}) {
            allowlist = SmsRecipientAllowlist.parse(empty, "Orange");
            assertThat(allowlist.restricts()).as("'%s' restricts nothing", empty).isFalse();
        }
        tokenAnswers.add(Answer.of(200, token(TOKEN_1, "3600")));
        sendAnswers.add(accepted("r1"));

        assertThat(gateway("", false).sendSms(List.of("+22670707071"), "a", null).delivered()).isTrue();
        assertThat(sendRequests).hasSize(1);
    }

    @Test
    @DisplayName("allowlist set but with no valid entry: restricted to no one, never read as allow-all")
    void invalidAllowlistAllowsNoOne() {
        for (String broken : new String[] {",", " , ,", "70707070", "not-a-number", "tel:+22670707070"}) {
            allowlist = SmsRecipientAllowlist.parse(broken, "Orange");
            assertThat(allowlist.restricts()).as("'%s' restricts", broken).isTrue();
            assertThat(allowlist.size()).as("'%s' lists nobody", broken).isZero();
            OrangeSmsGatewayImpl gateway = gateway("", false);
            assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070"), "a", null))
                .hasMessage(SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED);
        }
        assertThat(tokenRequests).isEmpty();
        assertThat(sendRequests).isEmpty();
    }

    @Test
    @DisplayName("allowlist refusals are logged masked")
    void allowlistLogIsMasked() {
        allowlist = SmsRecipientAllowlist.parse("+22670707070", "Orange");
        Logger allowlistLogger = (Logger) LoggerFactory.getLogger(SmsRecipientAllowlist.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        allowlistLogger.addAppender(captured);
        try {
            OrangeSmsGatewayImpl gateway = gateway("", false);
            assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707071"), "a", null))
                .isInstanceOf(IllegalStateException.class);
        } finally {
            allowlistLogger.detachAppender(captured);
        }
        List<String> lines = captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anyMatch(line -> line.contains("+226*****71") && line.contains("SMS_RECIPIENT_ALLOWLIST"));
        assertThat(lines).noneMatch(line -> line.contains("22670707071"));
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
