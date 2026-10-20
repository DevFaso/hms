package com.example.hms.controller.support;

import com.example.hms.service.EmailService;
import com.example.hms.service.PasswordResetService;
import com.example.hms.service.UserRoleHospitalAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
    /**
     * QA D311/D418: re-sends an inactive account's assignment code (SMS and,
     * when the account has one, e-mail). Lazy so this facade adds no edge to
     * the bean graph the auth slices build.
     */
    private final ObjectProvider<UserRoleHospitalAssignmentService> assignmentService;

    public PasswordResetService passwordReset() {
        return passwordResetService;
    }

    public EmailService email() {
        return emailService;
    }

    public UserRoleHospitalAssignmentService assignments() {
        return assignmentService.getIfAvailable();
    }
}
