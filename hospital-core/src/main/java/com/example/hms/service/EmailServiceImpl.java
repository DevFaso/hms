package com.example.hms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final DateTimeFormatter HUMAN_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy");
    private static final String LOGIN_URL = "https://yourapp.com/login";
    private static final String GENERIC_GREETING = "there";

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
        String safeRole = (roleDisplayName != null && !roleDisplayName.isBlank()) ? roleDisplayName : "the assigned role";
        String safeHospital = (hospitalDisplayName != null && !hospitalDisplayName.isBlank()) ? hospitalDisplayName : "our hospital network";

        // Temporary credentials section (only for brand-new users)
        String credentialsSection = "";
        if (tempUsername != null && tempPassword != null) {
            credentialsSection = """
                <div style="background:#fef9c3;border:1px solid #fbbf24;border-radius:8px;padding:16px;margin:20px 0;">
                  <h3 style="margin:0 0 8px;color:#92400e;">🔑 Your Temporary Login Credentials</h3>
                  <p style="margin:4px 0;">Username: <strong style="font-family:monospace;">%s</strong></p>
                  <p style="margin:4px 0;">Password: <strong style="font-family:monospace;">%s</strong></p>
                  <p style="margin:8px 0 0;font-size:13px;color:#92400e;">
                    ⚠️ You will be asked to change your password on first login.
                    Keep these credentials safe and do not share them.
                  </p>
                </div>
                """.formatted(escapeHtml(tempUsername), escapeHtml(tempPassword));
        }

        String linkSection = "";
        if (profileCompletionUrl != null && !profileCompletionUrl.isBlank()) {
            linkSection = """
                <p style="margin:24px 0;">
                    <a href="%s" style="background:#2563eb;color:#fff;padding:12px 24px;border-radius:6px;text-decoration:none;display:inline-block;font-size:16px;font-weight:600;">Finish Profile Setup →</a>
                </p>
                <p style="font-size: 13px; color: #888;">If the button above doesn't work, copy and paste this link into your browser:<br />
                <a href="%s" style="color:#2563eb;word-break:break-all;">%s</a></p>
                """.formatted(profileCompletionUrl, profileCompletionUrl, profileCompletionUrl);
        }

        String body = """
            <!DOCTYPE html>
            <html>
            <body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif;">
            <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:40px 0;">
              <tr><td align="center">
                <table width="560" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:12px;box-shadow:0 2px 12px rgba(0,0,0,0.08);overflow:hidden;">
                  <!-- Header -->
                  <tr><td style="background:linear-gradient(135deg,#1e40af,#2563eb);padding:32px 40px;text-align:center;">
                    <div style="display:inline-block;background:rgba(255,255,255,0.15);border-radius:12px;padding:12px 20px;margin-bottom:16px;">
                      <span style="color:#fff;font-size:22px;font-weight:700;letter-spacing:2px;">HMS</span>
                    </div>
                    <h1 style="margin:0;color:#fff;font-size:22px;font-weight:600;">New Role Assignment</h1>
                    <p style="margin:6px 0 0;color:rgba(255,255,255,0.85);font-size:14px;">Hospital Management System</p>
                  </td></tr>
                  <!-- Body -->
                  <tr><td style="padding:40px;">
                    <p style="margin:0 0 16px;font-size:16px;color:#1e293b;">Hi <strong>%s</strong>,</p>
                    <p style="margin:0 0 24px;font-size:15px;color:#475569;">You have been assigned a new role in the hospital system.</p>
                    <!-- Role / Hospital card -->
                    <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f1f5f9;border-radius:8px;margin-bottom:28px;">
                      <tr>
                        <td style="padding:20px;">
                          <table width="100%%" cellpadding="0" cellspacing="0">
                            <tr>
                              <td style="padding:6px 0;"><span style="color:#64748b;font-size:13px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;">Role</span></td>
                              <td style="padding:6px 0;text-align:right;"><strong style="color:#1e293b;font-size:15px;">%s</strong></td>
                            </tr>
                            <tr>
                              <td style="padding:6px 0;"><span style="color:#64748b;font-size:13px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;">Organization</span></td>
                              <td style="padding:6px 0;text-align:right;"><strong style="color:#1e293b;font-size:15px;">%s</strong></td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                    %s
                    <!-- Verification Code -->
                    <p style="margin:0 0 12px;font-size:15px;color:#1e293b;font-weight:600;">Enter this code to confirm your assignment:</p>
                    <div style="text-align:center;margin:0 0 28px;">
                      <div style="display:inline-block;background:#eff6ff;border:2px solid #bfdbfe;border-radius:12px;padding:20px 40px;">
                        <span style="font-size:38px;font-weight:800;letter-spacing:12px;color:#1e40af;font-family:monospace;">%s</span>
                      </div>
                      <p style="margin:10px 0 0;font-size:13px;color:#ef4444;font-weight:500;">⏱ This code expires soon. Do not share it.</p>
                    </div>
                    <!-- Reference -->
                    <p style="margin:0 0 24px;font-size:13px;color:#94a3b8;">Assignment reference: <code style="background:#f1f5f9;padding:2px 6px;border-radius:4px;color:#475569;">%s</code></p>
                    <!-- CTA -->
                    %s
                    <hr style="border:none;border-top:1px solid #e2e8f0;margin:28px 0;" />
                    <p style="margin:0;font-size:13px;color:#94a3b8;">If you did not expect this assignment, please contact your hospital administrator immediately.</p>
                  </td></tr>
                  <!-- Footer -->
                  <tr><td style="background:#f8fafc;padding:20px 40px;text-align:center;border-top:1px solid #e2e8f0;">
                    <p style="margin:0;font-size:12px;color:#94a3b8;">© Hospital Management System — Secure Notification</p>
                  </td></tr>
                </table>
              </td></tr>
            </table>
            </body>
            </html>
            """.formatted(safeUserName, safeRole, safeHospital,
                          credentialsSection,
                          confirmationCode, assignmentCode, linkSection);

        sendHtml(List.of(to), List.of(), List.of(), "Action Required: Confirm Your Role Assignment at " + safeHospital, body);
        log.info("✅ Role assignment confirmation email sent to {}", to);
    }

    /** Minimal HTML-escape for safe inline display of user-supplied strings. */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
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
        var body = buildActivationEmailBody(activationLink);
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
            LOGIN_URL,
            LOGIN_URL
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
            LOGIN_URL,
            LOGIN_URL
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
        var loginUrl = LOGIN_URL;
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


    private String buildActivationEmailBody(String link) {
        return """
            <h2>Welcome to the Hospital Management System</h2>
            <p>Click the link below to activate your account:</p>
            <p><a href="%s">Activate Account</a></p>
            <p style="color:#666">This link will expire in 24 hours.</p>
            """.formatted(link);
    }

    private String buildResetEmailBody(String link) {
        return """
            <h2>Password Reset Request</h2>
            <p>If you did not make this request, you can safely ignore this email.</p>
            <p><a href="%s">Reset Password</a></p>
            <p style="color:#666">This link will expire in 2 hours.</p>
            """.formatted(link);
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


}
