package com.example.hms.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The asynchronous log shipper stays non-blocking and counts what it drops: with the worker stuck on a slow
 * downstream and the queue full, an append returns at once and the event is counted, not silently lost.
 */
class DropCountingAsyncAppenderTest {

    private LoggerContext context;
    private DropCountingAsyncAppender async;
    private final CountDownLatch workerEntered = new CountDownLatch(1);
    private final CountDownLatch releaseWorker = new CountDownLatch(1);
    private final List<String> delivered = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        AppenderBase<ILoggingEvent> slowDownstream = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                workerEntered.countDown();
                try {
                    releaseWorker.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                delivered.add(event.getFormattedMessage());
            }
        };
        slowDownstream.setContext(context);
        slowDownstream.start();

        async = new DropCountingAsyncAppender();
        async.setContext(context);
        async.setName("ASYNC_TEST");
        async.setQueueSize(1);
        async.setDiscardingThreshold(0);
        async.setNeverBlock(true);
        async.setMaxFlushTime(5_000);
        async.addAppender(slowDownstream);
        async.start();
    }

    @AfterEach
    void tearDown() {
        releaseWorker.countDown();
        async.stop();
        context.stop();
    }

    @Test
    void aFullQueueDropsWithoutBlockingAndCountsEveryDrop() throws Exception {
        long sinceStartBefore = DropCountingAsyncAppender.droppedSinceStart();

        async.doAppend(event("first"));                  // taken by the worker, which then stalls
        assertThat(workerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        async.doAppend(event("second"));                 // fills the one-slot queue

        long started = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            async.doAppend(event("dropped " + i));
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis).as("appending to a full queue must not wait for it").isLessThan(1_000);
        assertThat(async.droppedCount()).isEqualTo(3);
        assertThat(DropCountingAsyncAppender.droppedSinceStart() - sinceStartBefore).isGreaterThanOrEqualTo(3);

        releaseWorker.countDown();
        async.stop();
        assertThat(delivered).containsExactly("first", "second");
    }

    @Test
    void nothingIsCountedWhileTheQueueHasRoom() throws Exception {
        releaseWorker.countDown();
        async.doAppend(event("one"));
        assertThat(workerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        async.stop();

        assertThat(async.droppedCount()).isZero();
        assertThat(delivered).containsExactly("one");
    }

    private static LoggingEvent event(String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setLoggerName("test.async.drop");
        event.setThreadName(Thread.currentThread().getName());
        event.setTimeStamp(System.currentTimeMillis());
        event.setMessage(message);
        event.setMDCPropertyMap(Map.of());
        return event;
    }
}
