package com.example.hms.logging;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Makes the log events dropped by {@link DropCountingAsyncAppender} visible.
 *
 * <ul>
 *   <li>Metric {@value #METRIC}: a counter of the events dropped since the JVM started.</li>
 *   <li>A WARN, on every report interval in which the count grew, with how many were dropped in that
 *   interval and in total. It reaches the console appender, which is synchronous and never drops.</li>
 * </ul>
 *
 * <p>Per instance by design (no scheduler lock): each JVM reports its own queue. Interval:
 * {@code hms.logging.async-drop-report-interval-ms} (default one minute).
 */
@Component
@Slf4j
public class AsyncLogDropMonitor {

    public static final String METRIC = "hms.logging.async.dropped";

    private final LongSupplier droppedSinceStart;
    private final AtomicLong lastReported = new AtomicLong();

    @Autowired
    public AsyncLogDropMonitor(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable(), DropCountingAsyncAppender::droppedSinceStart);
    }

    AsyncLogDropMonitor(MeterRegistry registry, LongSupplier droppedSinceStart) {
        this.droppedSinceStart = droppedSinceStart;
        this.lastReported.set(droppedSinceStart.getAsLong());
        if (registry != null) {
            // The monitor is the counter's state object (Micrometer holds it weakly): a singleton bean, so it lives
            // as long as the registry does.
            FunctionCounter.builder(METRIC, this, monitor -> monitor.droppedSinceStart.getAsLong())
                .description("Log events the asynchronous log shipper dropped because its queue was full")
                .baseUnit("events")
                .register(registry);
        }
    }

    /**
     * One report. Returns the number of events dropped since the previous report (0 when none).
     */
    @Scheduled(fixedDelayString = "${hms.logging.async-drop-report-interval-ms:60000}",
        initialDelayString = "${hms.logging.async-drop-report-interval-ms:60000}")
    public long report() {
        long total = droppedSinceStart.getAsLong();
        long since = total - lastReported.getAndSet(total);
        if (since > 0) {
            log.warn("[LOGGING] {} log event(s) dropped by the asynchronous log shipper since the last report "
                + "({} since start): its queue was full", since, total);
        }
        return Math.max(since, 0);
    }
}
