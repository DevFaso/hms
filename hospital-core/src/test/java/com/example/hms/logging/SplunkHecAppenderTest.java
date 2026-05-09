package com.example.hms.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.Mockito;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.ContextBase;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link SplunkHecAppender}.
 *
 * <p>The HttpClient is a Mockito mock so no network I/O happens. We assert wire-level details
 * (URL, headers, body shape) plus the appender's defensive behaviour: silent no-op when
 * disabled, refuses to start without credentials, never throws when HEC fails.
 */
class SplunkHecAppenderTest {

    private SplunkHecAppender appender;
    private HttpClient mockClient;
    private HttpResponse<String> mockResponse;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        appender = new SplunkHecAppender();
        Context ctx = new ContextBase();
        appender.setContext(ctx);

        mockClient = mock(HttpClient.class);
        mockResponse = (HttpResponse<String>) mock(HttpResponse.class);

        appender.setHttpClient(mockClient);
    }

    @Test
    void doesNotStart_whenDisabled() {
        appender.setEnabled(false);
        appender.start();

        // Disabled → still considered "started" so logback doesn't loop trying to start it,
        // but append must be a no-op.
        appender.append(makeEvent("hello"));
        verifyNoSendAttempt();
    }

    @Test
    void refusesToStart_whenEnabledWithoutUrl() {
        appender.setEnabled(true);
        appender.setUrl("");
        appender.setToken("dummy");
        appender.start();

        // Implementation contract: when enabled but blank URL, start() returns without calling
        // super.start() so isStarted() stays false and append() short-circuits.
        appender.append(makeEvent("dropped"));
        verifyNoSendAttempt();
        assertThat(appender.isStarted()).isFalse();
    }

    @Test
    void refusesToStart_whenEnabledWithoutToken() {
        appender.setEnabled(true);
        appender.setUrl("https://splunk.example.com:8088");
        appender.setToken("");
        appender.start();

        appender.append(makeEvent("dropped"));
        verifyNoSendAttempt();
        assertThat(appender.isStarted()).isFalse();
    }

    @Test
    void postsEventToConfiguredHecUrl_withSplunkAuthHeader() throws Exception {
        configureEnabledAppender();
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(mockResponse);

        appender.append(makeEvent("hello world"));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient, times(1)).send(captor.capture(), any());
        HttpRequest sent = captor.getValue();

        assertThat(sent.uri())
            .hasToString("https://splunk.example.com:8088/services/collector/event");
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.headers().firstValue("Authorization")).hasValue("Splunk test-token");
        assertThat(sent.headers().firstValue("Content-Type"))
            .hasValue("application/json; charset=utf-8");
    }

    @Test
    void serializesLevelLoggerThreadAndMessage_inEventBody() {
        configureEnabledAppender();
        // makeEvent already pins level/logger/thread; we assert against those defaults rather
        // than re-setting (LoggingEvent setters are all single-shot).
        LoggingEvent ev = makeEvent(Level.WARN, "a structured message");

        String json = appender.renderPayload(ev);

        assertThat(json)
            .contains("\"level\":\"WARN\"")
            .contains("\"logger\":\"test.logger\"")
            .contains("\"thread\":\"test-thread\"")
            .contains("\"message\":\"a structured message\"")
            .contains("\"sourcetype\":\"spring-boot:json\"")
            .contains("\"index\":\"main\"");
    }

    @Test
    void includesMdcAndExceptionDetails_whenPresent() {
        configureEnabledAppender();
        Map<String, String> mdc = new HashMap<>();
        mdc.put("traceId", "abc123");
        mdc.put("userId", "42");
        LoggingEvent ev = makeEvent(Level.ERROR, "kaboom", mdc);
        ev.setThrowableProxy(new ThrowableProxy(new IllegalStateException("DB down")));

        String json = appender.renderPayload(ev);

        assertThat(json)
            .contains("\"mdc\":{")
            .contains("\"traceId\":\"abc123\"")
            .contains("\"userId\":\"42\"")
            .contains("\"exception\":\"java.lang.IllegalStateException: DB down\"")
            .contains("\"stacktrace\":\"java.lang.IllegalStateException: DB down");
    }

    @Test
    void handlesHttp5xx_withoutThrowing_andCountsFailure() throws Exception {
        configureEnabledAppender();
        when(mockResponse.statusCode()).thenReturn(503);
        when(mockResponse.body()).thenReturn("upstream timeout");
        when(mockClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(mockResponse);

        // Must not throw — the contract is that a Splunk outage never crashes a request thread.
        appender.append(makeEvent("hi"));

        verify(mockClient, atLeastOnce()).send(any(HttpRequest.class), any());
        assertThat(appender.getFailureCount()).isEqualTo(1);
    }

    @Test
    void handlesIoException_withoutThrowing_andCountsFailure() throws Exception {
        configureEnabledAppender();
        when(mockClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
            .thenThrow(new IOException("connection refused"));

        appender.append(makeEvent("hi"));

        assertThat(appender.getFailureCount()).isEqualTo(1);
    }

    @Test
    void stripsTrailingSlashOnHecUrl_beforeAppendingPath() throws Exception {
        appender.setEnabled(true);
        appender.setUrl("https://splunk.example.com:8088/"); // trailing slash
        appender.setToken("test-token");
        appender.setIndex("main");
        appender.setSource("hms-backend");
        appender.setSourceType("spring-boot:json");
        appender.setApplication("hms");
        appender.setEnvironment("test");
        appender.start();
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(mockResponse);

        appender.append(makeEvent("hello"));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient).send(captor.capture(), any());
        // Single slash between host:port and the HEC path — not //services/...
        assertThat(captor.getValue().uri())
            .hasToString("https://splunk.example.com:8088/services/collector/event");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private void configureEnabledAppender() {
        appender.setEnabled(true);
        appender.setUrl("https://splunk.example.com:8088");
        appender.setToken("test-token");
        appender.setIndex("main");
        appender.setSource("hms-backend");
        appender.setSourceType("spring-boot:json");
        appender.setHost("test-host");
        appender.setApplication("hms");
        appender.setEnvironment("test");
        appender.start();
    }

    private void verifyNoSendAttempt() {
        try {
            verify(mockClient, never()).send(any(HttpRequest.class), any());
        } catch (Exception e) {
            // verify() throws checked exceptions because of the underlying mocked method.
            throw new AssertionError("Send was called when it should not have been", e);
        }
    }

    private LoggingEvent makeEvent(String message) {
        return makeEvent(Level.INFO, message, java.util.Map.of());
    }

    private LoggingEvent makeEvent(Level level, String message) {
        return makeEvent(level, message, java.util.Map.of());
    }

    /**
     * Both {@code setLevel} and {@code setMDCPropertyMap} are single-shot on Logback's
     * {@code LoggingEvent} — calling them twice throws {@code IllegalStateException}. So the
     * helper accepts both up-front rather than letting tests mutate the event after construction.
     * Without an attached LoggerContext (we don't run real Logback bootstrap in tests),
     * {@code getMDCPropertyMap()} would otherwise NPE on its lazy context lookup.
     */
    private LoggingEvent makeEvent(Level level, String message, java.util.Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName("test.logger");
        event.setThreadName("test-thread");
        event.setTimeStamp(1715252400000L); // fixed epoch ms for deterministic asserts
        event.setMessage(message);
        event.setMDCPropertyMap(mdc);
        return event;
    }
}
