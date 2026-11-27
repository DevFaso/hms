package com.example.hms.controller;

import com.example.hms.payload.dto.UsernameReminderRequestDTO;
import com.example.hms.service.UsernameReminderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/auth/username")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class UsernameReminderController {

    private final UsernameReminderService usernameReminderService;

    @PostMapping("/reminder")
    public ResponseEntity<Void> remind(
        @Valid @RequestBody UsernameReminderRequestDTO dto,
        Locale locale,
        HttpServletRequest request
    ) {
        String ip = clientIp(request);
        String identifier = dto.getIdentifier().trim();
        // The account work runs after the answer has gone: the answer, and the
        // time it takes, are the same whether or not the identifier names an
        // account. A full queue drops the request (the person can ask again).
        try {
            worker.execute(() -> remindQuietly(identifier, locale, ip));
        } catch (java.util.concurrent.RejectedExecutionException full) {
            log.warn("Username reminder dropped: the reminder queue is full");
        }
        return ResponseEntity.noContent().build();
    }

    private void remindQuietly(String identifier, Locale locale, String ip) {
        try {
            usernameReminderService.sendReminder(identifier, locale, ip);
        } catch (RuntimeException ex) {
            log.warn("Username reminder failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** Two daemon workers and a bounded queue, private to this controller. */
    private final java.util.concurrent.Executor worker = newWorker();

    private static java.util.concurrent.Executor newWorker() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(200),
            runnable -> {
                Thread thread = new Thread(runnable, "username-reminder");
                thread.setDaemon(true);
                return thread;
            });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static String clientIp(HttpServletRequest request) {
        return com.example.hms.security.ClientAddress.of(request);
    }
}
