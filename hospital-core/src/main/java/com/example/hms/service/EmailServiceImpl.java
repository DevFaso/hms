package com.example.hms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    private static final DateTimeFormatter HUMAN_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy");
    private static final String GENERIC_GREETING = "there";
    private static final String HI_PARAGRAPH = "<p style=\"font-size:15px;color:#1e293b;margin:0 0 16px;\">Hi ";

    /** Returns the app login URL, driven by the configured frontend base URL. */
    private String loginUrl() {
        return frontendBaseUrl + "/login";
    }

    private static String resolveRoleLabel(String roleDisplayName, boolean isPatient) {
        if (isPatient) return "Patient";
        return (roleDisplayName != null && !roleDisplayName.isBlank()) ? roleDisplayName : "the assigned role";
    }

    @Override
    public void sendAppointmentRescheduledEmail(String to, String patientName, String hospitalName, String staffName, String newAppointmentDate, String newAppointmentTime, String hospitalEmail, String hospitalPhone, String rescheduleLink, String cancelLink) {
        validateAddresses(List.of(to));
        log.info("📧 Sending appointment rescheduled email to: {}", to);
        String body = """
            <h2>Appointment Rescheduled</h2>
            <p>Dear %s,</p>
            <p>Your appointment at <strong>%s</strong> with Dr. %s has been rescheduled.</p>
            <p><strong>New Date:</strong> %s<br>
            <strong>New Time:</strong> %s</p>
            <p>If you need to reschedule again or cancel, please use the links below:</p>
            <p><a href="%s">Reschedule Appointment</a><br>
            <a href="%s">Cancel Appointment</a></p>
            <p>If you have any questions, contact us at %s or call us at %s.</p>
            """.formatted(patientName, hospitalName, staffName, newAppointmentDate, newAppointmentTime, rescheduleLink, cancelLink, hospitalEmail, hospitalPhone);
        sendHtml(List.of(to), List.of(), List.of(), "Appointment Rescheduled", body);
        log.info("✅ Appointment rescheduled email sent to {}", to);
    }

    @Override
    public void sendAppointmentCancelledEmail(String to, String patientName, String hospitalName, String staffName, String appointmentDate, String appointmentTime, String hospitalEmail, String hospitalPhone) {
        validateAddresses(List.of(to));
        log.info("📧 Sending appointment cancelled email to: {}", to);
        String body = """
            <h2>Appointment Cancelled</h2>
            <p>Dear %s,</p>
            <p>Your appointment at <strong>%s</strong> with Dr. %s on %s at %s has been cancelled.</p>
            <p>If you have any questions, contact us at %s or call us at %s.</p>
            """.formatted(patientName, hospitalName, staffName, appointmentDate, appointmentTime, hospitalEmail, hospitalPhone);
        sendHtml(List.of(to), List.of(), List.of(), "Appointment Cancelled", body);
        log.info("✅ Appointment cancelled email sent to {}", to);
    }

    @Override
    public void sendAppointmentCompletedEmail(String to, String patientName, String hospitalName, String staffName, String appointmentDate, String appointmentTime, String hospitalEmail, String hospitalPhone) {
        validateAddresses(List.of(to));
        log.info("📧 Sending appointment completed email to: {}", to);
        String body = """
            <h2>Appointment Completed</h2>
            <p>Dear %s,</p>
            <p>Your appointment at <strong>%s</strong> with Dr. %s on %s at %s has been marked as completed.</p>
            <p>If you have any questions, contact us at %s or call us at %s.</p>
            """.formatted(patientName, hospitalName, staffName, appointmentDate, appointmentTime, hospitalEmail, hospitalPhone);
        sendHtml(List.of(to), List.of(), List.of(), "Appointment Completed", body);
        log.info("✅ Appointment completed email sent to {}", to);
    }

    @Override
    public void sendAppointmentNoShowEmail(String to, String patientName, String hospitalName, String staffName, String appointmentDate, String appointmentTime, String hospitalEmail, String hospitalPhone) {
        validateAddresses(List.of(to));
        log.info("📧 Sending appointment no-show email to: {}", to);
        String body = """
            <h2>Appointment No-Show</h2>
            <p>Dear %s,</p>
            <p>Your appointment at <strong>%s</strong> with Dr. %s on %s at %s was marked as no-show.</p>
            <p>If you have any questions or wish to reschedule, contact us at %s or call us at %s.</p>
            """.formatted(patientName, hospitalName, staffName, appointmentDate, appointmentTime, hospitalEmail, hospitalPhone);
        sendHtml(List.of(to), List.of(), List.of(), "Appointment No-Show", body);
        log.info("✅ Appointment no-show email sent to {}", to);
    }

    private final JavaMailSender mailSender;

    @Override
    public void sendAppointmentConfirmationEmail(String to, String patientName, String hospitalName, String staffName, String appointmentDate, String appointmentTime, String hospitalEmail, String hospitalPhone, String rescheduleLink, String cancelLink) {
        validateAddresses(List.of(to));
        log.info("📧 Sending appointment confirmation email to: {}", to);

        String body = """
            <h2>Appointment Confirmation</h2>
            <p>Dear %s,</p>
            <p>Your appointment at <strong>%s</strong> with Dr. %s is confirmed.</p>
            <p><strong>Date:</strong> %s<br>
            <strong>Time:</strong> %s</p>
            <p>If you need to reschedule or cancel, please use the links below:</p>
            <p><a href="%s">Reschedule Appointment</a><br>
            <a href="%s">Cancel Appointment</a></p>
            <p>If you have any questions, contact us at %s or call us at %s.</p>
            """.formatted(patientName, hospitalName, staffName, appointmentDate, appointmentTime, rescheduleLink, cancelLink, hospitalEmail, hospitalPhone);

        sendHtml(List.of(to), List.of(), List.of(), "Appointment Confirmation", body);
        log.info("✅ Appointment confirmation email sent to {}", to);
    }

    @Override
    public void sendRoleAssignmentConfirmationEmail(String to,
                                                    String userName,
                                                    String roleDisplayName,
                                                    String hospitalDisplayName,
                                                    String confirmationCode,
                                                    String assignmentCode,
                                                    String profileCompletionUrl,
                                                    String tempUsername,
                                                    String tempPassword) {
        validateAddresses(List.of(to));
        log.info("📧 Sending role assignment confirmation email to: {}", to);

        String safeUserName = (userName != null && !userName.isBlank()) ? userName : GENERIC_GREETING;
        String safeHospital = (hospitalDisplayName != null && !hospitalDisplayName.isBlank()) ? hospitalDisplayName : "our hospital network";

        boolean isPatient = roleDisplayName != null
                && roleDisplayName.toUpperCase(java.util.Locale.ROOT).contains("PATIENT");
        String safeRole = resolveRoleLabel(roleDisplayName, isPatient);

        String buttonLabel = isPatient ? "Activate Your Account" : "Verify Your Role Assignment";
        String linkSection = "";
        if (profileCompletionUrl != null && !profileCompletionUrl.isBlank()) {
            linkSection = """
                <p style="margin:24px 0;">
                    <a href="%s" style="background:#2563eb;color:#fff;padding:12px 18px;border-radius:6px;text-decoration:none;display:inline-block;">%s</a>
                </p>
                <p style="font-size: 14px; color: #666;">Click the button above to open the verification page, then enter the 6-digit code shown in this email.<br />If the button doesn't work, copy and paste this link into your browser:<br /><a href="%s">%s</a></p>
                """.formatted(profileCompletionUrl, buttonLabel, profileCompletionUrl, profileCompletionUrl);
        }

        String credentialsSection = "";
        if (tempUsername != null && !tempUsername.isBlank() && tempPassword != null && !tempPassword.isBlank()) {
            credentialsSection = """
                <div style="background:#f0f9ff;border:1px solid #bae6fd;border-radius:8px;padding:16px;margin:16px 0;">
                    <p style="margin:0 0 8px;font-weight:600;color:#0369a1;">Your Temporary Login Credentials</p>
                    <p style="margin:4px 0;"><strong>Username:</strong> %s</p>
                    <p style="margin:4px 0;"><strong>Temporary Password:</strong> <code style="background:#e0f2fe;padding:2px 6px;border-radius:4px;">%s</code></p>
                    <p style="margin:8px 0 0;font-size:13px;color:#0369a1;">Please sign in and change your password immediately after activation.</p>
                </div>
                """.formatted(tempUsername, tempPassword);
        }

        String subject;
        String body;
        if (isPatient) {
            subject = "Welcome to " + safeHospital + " — Activate Your Patient Account";
            body = """
                <h2>Welcome to %s</h2>
                <p>Hi %s,</p>
                <p>A patient account has been created for you at <strong>%s</strong>.</p>
                <p>To activate your account, click the button below and enter this <strong>6-digit verification code</strong>:</p>
                <p style="font-size: 28px; font-weight: bold; letter-spacing: 6px; text-align: center; \
                background: #f8fafc; border: 2px dashed #2563eb; border-radius: 8px; padding: 16px; margin: 16px 0;">%s</p>
                %s
                %s
                <p><strong>Your account will remain inactive until you enter this code.</strong></p>
                <div style="background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:12px 16px;margin:16px 0;">
                    <p style="margin:0;font-size:13px;color:#991b1b;"><strong>Didn't request this account?</strong> \
                If you did not register at this hospital, please ignore this email or contact the hospital administrator. \
                No action is needed — the account will not be activated.</p>
                </div>
                <p style="color:#666;font-size:13px;">This verification code expires 48 hours after it was sent.</p>
                """.formatted(safeHospital, safeUserName, safeHospital, confirmationCode,
                              credentialsSection, linkSection);
        } else {
            subject = "Action Required: Confirm Your Hospital Role Assignment";
            body = """
                <h2>Verify Your New Role Assignment</h2>
                <p>Hi %s,</p>
                <p>You have been assigned the role <strong>%s</strong> at <strong>%s</strong>.</p>
                <p>To activate your assignment, click the verification link below and enter this code:</p>
                    <p style="font-size: 24px; font-weight: bold; letter-spacing: 4px;">%s</p>
                <p>Assignment reference: <strong>%s</strong></p>
                %s
                %s
                <p><strong>Your account will remain inactive until you verify this code.</strong></p>
                <p>If you did not expect this assignment, please contact the hospital administrator immediately.</p>
                <p style="color:#666">This code expires 48 hours after it was sent.</p>
                """.formatted(safeUserName, safeRole, safeHospital, confirmationCode, assignmentCode, linkSection, credentialsSection);
        }

        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("✅ Role assignment confirmation email sent to {}", to);
    }

    @Override
    public void sendHtml(List<String> to, List<String> cc, List<String> bcc,
                         String subject, String htmlBody) {
        sendWithAttachment(to, cc, bcc, subject, htmlBody, null, null, null);
    }

    @Override
    public void sendWithAttachment(List<String> to, List<String> cc, List<String> bcc,
                                   String subject, String htmlBody,
                                   byte[] attachment, String filename, String contentType) {
        validateAddresses(to);
        log.info("📧 Sending email to: {}, subject: {}", to, subject);
        mailSender.send(mime -> {
            var multipart = attachment != null;
            var helper = new MimeMessageHelper(mime, multipart, "UTF-8");
            helper.setTo(to.toArray(String[]::new));
            if (cc != null && !cc.isEmpty()) helper.setCc(cc.toArray(String[]::new));
            if (bcc != null && !bcc.isEmpty()) helper.setBcc(bcc.toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            if (attachment != null) {
                var res = new ByteArrayResource(attachment) {
                    @Override public String getFilename() { return filename; }
                };
                helper.addAttachment(filename, res, contentType != null ? contentType : "application/pdf");
            }
        });
    }

    @Override
    public void sendActivationEmail(String to, String activationLink) {
        sendActivationEmail(to, activationLink, null, null, null);
    }

    @Override
    public void sendActivationEmail(String to, String activationLink,
                                     String patientName, String username,
                                     String hospitalName) {
        var body = buildActivationEmailBody(activationLink, patientName, username, hospitalName);
        sendHtml(List.of(to), List.of(), List.of(), "Activate Your Hospital Management Account", body);
        log.info("✅ Activation email sent to {}", to);
    }

    @Override
    public void sendPasswordResetEmail(String to, String resetLink) {
        var body = buildResetEmailBody(resetLink);
        sendHtml(List.of(to), List.of(), List.of(), "Reset Your Hospital Management Account Password", body);
        log.info("✅ Password reset email sent to {}", to);
    }

    @Override
    public void sendPasswordResetConfirmationEmail(String to, String displayName) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        String safeName = (displayName != null && !displayName.isBlank()) ? displayName : GENERIC_GREETING;
        String escapedName = escapeHtml(safeName);
        String changedAt = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' HH:mm"));

        String header = "<div style=\"background:linear-gradient(135deg,#065f46,#059669);"
            + "padding:32px 40px;text-align:center;\">"
            + "<h1 style=\"color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:0.5px;\">"
            + "&#9989; Password Changed Successfully"
            + "</h1>"
            + "<p style=\"color:#a7f3d0;margin:8px 0 0;font-size:14px;\">Hospital Management System</p>"
            + "</div>";

        String bodyContent = "<div style=\"padding:36px 40px;\">"
            + HI_PARAGRAPH + escapedName + ",</p>"
            + "<p style=\"font-size:15px;color:#334155;line-height:1.6;margin:0 0 24px;\">"
            + "Your account password was successfully reset on <strong>" + changedAt + "</strong>."
            + " You can now sign in with your new password."
            + "</p>"
            + "<div style=\"text-align:center;margin:32px 0;\">"
            + "<a href=\"" + loginUrl() + "\" style=\"display:inline-block;"
            + "background:linear-gradient(135deg,#2563eb,#1d4ed8);color:#ffffff;"
            + "text-decoration:none;padding:14px 36px;border-radius:8px;font-size:16px;"
            + "font-weight:600;letter-spacing:0.3px;\">Sign In to Your Account</a>"
            + "</div>"
            + "<hr style=\"border:none;border-top:1px solid #e2e8f0;margin:0 0 28px;\"/>"
            + "<div style=\"background:#fef2f2;border:1px solid #fecaca;border-radius:8px;"
            + "padding:16px 20px;margin-bottom:24px;\">"
            + "<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;color:#991b1b;\">"
            + "&#128680; Didn't make this change?</p>"
            + "<p style=\"margin:0;font-size:14px;color:#7f1d1d;line-height:1.6;\">"
            + "If you did <strong>not</strong> reset your password, your account may have been compromised. "
            + "<strong><a href=\"" + loginUrl() + "\" style=\"color:#991b1b;\">Sign in immediately</a></strong>"
            + " and contact your hospital administrator to secure your account."
            + "</p></div>"
            + "<ul style=\"padding-left:20px;color:#64748b;font-size:13px;line-height:1.8;margin:0;\">"
            + "<li>Never share your password with anyone, including hospital staff.</li>"
            + "<li>Use a unique password that you don't use on other sites.</li>"
            + "<li>Enable any available two-factor authentication for extra security.</li>"
            + "</ul>"
            + "</div>";

        String body = htmlEmailWrapper(header + bodyContent + htmlEmailFooter());

        sendHtml(List.of(to), List.of(), List.of(), "Your HMS Password Has Been Changed", body);
        log.info("✅ Password reset confirmation email sent to {}", to);
    }

    @Override
    public void sendAccountRestoredEmail(String to, String displayName) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        String safeName = (displayName != null && !displayName.isBlank()) ? displayName : GENERIC_GREETING;
        String escapedName = escapeHtml(safeName);
        String restoredAt = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' HH:mm"));

        String header = "<div style=\"background:linear-gradient(135deg,#1e40af,#2563eb);"
            + "padding:32px 40px;text-align:center;\">"
            + "<h1 style=\"color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:0.5px;\">"
            + "&#9989; Your Account Has Been Restored"
            + "</h1>"
            + "<p style=\"color:#bfdbfe;margin:8px 0 0;font-size:14px;\">Hospital Management System</p>"
            + "</div>";

        String bodyContent = "<div style=\"padding:36px 40px;\">"
            + HI_PARAGRAPH + escapedName + ",</p>"
            + "<p style=\"font-size:15px;color:#334155;line-height:1.6;margin:0 0 24px;\">"
            + "Your account was <strong>restored</strong> on <strong>" + restoredAt + "</strong> "
            + "by a system administrator. You can now sign in as normal."
            + "</p>"
            + "<div style=\"text-align:center;margin:32px 0;\">"
            + "<a href=\"" + loginUrl() + "\" style=\"display:inline-block;"
            + "background:linear-gradient(135deg,#2563eb,#1d4ed8);color:#ffffff;"
            + "text-decoration:none;padding:14px 36px;border-radius:8px;font-size:16px;"
            + "font-weight:600;letter-spacing:0.3px;\">Sign In to Your Account</a>"
            + "</div>"
            + "<hr style=\"border:none;border-top:1px solid #e2e8f0;margin:0 0 28px;\"/>"
            + "<div style=\"background:#fef2f2;border:1px solid #fecaca;border-radius:8px;"
            + "padding:16px 20px;margin-bottom:24px;\">"
            + "<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;color:#991b1b;\">"
            + "&#128680; Didn't expect this?</p>"
            + "<p style=\"margin:0;font-size:14px;color:#7f1d1d;line-height:1.6;\">"
            + "If you did <strong>not</strong> request your account to be restored, "
            + "please contact your system administrator immediately and do <strong>not</strong> sign in."
            + "</p>"
            + "</div>"
            + "</div>";

        String body = htmlEmailWrapper(header + bodyContent + htmlEmailFooter());
        sendHtml(List.of(to), List.of(), List.of(), "Your HMS Account Has Been Restored", body);
        log.info("✅ Account restored notification email sent to {}", to);
    }

    @Override
    public void sendRecoveryContactVerificationEmail(String to, String verificationCode) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        String escapedCode = escapeHtml(verificationCode);

        String header = "<div style=\"background:linear-gradient(135deg,#1e40af,#2563eb);"
            + "padding:32px 40px;text-align:center;\">"
            + "<h1 style=\"color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:0.5px;\">"
            + "&#128274; Verify Your Recovery Contact"
            + "</h1>"
            + "<p style=\"color:#bfdbfe;margin:8px 0 0;font-size:14px;\">Hospital Management System</p>"
            + "</div>";

        String bodyContent = "<div style=\"padding:36px 40px;\">"
            + "<p style=\"font-size:15px;color:#334155;line-height:1.6;margin:0 0 24px;\">"
            + "You requested to add this email address as a recovery contact for your HMS account. "
            + "Please enter the verification code below to confirm ownership:"
            + "</p>"
            + "<div style=\"text-align:center;margin:32px 0;\">"
            + "<div style=\"display:inline-block;background:#f1f5f9;border:2px dashed #94a3b8;"
            + "border-radius:12px;padding:20px 40px;\">"
            + "<span style=\"font-size:32px;font-weight:700;letter-spacing:8px;color:#1e293b;font-family:monospace;\">"
            + escapedCode
            + "</span>"
            + "</div>"
            + "</div>"
            + "<p style=\"font-size:14px;color:#64748b;text-align:center;margin:0 0 24px;\">"
            + "This code expires in <strong>15 minutes</strong>."
            + "</p>"
            + "<hr style=\"border:none;border-top:1px solid #e2e8f0;margin:0 0 28px;\"/>"
            + "<div style=\"background:#fef2f2;border:1px solid #fecaca;border-radius:8px;"
            + "padding:16px 20px;margin-bottom:24px;\">"
            + "<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;color:#991b1b;\">"
            + "&#128680; Didn't request this?</p>"
            + "<p style=\"margin:0;font-size:14px;color:#7f1d1d;line-height:1.6;\">"
            + "If you did <strong>not</strong> add this recovery contact, you can safely ignore this email. "
            + "No changes will be made to your account."
            + "</p></div>"
            + "</div>";

        String body = htmlEmailWrapper(header + bodyContent + htmlEmailFooter());
        sendHtml(List.of(to), List.of(), List.of(), "HMS Recovery Contact Verification Code", body);
        log.info("✅ Recovery contact verification email sent to {}", to);
    }

    @Override
    public void sendUsernameReminderEmail(String toEmail, String username, Locale locale) {
        var subject = subjectUsername(locale);
        var body = buildUsernameReminderEmailBody(username, locale);
        sendHtml(List.of(toEmail), List.of(), List.of(), subject, body);
        log.info("✅ Username reminder email sent to {}", toEmail);
    }

    @Override
    public void sendPasswordRotationReminderEmail(String to, String displayName, long daysRemaining, LocalDate dueOn) {
        validateAddresses(List.of(to));
    var safeName = (displayName != null && !displayName.isBlank()) ? displayName : GENERIC_GREETING;
        var subject = "Password rotation reminder";
        var body = """
            <h2>Password Rotation Reminder</h2>
            <p>Hi %s,</p>
            <p>This is a reminder that your account password must be updated by <strong>%s</strong>.</p>
            <p><strong>%d day%s</strong> remain before your password expires.</p>
            <p>Please sign in at <a href="%s">%s</a> and update your password.</p>
            <p>If you recently changed your password, you can ignore this message.</p>
            """.formatted(
            safeName,
            HUMAN_DATE.format(dueOn),
            daysRemaining,
            daysRemaining == 1 ? "" : "s",
            loginUrl(),
            loginUrl()
        );
        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("📧 Password rotation reminder sent to {} ({} day(s) remaining)", to, daysRemaining);
    }

    @Override
    public void sendPasswordRotationForceChangeEmail(String to, String displayName, LocalDate dueOn, long daysOverdue) {
        validateAddresses(List.of(to));
    var safeName = (displayName != null && !displayName.isBlank()) ? displayName : GENERIC_GREETING;
        var subject = "Password rotation enforcement";
        var body = """
            <h2>Password Change Required</h2>
            <p>Hi %s,</p>
            <p>Your password rotation deadline on <strong>%s</strong> has passed. It has now been <strong>%d day%s</strong> overdue.</p>
            <p>For security reasons, you must change your password immediately. Sign in at <a href="%s">%s</a> to update it.</p>
            <p>Access to certain areas will remain restricted until your password is updated.</p>
            """.formatted(
            safeName,
            HUMAN_DATE.format(dueOn),
            daysOverdue,
            daysOverdue == 1 ? "" : "s",
            loginUrl(),
            loginUrl()
        );
        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("📧 Password rotation enforcement notice sent to {} ({} day(s) overdue)", to, daysOverdue);
    }

    private String subjectUsername(Locale locale) {
        var lang = locale != null ? locale.getLanguage() : "en";
        return switch (lang) {
            case "fr" -> "Rappel d’identifiant";
            case "es" -> "Recordatorio de nombre de usuario";
            default -> "Your Username Reminder";
        };
    }

    private String buildUsernameReminderEmailBody(String username, Locale locale) {
        var loginUrl = loginUrl();
        var lang = locale != null ? locale.getLanguage() : "en";
        return switch (lang) {
            case "fr" -> """
            <h2>Rappel d’identifiant</h2>
            <p>Vous (ou quelqu’un d’autre) avez demandé votre identifiant pour le Système de Gestion Hospitalière.</p>
            <p><strong>Identifiant&nbsp;:</strong> %s</p>
            <p>Vous pouvez vous connecter ici : <a href="%s">%s</a></p>
            <p style="color:#666">Si vous n’êtes pas à l’origine de cette demande, ignorez ce message.</p>
            """.formatted(username, loginUrl, loginUrl);
            case "es" -> """
            <h2>Recordatorio de nombre de usuario</h2>
            <p>Usted (o alguien) solicitó su nombre de usuario del Sistema de Gestión Hospitalaria.</p>
            <p><strong>Usuario:</strong> %s</p>
            <p>Puede iniciar sesión aquí: <a href="%s">%s</a></p>
            <p style="color:#666">Si no solicitó esto, puede ignorar este correo.</p>
            """.formatted(username, loginUrl, loginUrl);
            default -> """
            <h2>Username Reminder</h2>
            <p>You (or someone) requested your username for the Hospital Management System.</p>
            <p><strong>Username:</strong> %s</p>
            <p>You can sign in here: <a href="%s">%s</a></p>
            <p style="color:#666">If you didn’t request this, you can safely ignore this email.</p>
            """.formatted(username, loginUrl, loginUrl);
        };
    }


    private String buildActivationEmailBody(String link, String patientName,
                                              String username, String hospitalName) {
        String safeName = (patientName != null && !patientName.isBlank()) ? escapeHtml(patientName) : null;
        String safeUser = (username != null && !username.isBlank()) ? escapeHtml(username) : null;
        String safeHosp = (hospitalName != null && !hospitalName.isBlank()) ? escapeHtml(hospitalName) : null;

        String header = "<div style=\"background:linear-gradient(135deg,#1e3a5f,#2563eb);"
            + "padding:32px 40px;text-align:center;\">"
            + "<h1 style=\"color:#ffffff;margin:0;font-size:22px;font-weight:700;\">"
            + "Welcome to the Hospital Management System</h1>"
            + (safeHosp != null
                ? "<p style=\"color:#bfdbfe;margin:8px 0 0;font-size:14px;\">" + safeHosp + "</p>"
                : "")
            + "</div>";

        StringBuilder body = new StringBuilder();
        body.append("<div style=\"padding:36px 40px;\">");
        if (safeName != null) {
            body.append(HI_PARAGRAPH).append(safeName).append(",</p>");
        }
        body.append("<p style=\"font-size:15px;color:#334155;line-height:1.6;margin:0 0 24px;\">")
            .append("Your patient account has been created. Please activate it by clicking the button below.")
            .append("</p>");

        if (safeUser != null) {
            body.append("<div style=\"background:#f0f9ff;border:1px solid #bae6fd;border-radius:8px;")
                .append("padding:16px 20px;margin:0 0 24px;\">")
                .append("<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;color:#0c4a6e;\">Your login credentials</p>")
                .append("<p style=\"margin:0;font-size:14px;color:#075985;\">")
                .append("<strong>Username:</strong> ").append(safeUser).append("<br/>")
                .append("<strong>Password:</strong> You will be prompted to set a password after activation.")
                .append("</p></div>");
        }

        body.append("<div style=\"text-align:center;margin:32px 0;\">")
            .append("<a href=\"").append(link).append("\" style=\"display:inline-block;")
            .append("background:linear-gradient(135deg,#2563eb,#1d4ed8);color:#ffffff;")
            .append("text-decoration:none;padding:14px 36px;border-radius:8px;font-size:16px;")
            .append("font-weight:600;\">Activate My Account</a>")
            .append("</div>")
            .append("<p style=\"font-size:13px;color:#64748b;text-align:center;margin:0 0 32px;\">")
            .append("Button not working? Copy and paste this link into your browser:<br/>")
            .append("<a href=\"").append(link).append("\" style=\"color:#2563eb;word-break:break-all;\">").append(link).append("</a>")
            .append("</p>")
            .append("<hr style=\"border:none;border-top:1px solid #e2e8f0;margin:0 0 20px;\"/>")
            .append("<p style=\"font-size:13px;color:#94a3b8;text-align:center;\">")
            .append("This link expires in <strong>24 hours</strong>. ")
            .append("If you did not expect this email, please contact the hospital administrator.")
            .append("</p></div>");

        return htmlEmailWrapper(header + body.toString() + htmlEmailFooter());
    }

    private String buildResetEmailBody(String link) {
        String header = "<div style=\"background:linear-gradient(135deg,#1e3a5f,#2563eb);"
            + "padding:32px 40px;text-align:center;\">"
            + "<h1 style=\"color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:0.5px;\">"
            + "&#128274; Password Reset Request"
            + "</h1>"
            + "<p style=\"color:#bfdbfe;margin:8px 0 0;font-size:14px;\">Hospital Management System</p>"
            + "</div>";

        String bodyContent = "<div style=\"padding:36px 40px;\">"
            + "<p style=\"font-size:15px;color:#1e293b;margin:0 0 16px;\">Hello,</p>"
            + "<p style=\"font-size:15px;color:#334155;line-height:1.6;margin:0 0 24px;\">"
            + "We received a request to reset the password for your account. "
            + "Click the button below to choose a new password."
            + "</p>"
            + "<div style=\"text-align:center;margin:32px 0;\">"
            + "<a href=\"" + link + "\" style=\"display:inline-block;"
            + "background:linear-gradient(135deg,#2563eb,#1d4ed8);color:#ffffff;"
            + "text-decoration:none;padding:14px 36px;border-radius:8px;font-size:16px;"
            + "font-weight:600;letter-spacing:0.3px;\">Reset My Password</a>"
            + "</div>"
            + "<p style=\"font-size:13px;color:#64748b;text-align:center;margin:0 0 32px;\">"
            + "Button not working? Copy and paste this link into your browser:<br/>"
            + "<a href=\"" + link + "\" style=\"color:#2563eb;word-break:break-all;\">" + link + "</a>"
            + "</p>"
            + "<hr style=\"border:none;border-top:1px solid #e2e8f0;margin:0 0 28px;\"/>"
            + "<div style=\"background:#fef9ec;border:1px solid #fcd34d;border-radius:8px;"
            + "padding:16px 20px;margin-bottom:24px;\">"
            + "<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;color:#92400e;\">"
            + "&#9888;&#65039; Didn't request this?</p>"
            + "<p style=\"margin:0;font-size:14px;color:#78350f;line-height:1.6;\">"
            + "If you did <strong>not</strong> request a password reset, your account may be at risk. "
            + "Please <strong>do not click the link above</strong> and immediately "
            + "<a href=\"" + loginUrl() + "\" style=\"color:#b45309;font-weight:600;\">"
            + "sign in to your account</a> to verify your security settings, "
            + "or contact your hospital administrator."
            + "</p></div>"
            + "<ul style=\"padding-left:20px;color:#64748b;font-size:13px;line-height:1.8;margin:0;\">"
            + "<li>This link expires in <strong>2 hours</strong>.</li>"
            + "<li>The link can only be used <strong>once</strong>.</li>"
            + "<li>Never share this link with anyone.</li>"
            + "</ul>"
            + "</div>";

        return htmlEmailWrapper(header + bodyContent + htmlEmailFooter());
    }

    // -------------------------------------------------------------------------
    // Shared HTML building blocks
    // -------------------------------------------------------------------------

    /**
     * Escapes user-supplied values before embedding them in HTML email bodies
     * to prevent HTML injection.
     */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    /**
     * Wraps {@code content} in the standard outer email container:
     * a full-width light-grey background with a centred white card.
     */
    private static String htmlEmailWrapper(String content) {
        return "<div style=\"font-family:Arial,sans-serif;max-width:600px;margin:0 auto;"
             + "background:#f8fafc;padding:24px;\">"
             + "<div style=\"background:#ffffff;border-radius:12px;overflow:hidden;"
             + "box-shadow:0 2px 8px rgba(0,0,0,0.08);\">"
             + content
             + "</div></div>";
    }

    /** Returns the standard email footer HTML (year is resolved at call-time). */
    private static String htmlEmailFooter() {
        return "<div style=\"background:#f1f5f9;padding:20px 40px;text-align:center;"
             + "border-top:1px solid #e2e8f0;\">"
             + "<p style=\"margin:0;font-size:12px;color:#94a3b8;\">"
             + "\u00a9 " + java.time.Year.now().getValue() + " Hospital Management System"
             + " &nbsp;|&nbsp; This is an automated message \u2014 please do not reply."
             + "</p></div>";
    }

    private static void validateAddresses(List<String> addresses) {
        log.info("validateAddresses input: {}", addresses);
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("Recipient list cannot be empty");
        }
        for (String addr : addresses) {
            if (addr == null || addr.isBlank()) {
                throw new IllegalArgumentException("Empty email address");
            }
            // Simple RFC check — character-class exclusions prevent backtracking/ReDoS
            if (!addr.matches("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$")) {
                throw new IllegalArgumentException("Invalid email format: " + addr);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Admin-created user welcome email
    // -------------------------------------------------------------------------

    @Override
    public void sendAdminWelcomeEmail(String to,
                                      String displayName,
                                      String username,
                                      String tempPassword,
                                      String roleName,
                                      String hospitalName) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        log.info("📧 Sending admin welcome email to: {}", to);

        String safeName     = (displayName  != null && !displayName.isBlank())  ? displayName  : GENERIC_GREETING;
        String safeRole     = (roleName     != null && !roleName.isBlank())     ? roleName     : "your assigned role";
        String safeHospital = (hospitalName != null && !hospitalName.isBlank()) ? hospitalName : null;
        String safeUsername = (username     != null && !username.isBlank())     ? username     : "\u2014";
        String safePassword = (tempPassword != null && !tempPassword.isBlank()) ? tempPassword : "\u2014";
        String loginUrl     = loginUrl();

        String escapedName     = escapeHtml(safeName);
        String escapedRole     = escapeHtml(safeRole);
        String escapedHospital = safeHospital != null ? escapeHtml(safeHospital) : null;
        String escapedUsername = escapeHtml(safeUsername);
        String escapedPassword = escapeHtml(safePassword);

        String hospitalLine = escapedHospital != null
            ? "<tr><td style='padding:6px 0;color:#64748b;font-weight:600;'>Hospital</td>"
              + "<td style='padding:6px 0 6px 16px;'>" + escapedHospital + "</td></tr>"
            : "";
        String atHospital = escapedHospital != null
            ? "at <strong>" + escapedHospital + "</strong>. "
            : ". ";

        String header = "<div style=\"background:linear-gradient(135deg,#1e3a5f,#2563eb);"
            + "padding:32px 40px;text-align:center;\">"
            + "<div style=\"font-size:36px;margin-bottom:8px;\">&#127973;</div>"
            + "<h1 style=\"color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:-0.5px;\">"
            + "Welcome to HMS</h1>"
            + "<p style=\"color:#bfdbfe;margin:6px 0 0;font-size:14px;\">Your account has been created</p>"
            + "</div>";

        String bodyContent = "<div style=\"padding:32px 40px;\">"
            + "<p style=\"color:#1e293b;font-size:16px;margin-top:0;\">Hi <strong>" + escapedName + "</strong>,</p>"
            + "<p style=\"color:#475569;line-height:1.6;\">"
            + "A <strong>" + escapedRole + "</strong> account has been created for you " + atHospital
            + "You can sign in immediately using the credentials below. "
            + "You will be prompted to change your password on first login."
            + "</p>"
            + "<div style=\"background:#eff6ff;border:2px solid #bfdbfe;border-radius:10px;"
            + "padding:20px 24px;margin:24px 0;\">"
            + "<p style=\"margin:0 0 12px;font-weight:700;color:#1e3a8a;font-size:14px;"
            + "text-transform:uppercase;letter-spacing:0.5px;\">Your Login Credentials</p>"
            + "<table style=\"border-collapse:collapse;width:100%;\">"
            + "<tr><td style=\"padding:6px 0;color:#64748b;font-weight:600;\">Username</td>"
            + "<td style=\"padding:6px 0 6px 16px;font-family:monospace;font-size:16px;color:#1e293b;\">"
            + escapedUsername + "</td></tr>"
            + "<tr><td style=\"padding:6px 0;color:#64748b;font-weight:600;\">Temp Password</td>"
            + "<td style=\"padding:6px 0 6px 16px;font-family:monospace;font-size:16px;color:#1e293b;\">"
            + escapedPassword + "</td></tr>"
            + "<tr><td style=\"padding:6px 0;color:#64748b;font-weight:600;\">Role</td>"
            + "<td style=\"padding:6px 0 6px 16px;\">" + escapedRole + "</td></tr>"
            + hospitalLine
            + "</table></div>"
            + "<p style=\"text-align:center;margin:28px 0;\">"
            + "<a href=\"" + loginUrl + "\" style=\"background:#2563eb;color:#ffffff;"
            + "text-decoration:none;padding:14px 32px;border-radius:8px;font-size:15px;"
            + "font-weight:600;display:inline-block;\">Sign In to Your Account</a></p>"
            + "<p style=\"text-align:center;font-size:13px;color:#94a3b8;\">Or copy this link:<br/>"
            + "<a href=\"" + loginUrl + "\" style=\"color:#2563eb;\">" + loginUrl + "</a></p>"
            + "<div style=\"background:#fef3c7;border-left:4px solid #f59e0b;padding:14px 16px;"
            + "border-radius:0 8px 8px 0;margin-top:24px;\">"
            + "<p style=\"margin:0;font-size:14px;color:#92400e;\">"
            + "<strong>&#9888; Security notice:</strong> This email contains a temporary password. "
            + "Please sign in and change it immediately. Do not share this email with anyone. "
            + "If you did not expect this account, contact your system administrator right away."
            + "</p></div>"
            + "</div>";

        String body = htmlEmailWrapper(header + bodyContent + htmlEmailFooter());

        sendHtml(List.of(to), List.of(), List.of(),
            "Welcome to HMS \u2014 Your Account Is Ready", body);
        log.info("✅ Admin welcome email sent to {}", to);
    }

}

