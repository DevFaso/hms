package com.example.hms.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Dropped log events surface as a metric and as a WARN carrying the count, once per interval that saw drops. */
class AsyncLogDropMonitorTest {

    private final AtomicLong dropped = new AtomicLong(4);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AsyncLogDropMonitor.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void theMetricReadsTheDropCount() {
        AsyncLogDropMonitor monitor = new AsyncLogDropMonitor(registry, dropped::get);

        FunctionCounter counter = registry.get(AsyncLogDropMonitor.METRIC).functionCounter();
        assertThat(counter.count()).isEqualTo(4.0);
        dropped.addAndGet(3);
        assertThat(counter.count()).isEqualTo(7.0);
        assertThat(monitor.report()).isEqualTo(3);   // also keeps the counter's state object reachable
    }

    @Test
    void aReportWarnsWithTheDropsSinceTheLastReportAndStaysQuietOtherwise() {
        AsyncLogDropMonitor monitor = new AsyncLogDropMonitor(registry, dropped::get);

        assertThat(monitor.report()).as("drops before the monitor started are not re-reported").isZero();
        assertThat(logs.list).isEmpty();

        dropped.addAndGet(5);
        assertThat(monitor.report()).isEqualTo(5);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("5 log event(s) dropped").contains("9 since start");
        });

        assertThat(monitor.report()).isZero();
        assertThat(logs.list).hasSize(1);
    }

    @Test
    void worksWithoutAMeterRegistry() {
        AsyncLogDropMonitor monitor = new AsyncLogDropMonitor(null, dropped::get);
        dropped.incrementAndGet();
        assertThat(monitor.report()).isEqualTo(1);
    }
}
