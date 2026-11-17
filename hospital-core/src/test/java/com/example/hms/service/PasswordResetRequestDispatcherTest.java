package com.example.hms.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * QA D802: a known identifier answered in 209–574 ms and an unknown one in
 * 54–151 ms, because the token, its persistence and the send ran before the
 * 204. The dispatcher hands that work to its own pool, so the request thread
 * does the same thing for every identifier.
 */
class PasswordResetRequestDispatcherTest {

    private final PasswordResetService service = mock(PasswordResetService.class);

    @Test
    @DisplayName("dispatch only queues: the service is not called until the executor runs the task")
    void dispatchOnlyQueues() {
        List<Runnable> queued = new ArrayList<>();
        PasswordResetRequestDispatcher dispatcher = new PasswordResetRequestDispatcher(service, queued::add);

        dispatcher.dispatch("known@hospital.test", Locale.FRENCH, "10.0.0.1");

        verifyNoInteractions(service);
        assertThat(queued).hasSize(1);

        queued.get(0).run();
        verify(service).requestResetByIdentifier("known@hospital.test", Locale.FRENCH, "10.0.0.1");
    }

    @Test
    @DisplayName("a full queue drops the request without throwing and without touching the account")
    void aFullQueueDropsTheRequest() {
        PasswordResetRequestDispatcher dispatcher = new PasswordResetRequestDispatcher(service, task -> {
            throw new TaskRejectedException("full");
        });

        assertThatCode(() -> dispatcher.dispatch("known@hospital.test", Locale.ENGLISH, "10.0.0.1"))
            .doesNotThrowAnyException();
        verify(service, never()).requestResetByIdentifier(any(), any(), any());
    }

    @Test
    @DisplayName("a failure in the queued work stays on the worker")
    void aFailureStaysOnTheWorker() {
        List<Runnable> queued = new ArrayList<>();
        PasswordResetRequestDispatcher dispatcher = new PasswordResetRequestDispatcher(service, queued::add);
        doThrow(new IllegalStateException("db down"))
            .when(service).requestResetByIdentifier(any(), any(), any());

        dispatcher.dispatch("known@hospital.test", Locale.ENGLISH, "10.0.0.1");

        assertThatCode(() -> queued.get(0).run()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the production pool runs the account work on its own thread, not the caller's")
    void theProductionPoolRunsOffTheCallerThread() throws Exception {
        CompletableFuture<Thread> worker = new CompletableFuture<>();
        doAnswer(inv -> {
            worker.complete(Thread.currentThread());
            return null;
        }).when(service).requestResetByIdentifier(any(), any(), any());
        PasswordResetRequestDispatcher dispatcher = new PasswordResetRequestDispatcher(service);
        try {
            dispatcher.dispatch("known@hospital.test", Locale.ENGLISH, "10.0.0.1");

            Thread ran = worker.get(5, TimeUnit.SECONDS);
            assertThat(ran).isNotSameAs(Thread.currentThread());
            assertThat(ran.getName()).startsWith("password-reset-");
        } finally {
            dispatcher.destroy();
        }
    }
}
