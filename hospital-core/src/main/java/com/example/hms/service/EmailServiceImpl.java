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

    /** Returns the app login URL, driven by the configured frontend base URL. */
    private String loginUrl() {
        return frontendBaseUrl + "/login";
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
        String safeRole = (roleDisplayName != null && !roleDisplayName.isBlank()) ? roleDisplayName : "the assigned role";
        String safeHospital = (hospitalDisplayName != null && !hospitalDisplayName.isBlank()) ? hospitalDisplayName : "our hospital network";
        String linkSection = "";
        if (profileCompletionUrl != null && !profileCompletionUrl.isBlank()) {
            linkSection = """
                <p style="margin:24px 0;">
                    <a href="%s" style="background:#2563eb;color:#fff;padding:12px 18px;border-radius:6px;text-decoration:none;display:inline-block;">Finish profile setup</a>
                </p>
                <p style="font-size: 14px; color: #666;">If the button above doesn't work, copy and paste this link into your browser:<br /><a href="%s">%s</a></p>
                """.formatted(profileCompletionUrl, profileCompletionUrl, profileCompletionUrl);
        }

        String credentialsSection = "";
        if (tempUsername != null && !tempUsername.isBlank() && tempPassword != null && !tempPassword.isBlank()) {
            credentialsSection = """
                <div style="background:#f0f9ff;border:1px solid #bae6fd;border-radius:8px;padding:16px;margin:16px 0;">
                    <p style="margin:0 0 8px;font-weight:600;color:#0369a1;">Your Temporary Login Credentials</p>
                    <p style="margin:4px 0;"><strong>Username:</strong> %s</p>
                    <p style="margin:4px 0;"><strong>Temporary Password:</strong> <code style="background:#e0f2fe;padding:2px 6px;border-radius:4px;">%s</code></p>
                    <p style="margin:8px 0 0;font-size:13px;color:#0369a1;">Please sign in and change your password immediately.</p>
                </div>
                """.formatted(tempUsername, tempPassword);
        }

        String body = """
            <h2>Confirm Your New Role Assignment</h2>
            <p>Hi %s,</p>
            <p>You have been assigned the role <strong>%s</strong> at <strong>%s</strong>.</p>
            <p>Please confirm this assignment with the verification code below:</p>
                <p style="font-size: 24px; font-weight: bold; letter-spacing: 4px;">%s</p>
            <p>Assignment reference: <strong>%s</strong></p>
            <p>If you did not expect this assignment, please contact the hospital administrator immediately.</p>
            %s
            %s
            <p style="color:#666">This code will expire soon for security purposes.</p>
            """.formatted(safeUserName, safeRole, safeHospital, confirmationCode, assignmentCode, linkSection, credentialsSection);

        sendHtml(List.of(to), List.of(), List.of(), "Action Required: Confirm Your Hospital Role Assignment", body);
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
    public void sendPasswordResetConfirmationEmail(String to, String displayName) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        String safeName = (displayName != null && !displayName.isBlank()) ? displayName : GENERIC_GREETING;
        String changedAt = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' HH:mm"));
        String body = """
            <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;background:#f8fafc;padding:24px;">
              <div style="background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);">

                <!-- Header -->
                <div style="background:linear-gradient(135deg,#065f46,#059669);padding:32px 40px;text-align:center;">
                  <h1 style="color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:0.5px;">
                    ✅ Password Changed Successfully
                  </h1>
                  <p style="color:#a7f3d0;margin:8px 0 0;font-size:14px;">Hospital Management System</p>
                </div>

                <!-- Body -->
                <div style="padding:36px 40px;">
                  <p style="font-size:15px;color:#1e293b;margin:0 0 16px;">Hi %s,</p>
                  <p style="font-size:15px;color:#334155;line-height:1.6;margin:0 0 24px;">
                    Your account password was successfully reset on <strong>%s</strong>.
                    You can now sign in with your new password.
                  </p>

                  <!-- CTA Button -->
                  <div style="text-align:center;margin:32px 0;">
                    <a href="%s"
                       style="display:inline-block;background:linear-gradient(135deg,#2563eb,#1d4ed8);color:#ffffff;
                              text-decoration:none;padding:14px 36px;border-radius:8px;font-size:16px;
                              font-weight:600;letter-spacing:0.3px;">
                      Sign In to Your Account
                    </a>
                  </div>

                  <hr style="border:none;border-top:1px solid #e2e8f0;margin:0 0 28px;"/>

                  <!-- Security warning -->
                  <div style="background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:16px 20px;margin-bottom:24px;">
                    <p style="margin:0 0 8px;font-size:14px;font-weight:700;color:#991b1b;">🚨 Didn't make this change?</p>
                    <p style="margin:0;font-size:14px;color:#7f1d1d;line-height:1.6;">
                      If you did <strong>not</strong> reset your password, your account may have been compromised.
                      <strong>
                        <a href="%s" style="color:#991b1b;">Sign in immediately</a>
                      </strong>
                      and contact your hospital administrator to secure your account.
                    </p>
                  </div>

                  <!-- Tips -->
                  <ul style="padding-left:20px;color:#64748b;font-size:13px;line-height:1.8;margin:0;">
                    <li>Never share your password with anyone, including hospital staff.</li>
                    <li>Use a unique password that you don't use on other sites.</li>
                    <li>Enable any available two-factor authentication for extra security.</li>
                  </ul>
                </div>

                <!-- Footer -->
                <div style="background:#f1f5f9;padding:20px 40px;text-align:center;border-top:1px solid #e2e8f0;">
                  <p style="margin:0;font-size:12px;color:#94a3b8;">
                    © %d Hospital Management System &nbsp;|&nbsp;
                    This is an automated message — please do not reply.
                  </p>
                </div>

              </div>
            </div>
            """.formatted(safeName, changedAt, loginUrl(), loginUrl(), java.time.Year.now().getValue());

        sendHtml(List.of(to), List.of(), List.of(), "Your HMS Password Has Been Changed", body);
        log.info("✅ Password reset confirmation email sent to {}", to);
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
            <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;background:#f8fafc;padding:24px;">
              <div style="background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);">

                <!-- Header -->
                <div style="background:linear-gradient(135deg,#1e3a5f,#2563eb);padding:32px 40px;text-align:center;">
                  <h1 style="color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:0.5px;">
                    🔒 Password Reset Request
                  </h1>
                  <p style="color:#bfdbfe;margin:8px 0 0;font-size:14px;">Hospital Management System</p>
                </div>

                <!-- Body -->
                <div style="padding:36px 40px;">
                  <p style="font-size:15px;color:#1e293b;margin:0 0 16px;">Hello,</p>
                  <p style="font-size:15px;color:#334155;line-height:1.6;margin:0 0 24px;">
                    We received a request to reset the password for your account. Click the button below to choose a new password.
                  </p>

                  <!-- CTA Button -->
                  <div style="text-align:center;margin:32px 0;">
                    <a href="%s"
                       style="display:inline-block;background:linear-gradient(135deg,#2563eb,#1d4ed8);color:#ffffff;
                              text-decoration:none;padding:14px 36px;border-radius:8px;font-size:16px;
                              font-weight:600;letter-spacing:0.3px;">
                      Reset My Password
                    </a>
                  </div>

                  <!-- Fallback link -->
                  <p style="font-size:13px;color:#64748b;text-align:center;margin:0 0 32px;">
                    Button not working? Copy and paste this link into your browser:<br/>
                    <a href="%s" style="color:#2563eb;word-break:break-all;">%s</a>
                  </p>

                  <hr style="border:none;border-top:1px solid #e2e8f0;margin:0 0 28px;"/>

                  <!-- Security warning -->
                  <div style="background:#fef9ec;border:1px solid #fcd34d;border-radius:8px;padding:16px 20px;margin-bottom:24px;">
                    <p style="margin:0 0 8px;font-size:14px;font-weight:700;color:#92400e;">⚠️ Didn't request this?</p>
                    <p style="margin:0;font-size:14px;color:#78350f;line-height:1.6;">
                      If you did <strong>not</strong> request a password reset, your account may be at risk.
                      Please <strong>do not click the link above</strong> and immediately
                      <a href="%s" style="color:#b45309;font-weight:600;">sign in to your account</a>
                      to verify your security settings, or contact your hospital administrator.
                    </p>
                  </div>

                  <!-- Expiry & tips -->
                  <ul style="padding-left:20px;color:#64748b;font-size:13px;line-height:1.8;margin:0;">
                    <li>This link expires in <strong>2 hours</strong>.</li>
                    <li>The link can only be used <strong>once</strong>.</li>
                    <li>Never share this link with anyone.</li>
                  </ul>
                </div>

                <!-- Footer -->
                <div style="background:#f1f5f9;padding:20px 40px;text-align:center;border-top:1px solid #e2e8f0;">
                  <p style="margin:0;font-size:12px;color:#94a3b8;">
                    © %d Hospital Management System &nbsp;|&nbsp;
                    This is an automated message — please do not reply.
                  </p>
                </div>

              </div>
            </div>
            """.formatted(link, link, link, loginUrl(), java.time.Year.now().getValue());
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
        String safeUsername = (username     != null && !username.isBlank())     ? username     : "—";
        String safePassword = (tempPassword != null && !tempPassword.isBlank()) ? tempPassword : "—";
        String loginUrl     = loginUrl();
        int year            = java.time.Year.now().getValue();

        String hospitalLine = safeHospital != null
            ? "<tr><td style='padding:6px 0;color:#64748b;font-weight:600;'>Hospital</td><td style='padding:6px 0 6px 16px;'>%s</td></tr>".formatted(safeHospital)
            : "";

        String body = """
            <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;background:#f8fafc;padding:24px;">
              <div style="background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);">

                <!-- Header -->
                <div style="background:linear-gradient(135deg,#1e3a5f,#2563eb);padding:32px 40px;text-align:center;">
                  <div style="font-size:36px;margin-bottom:8px;">🏥</div>
                  <h1 style="color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:-0.5px;">
                    Welcome to HMS
                  </h1>
                  <p style="color:#bfdbfe;margin:6px 0 0;font-size:14px;">
                    Your account has been created
                  </p>
                </div>

                <!-- Body -->
                <div style="padding:32px 40px;">
                  <p style="color:#1e293b;font-size:16px;margin-top:0;">Hi <strong>%s</strong>,</p>
                  <p style="color:#475569;line-height:1.6;">
                    A <strong>%s</strong> account has been created for you
                    %s
                    You can sign in immediately using the credentials below.
                    You will be prompted to change your password on first login.
                  </p>

                  <!-- Credentials box -->
                  <div style="background:#eff6ff;border:2px solid #bfdbfe;border-radius:10px;padding:20px 24px;margin:24px 0;">
                    <p style="margin:0 0 12px;font-weight:700;color:#1e3a8a;font-size:14px;text-transform:uppercase;letter-spacing:0.5px;">
                      Your Login Credentials
                    </p>
                    <table style="border-collapse:collapse;width:100%%;">
                      <tr>
                        <td style="padding:6px 0;color:#64748b;font-weight:600;">Username</td>
                        <td style="padding:6px 0 6px 16px;font-family:monospace;font-size:16px;color:#1e293b;">%s</td>
                      </tr>
                      <tr>
                        <td style="padding:6px 0;color:#64748b;font-weight:600;">Temp Password</td>
                        <td style="padding:6px 0 6px 16px;font-family:monospace;font-size:16px;color:#1e293b;">%s</td>
                      </tr>
                      <tr>
                        <td style="padding:6px 0;color:#64748b;font-weight:600;">Role</td>
                        <td style="padding:6px 0 6px 16px;">%s</td>
                      </tr>
                      %s
                    </table>
                  </div>

                  <!-- CTA -->
                  <p style="text-align:center;margin:28px 0;">
                    <a href="%s" style="background:#2563eb;color:#ffffff;text-decoration:none;padding:14px 32px;border-radius:8px;font-size:15px;font-weight:600;display:inline-block;">
                      Sign In to Your Account
                    </a>
                  </p>
                  <p style="text-align:center;font-size:13px;color:#94a3b8;">
                    Or copy this link:<br/>
                    <a href="%s" style="color:#2563eb;">%s</a>
                  </p>

                  <!-- Security warning -->
                  <div style="background:#fef3c7;border-left:4px solid #f59e0b;padding:14px 16px;border-radius:0 8px 8px 0;margin-top:24px;">
                    <p style="margin:0;font-size:14px;color:#92400e;">
                      <strong>⚠ Security notice:</strong> This email contains a temporary password.
                      Please sign in and change it immediately. Do not share this email with anyone.
                      If you did not expect this account, contact your system administrator right away.
                    </p>
                  </div>
                </div>

                <!-- Footer -->
                <div style="background:#f1f5f9;padding:16px 40px;text-align:center;border-top:1px solid #e2e8f0;">
                  <p style="margin:0;font-size:12px;color:#94a3b8;">
                    © %d HMS · Hospital Management System · This is an automated message — please do not reply.
                  </p>
                </div>
              </div>
            </div>
            """.formatted(
                safeName,
                safeRole,
                safeHospital != null ? "at <strong>" + safeHospital + "</strong>. " : ". ",
                safeUsername,
                safePassword,
                safeRole,
                hospitalLine,
                loginUrl, loginUrl, loginUrl,
                year);

        sendHtml(List.of(to), List.of(), List.of(),
            "Welcome to HMS — Your Account Is Ready", body);
        log.info("✅ Admin welcome email sent to {}", to);
    }

}

