package com.example.hms.controller.support;

import com.example.hms.service.EmailService;
import com.example.hms.service.PasswordResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Groups password-reset and email-notification concerns into a single
 * injectable component so that {@code AuthController} stays within the
 * seven-parameter constructor limit (SonarQube java:S107).
 */
@Component
@RequiredArgsConstructor
public class AuthNotificationFacade {

    private final PasswordResetService passwordResetService;
    private final EmailService emailService;

    public PasswordResetService passwordReset() {
        return passwordResetService;
    }

    public EmailService email() {
        return emailService;
    }
}
