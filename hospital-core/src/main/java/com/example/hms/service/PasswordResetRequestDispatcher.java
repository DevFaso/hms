package com.example.hms.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * QA D802: hands an anonymous password-reset request to a worker thread, so
 * the HTTP answer takes the same time whether or not the identifier names an
 * account.
 *
 * <p>Every reset endpoint answered 204 for any identifier, but a known account
 * paid for the token, its persistence and the e-mail or SMS before the answer
 * (209–574 ms over 24 samples) where an unknown one stopped at the lookup
 * (54–151 ms) — the clock told the caller what the status code hid. The
 * request thread now only submits the identifier here; the lookup, the token,
 * the send and the audit all run in {@link PasswordResetService#requestResetByIdentifier}
 * on this dispatcher's own pool, after the answer has gone.
 *
 * <p>The pool is private to this class, not a bean: a bean of type
 * {@link Executor} would switch off Spring Boot's {@code applicationTaskExecutor}.
 * Its queue is bounded; when it is full the request is dropped with a warning
 * (the holder can ask again) rather than run on the caller's thread, which
 * would bring the timing back. Rejection happens before the account is looked
 * up, so it says nothing about the account either.
 *
 * <p>Logs carry no identifier, address, phone number, link or token.
 */
@Component
@Slf4j
public class PasswordResetRequestDispatcher implements DisposableBean {

    private static final int WORKERS = 2;
    private static final int QUEUE_CAPACITY = 500;
    private static final int SHUTDOWN_GRACE_SECONDS = 10;

    private final PasswordResetService passwordResetService;
    private final Executor executor;
    /** The pool this dispatcher created and so must shut down; null when a test supplied the executor. */
    private final ThreadPoolTaskExecutor ownedPool;

    @Autowired
    public PasswordResetRequestDispatcher(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
        this.ownedPool = newPool();
        this.executor = ownedPool;
    }

    /** For tests: run the account work on the given executor. */
    PasswordResetRequestDispatcher(PasswordResetService passwordResetService, Executor executor) {
        this.passwordResetService = passwordResetService;
        this.ownedPool = null;
        this.executor = executor;
    }

    /**
     * Queue the request and return at once. Never throws and never touches the
     * account on the calling thread, so the caller's answer is the same for
     * every identifier.
     */
    public void dispatch(String identifier, Locale locale, String requestIp) {
        try {
            executor.execute(() -> run(identifier, locale, requestIp));
        } catch (RejectedExecutionException rejected) {
            log.warn("🔑 Password reset request dropped: the reset queue is full");
        }
    }

    private void run(String identifier, Locale locale, String requestIp) {
        try {
            passwordResetService.requestResetByIdentifier(identifier, locale, requestIp);
        } catch (RuntimeException ex) {
            // Nobody is waiting for this answer; the type is enough to diagnose
            // and carries no identifier.
            log.warn("🔑 Password reset request failed: {}", ex.getClass().getSimpleName());
        }
    }

    private static ThreadPoolTaskExecutor newPool() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(WORKERS);
        pool.setMaxPoolSize(WORKERS);
        pool.setQueueCapacity(QUEUE_CAPACITY);
        pool.setThreadNamePrefix("password-reset-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(SHUTDOWN_GRACE_SECONDS);
        pool.initialize();
        return pool;
    }

    @Override
    public void destroy() {
        if (ownedPool != null) {
            ownedPool.shutdown();
        }
    }
}
