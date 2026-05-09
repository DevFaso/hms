package com.example.hms.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies the async wrapping pattern from {@code logback-spring.xml}: an {@link
 * AsyncAppender} fronts {@link SplunkHecAppender} so the synchronous HEC POST never runs on
 * the caller thread.
 *
 * <p>The XML wiring itself is verified by Spring Boot at startup (a malformed
 * {@code logback-spring.xml} fails the application context). What this test asserts is the
 * <em>pattern</em>: events appended to the async wrapper end up at the Splunk appender, on
 * a different thread from the caller.
 */
class AsyncSplunkAppenderIntegrationTest {

    private LoggerContext loggerContext;
    private SplunkHecAppender splunk;
    private AsyncAppender async;
    private HttpClient mockClient;
    private Thread callerThread;
    private volatile Thread appenderThread;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        loggerContext = new LoggerContext();
        callerThread = Thread.currentThread();

        // Real Splunk appender, but with a mock HttpClient and a hook that captures the
        // executing thread so we can prove the work moved off the caller thread.
        mockClient = mock(HttpClient.class);
        HttpResponse<String> response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        try {
            when(mockClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    appenderThread = Thread.currentThread();
                    return response;
                });
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        splunk = new SplunkHecAppender();
        splunk.setContext(loggerContext);
        splunk.setEnabled(true);
        splunk.setUrl("https://splunk.example.com:8088");
        splunk.setToken("test-token");
        splunk.setIndex("main");
        splunk.setSource("hms-backend");
        splunk.setSourceType("spring-boot:json");
        splunk.setApplication("hms");
        splunk.setEnvironment("test");
        splunk.setHttpClient(mockClient);
        splunk.start();

        // Async wrapper — same configuration as logback-spring.xml's ASYNC_SPLUNK_HEC.
        async = new AsyncAppender();
        async.setContext(loggerContext);
        async.setName("ASYNC_SPLUNK_HEC");
        async.setQueueSize(512);
        async.setDiscardingThreshold(0);
        async.setNeverBlock(true);
        async.setIncludeCallerData(false);
        async.addAppender(splunk);
        async.start();
    }

    @AfterEach
    void tearDown() {
        // Stop async first so it flushes the queue, then stop splunk so isStarted() flips
        // back. Order matters — stopping splunk first would leave events in the async
        // queue draining into a stopped appender.
        if (async != null) async.stop();
        if (splunk != null) splunk.stop();
        if (loggerContext != null) loggerContext.stop();
    }

    @Test
    void asyncWrapper_forwardsEventsToSplunkAppender_onAWorkerThread() throws Exception {
        async.doAppend(makeEvent());

        // Stopping the async appender flushes its internal queue and waits up to its
        // configured timeout for the worker to drain — so by the time stop() returns,
        // any event we appended has reached the wrapped appender (or been dropped by
        // neverBlock=true if the queue was full, which it won't be here).
        async.stop();

        verify(mockClient, atLeastOnce()).send(any(HttpRequest.class), any());
        assertThat(appenderThread)
            .as("HEC POST must run on the async worker, not the caller")
            .isNotNull()
            .isNotSameAs(callerThread);
    }

    @Test
    void asyncWrapper_isProperlyStartedAndAttachedToSplunkAppender() {
        assertThat(async.isStarted()).isTrue();
        assertThat(async.getName()).isEqualTo("ASYNC_SPLUNK_HEC");
        assertThat(async.iteratorForAppenders().hasNext())
            .as("async wrapper must hold at least one nested appender")
            .isTrue();
        assertThat(async.iteratorForAppenders().next())
            .as("nested appender must be the SplunkHecAppender we configured")
            .isSameAs(splunk);
    }

    private LoggingEvent makeEvent() {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setLoggerName("test.async.splunk");
        event.setThreadName(Thread.currentThread().getName());
        event.setTimeStamp(1715252400000L);
        event.setMessage("hello via async");
        event.setMDCPropertyMap(java.util.Map.of());
        return event;
    }
}
