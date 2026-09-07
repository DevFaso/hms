package com.example.hms.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("java:S107") // Email API methods require many template parameters by nature
public interface EmailService {

    /**
     * True when this deployment can actually hand mail to an SMTP server.
     * Mirrors {@link SmsService#deliversRealSms()}: callers use it to tell
     * "the send failed" apart from "there is no transport to send with",
     * so the registrar-facing delivery report can say which one it was.
     */
    default boolean deliversRealEmail() {
        return true;
    }

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
        String profileCompletionUrl,
        String tempUsername,
        String tempPassword
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

    /**
     * Enhanced activation email that includes the patient's credentials and hospital context.
     */
    void sendActivationEmail(String to, String activationLink, String patientName, String username, String hospitalName);
    void sendPasswordResetEmail(String to, String resetLink);

    void sendPasswordResetConfirmationEmail(String to, String displayName);

    void sendUsernameReminderEmail(String toEmail, String username, Locale locale);

    void sendPasswordRotationReminderEmail(String to, String displayName, long daysRemaining, LocalDate dueOn);

    void sendPasswordRotationForceChangeEmail(String to, String displayName, LocalDate dueOn, long daysOverdue);

    /**
     * Sent to a newly admin-created staff/admin user with their temporary
     * credentials.
     *
     * <p>The account is created INACTIVE (option A, 2026-09-02): the holder
     * must first confirm the role assignment with the code delivered in the
     * assignment message. This mail therefore points at activation, not at
     * the login page — telling an inactive user to "sign in immediately"
     * sends them to a rejection they cannot act on, which is precisely how a
     * delivered activation code got reported as "no activation email".
     *
     * @param to            recipient email address
     * @param displayName   first + last name (or username as fallback)
     * @param username      the account username
     * @param tempPassword  the plain-text temporary password (shown once)
     * @param roleName      human-readable role label, e.g. "Hospital Admin"
     * @param hospitalName  hospital the user was assigned to (may be null for global roles)
     * @param activationUrl where to enter the confirmation code; null falls back
     *                      to generic wording that still names the step
     */
    void sendAdminWelcomeEmail(
        String to,
        String displayName,
        String username,
        String tempPassword,
        String roleName,
        String hospitalName,
        String activationUrl
    );

    /**
     * Sent immediately after an admin restores a soft-deleted account, so the
     * account owner is informed and can report unauthorised access if needed.
     *
     * @param to          recipient email address
     * @param displayName first + last name (or username as fallback)
     */
    void sendAccountRestoredEmail(String to, String displayName);

    /**
     * Sends a verification code to confirm ownership of a recovery contact email address.
     *
     * @param to               the recovery contact email address to verify
     * @param verificationCode the 6-digit code the user must enter to confirm
     */
    void sendRecoveryContactVerificationEmail(String to, String verificationCode);

}

