package com.example.hms.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public interface EmailService {

    void sendAppointmentRescheduledEmail(
        String to,
        String patientName,
        String hospitalName,
        String staffName,
        String newAppointmentDate,
        String newAppointmentTime,
        String hospitalEmail,
        String hospitalPhone,
        String rescheduleLink,
        String cancelLink
    );

    void sendAppointmentCancelledEmail(
        String to,
        String patientName,
        String hospitalName,
        String staffName,
        String appointmentDate,
        String appointmentTime,
        String hospitalEmail,
        String hospitalPhone
    );

    void sendAppointmentCompletedEmail(
        String to,
        String patientName,
        String hospitalName,
        String staffName,
        String appointmentDate,
        String appointmentTime,
        String hospitalEmail,
        String hospitalPhone
    );

    void sendAppointmentNoShowEmail(
        String to,
        String patientName,
        String hospitalName,
        String staffName,
        String appointmentDate,
        String appointmentTime,
        String hospitalEmail,
        String hospitalPhone
    );

    void sendAppointmentConfirmationEmail(
        String to,
        String patientName,
        String hospitalName,
        String staffName,
        String appointmentDate,
        String appointmentTime,
        String hospitalEmail,
        String hospitalPhone,
        String rescheduleLink,
        String cancelLink
    );

    void sendRoleAssignmentConfirmationEmail(
        String to,
        String userName,
        String roleDisplayName,
        String hospitalDisplayName,
        String confirmationCode,
        String assignmentCode,
        String profileCompletionUrl
    );

    void sendHtml(
        List<String> to, List<String> cc, List<String> bcc,
        String subject, String htmlBody);

    void sendWithAttachment(
        List<String> to, List<String> cc, List<String> bcc,
        String subject, String htmlBody,
        byte[] attachment, String filename, String contentType);

    // App-specific helpers
    void sendActivationEmail(String to, String activationLink);
    void sendPasswordResetEmail(String to, String resetLink);

    void sendUsernameReminderEmail(String toEmail, String username, Locale locale);

    void sendPasswordRotationReminderEmail(String to, String displayName, long daysRemaining, LocalDate dueOn);

    void sendPasswordRotationForceChangeEmail(String to, String displayName, LocalDate dueOn, long daysOverdue);

}

