package com.example.hms.service;

import java.util.concurrent.Executor;

/** Lets tests outside this package build a dispatcher on an executor they control. */
public final class PasswordResetRequestDispatcherTestSupport {

    private PasswordResetRequestDispatcherTestSupport() {
    }

    public static PasswordResetRequestDispatcher onExecutor(PasswordResetService service, Executor executor) {
        return new PasswordResetRequestDispatcher(service, executor);
    }
}
