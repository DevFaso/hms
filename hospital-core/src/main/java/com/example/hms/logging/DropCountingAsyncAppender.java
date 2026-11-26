package com.example.hms.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Logback's {@link AsyncAppender}, with the events it drops counted.
 *
 * <p>With {@code neverBlock=true} the stock appender offers each event to its queue and, when the queue is
 * full, drops it without a trace. This one keeps the same non-blocking behaviour (a request thread never
 * waits for the queue to drain) and counts every event it could not queue, so the loss is visible:
 * {@link AsyncLogDropMonitor} exposes the count as a metric and logs a WARN to the console when it grows.
 *
 * <p>The count is exact: the capacity check and the offer happen under one short lock, and only the worker
 * thread removes from the queue, so capacity can only grow between the two. The lock is held for a constant
 * time (no wait on the queue), so it never stalls a caller the way a blocking {@code put} would.
 */
public class DropCountingAsyncAppender extends AsyncAppender {

    /** Every event dropped by any instance since the JVM started (logback may build the appender twice). */
    private static final AtomicLong DROPPED_SINCE_START = new AtomicLong();

    private final AtomicLong dropped = new AtomicLong();
    private final Object offerLock = new Object();

    /** Events dropped by every instance of this appender since the JVM started. */
    public static long droppedSinceStart() {
        return DROPPED_SINCE_START.get();
    }

    /** Events this instance dropped because its queue was full. */
    public long droppedCount() {
        return dropped.get();
    }

    @Override
    protected void put(ILoggingEvent event) {
        if (!isNeverBlock()) {
            super.put(event);
            return;
        }
        synchronized (offerLock) {
            if (getRemainingCapacity() == 0) {
                dropped.incrementAndGet();
                DROPPED_SINCE_START.incrementAndGet();
                return;
            }
            super.put(event);
        }
    }
}
