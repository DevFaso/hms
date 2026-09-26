package com.example.hms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

/**
 * Composes every transactional mail from the message bundle.
 *
 * <p>A mail is read by its recipient, so every body is rendered in the
 * recipient's language — never the request's. The appointment mails take the
 * patient's stated language from the caller; the account and staff mails have
 * no per-user language to read yet and render in
 * {@link EmailService#DEFAULT_RECIPIENT_LOCALE}.
 *
 * <p>The HTML skeleton lives here. Only the human sentences live in the bundle,
 * one key per sentence, so a wording change retranslates one line. Every
 * dynamic value is a MessageFormat argument, escaped with {@link #escapeHtml}
 * before it goes in — the bundle never sees raw user input.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final MessageSource messageSource;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    // SMTP readiness inputs, mirroring StartupSubsystemLogger.announceMail():
    // host required; auth=false permits a username-less relay; auth=true
    // requires BOTH username and password. Used only to label delivery-report
    // outcomes (NOT_CONFIGURED vs FAILED); sends are still attempted.
    @Value("${spring.mail.host:}")
    private String configuredMailHost;

    @Value("${spring.mail.username:}")
    private String configuredMailUsername;

    @Value("${spring.mail.password:}")
    private String configuredMailPassword;

    /** Spring Boot's default when the property is unset is auth ON. */
    @Value("${spring.mail.properties.mail.smtp.auth:true}")
    private String smtpAuthProperty;

    private static final DateTimeFormatter CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm");

    // Bundle keys used by more than one mail.
    // Markup fragments repeated across the mails (Sonar S1192).
    private static final String HTML_BR = "<br/>";
    private static final String HTML_CENTER_BLOCK = "<div style=\"text-align:center;margin:32px 0;\">";
    private static final String HTML_A_OPEN = "<a href=\"";
    private static final String HTML_STRONG_OPEN = "<strong>";
    private static final String HTML_STRONG_CLOSE = "</strong>";
    private static final String HTML_STRONG_CLOSE_SP = "</strong> ";
    private static final String HTML_P_DIV_CLOSE = "</p></div>";
    private static final String HTML_DIV_BACKGROUND = "<div style=\"background:";
    private static final String HTML_STYLE_ATTR = "\" style=\"";
    private static final String COLOR_BRAND_TINT = "#bfdbfe";
    private static final String KEY_BRAND = "email.common.brand";
    private static final String KEY_GREETING_HI = "email.common.greeting.hi";
    private static final String KEY_GREETING_DEAR = "email.common.greeting.dear";
    private static final String KEY_GREETING_ANONYMOUS = "email.common.greeting.anonymous";
    private static final String KEY_SIGNIN_BUTTON = "email.common.signin.button";
    private static final String KEY_LINK_FALLBACK = "email.common.link.fallback";
    private static final String KEY_UNEXPECTED_TITLE = "email.common.unexpected.title";
    private static final String KEY_ACTIVATION_BUTTON = "email.activation.button";
    private static final String KEY_ROLE_ASSIGNMENT_SUBJECT = "email.role.assignment.subject";
    private static final String KEY_APPOINTMENT_CONTACT = "email.appointment.contact";
    private static final String KEY_LABEL_DATE = "email.appointment.label.date";
    private static final String KEY_LABEL_TIME = "email.appointment.label.time";

    // Shared HTML fragments.
    /** Opening wrapper for the body column of every templated email. */
    private static final String BODY_OPEN = "<div style=\"padding:36px 40px;\">";
    private static final String GREETING_PARAGRAPH_OPEN = "<p style=\"font-size:15px;color:#1e293b;margin:0 0 16px;\">";
    private static final String BODY_PARAGRAPH_OPEN = "<p style=\"font-size:15px;color:#334155;line-height:1.6;margin:0 0 24px;\">";
    private static final String CLOSE_PARAGRAPH = "</p>";
    private static final String CLOSE_DIV = "</div>";
    private static final String HR = "<hr style=\"border:none;border-top:1px solid #e2e8f0;margin:0 0 28px;\"/>";
    private static final String ALERT_BOX_OPEN = "<div style=\"background:#fef2f2;border:1px solid #fecaca;border-radius:8px;"
        + "padding:16px 20px;margin-bottom:24px;\">";
    private static final String ALERT_TITLE_OPEN = "<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;color:#991b1b;\">";
    private static final String ALERT_TEXT_OPEN = "<p style=\"margin:0;font-size:14px;color:#7f1d1d;line-height:1.6;\">";
    private static final String PRIMARY_BUTTON_STYLE = "display:inline-block;"
        + "background:linear-gradient(135deg,#2563eb,#1d4ed8);color:#ffffff;"
        + "text-decoration:none;padding:14px 36px;border-radius:8px;font-size:16px;"
        + "font-weight:600;letter-spacing:0.3px;";
    private static final String GRADIENT_BLUE = "linear-gradient(135deg,#1e40af,#2563eb)";
    private static final String GRADIENT_NAVY = "linear-gradient(135deg,#1e3a5f,#2563eb)";
    private static final String GRADIENT_GREEN = "linear-gradient(135deg,#065f46,#059669)";
    private static final String LINK_STYLE = "style=\"color:#2563eb;\"";

    /** Returns the app login URL, driven by the configured frontend base URL. */
    private String loginUrl() {
        return frontendBaseUrl + "/login";
    }

    // -------------------------------------------------------------------------
    // Bundle access
    // -------------------------------------------------------------------------

    /**
     * One bundle sentence in the recipient's language. Arguments are inserted
     * verbatim by MessageFormat, so anything user-supplied must already be
     * {@link #escapeHtml escaped}.
     */
    private String text(Locale locale, String key, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    private static Locale recipientLocale(Locale locale) {
        return locale != null ? locale : DEFAULT_RECIPIENT_LOCALE;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** "Hi {name}," or the anonymous greeting when no name is known. */
    private String greetingHi(Locale locale, String displayName) {
        return hasText(displayName)
            ? text(locale, KEY_GREETING_HI, escapeHtml(displayName))
            : text(locale, KEY_GREETING_ANONYMOUS);
    }

    /** Long date in the recipient's language: "5 septembre 2026", "September 5, 2026". */
    private static String humanDate(LocalDate date, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date);
    }

    // -------------------------------------------------------------------------
    // Appointment mails (recipient: the patient)
    // -------------------------------------------------------------------------

    @Override
    public void sendAppointmentRescheduledEmail(String to, String patientName, String hospitalName, String staffName,
                                                String newAppointmentDate, String newAppointmentTime,
                                                String hospitalEmail, String hospitalPhone,
                                                String rescheduleLink, String cancelLink, Locale locale) {
        validateAddresses(List.of(to));
        Locale l = recipientLocale(locale);
        log.info("📧 Sending appointment rescheduled email to: {}", to);
        String subject = text(l, "email.appointment.rescheduled.subject");
        String body = heading(subject)
            + paragraph(text(l, KEY_GREETING_DEAR, escapeHtml(patientName)))
            + paragraph(text(l, "email.appointment.rescheduled.body.intro", escapeHtml(hospitalName), escapeHtml(staffName)))
            + appointmentDetails(l, "email.appointment.label.newDate", "email.appointment.label.newTime",
                newAppointmentDate, newAppointmentTime)
            + appointmentLinks(l, "email.appointment.rescheduled.links.intro", rescheduleLink, cancelLink)
            + paragraph(text(l, KEY_APPOINTMENT_CONTACT, escapeHtml(hospitalEmail), escapeHtml(hospitalPhone)));
        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("✅ Appointment rescheduled email sent to {}", to);
    }

    @Override
    public void sendAppointmentCancelledEmail(String to, String patientName, String hospitalName, String staffName,
                                              String appointmentDate, String appointmentTime,
                                              String hospitalEmail, String hospitalPhone, Locale locale) {
        validateAddresses(List.of(to));
        Locale l = recipientLocale(locale);
        log.info("📧 Sending appointment cancelled email to: {}", to);
        String subject = text(l, "email.appointment.cancelled.subject");
        String body = heading(subject)
            + paragraph(text(l, KEY_GREETING_DEAR, escapeHtml(patientName)))
            + paragraph(text(l, "email.appointment.cancelled.body.intro", escapeHtml(hospitalName), escapeHtml(staffName),
                escapeHtml(appointmentDate), escapeHtml(appointmentTime)))
            + paragraph(text(l, KEY_APPOINTMENT_CONTACT, escapeHtml(hospitalEmail), escapeHtml(hospitalPhone)));
        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("✅ Appointment cancelled email sent to {}", to);
    }

    @Override
    public void sendAppointmentCompletedEmail(String to, String patientName, String hospitalName, String staffName,
                                              String appointmentDate, String appointmentTime,
                                              String hospitalEmail, String hospitalPhone, Locale locale) {
        validateAddresses(List.of(to));
        Locale l = recipientLocale(locale);
        log.info("📧 Sending appointment completed email to: {}", to);
        String subject = text(l, "email.appointment.completed.subject");
        String body = heading(subject)
            + paragraph(text(l, KEY_GREETING_DEAR, escapeHtml(patientName)))
            + paragraph(text(l, "email.appointment.completed.body.intro", escapeHtml(hospitalName), escapeHtml(staffName),
                escapeHtml(appointmentDate), escapeHtml(appointmentTime)))
            + paragraph(text(l, KEY_APPOINTMENT_CONTACT, escapeHtml(hospitalEmail), escapeHtml(hospitalPhone)));
        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("✅ Appointment completed email sent to {}", to);
    }

    @Override
    public void sendAppointmentNoShowEmail(String to, String patientName, String hospitalName, String staffName,
                                           String appointmentDate, String appointmentTime,
                                           String hospitalEmail, String hospitalPhone, Locale locale) {
        validateAddresses(List.of(to));
        Locale l = recipientLocale(locale);
        log.info("📧 Sending appointment no-show email to: {}", to);
        String subject = text(l, "email.appointment.noshow.subject");
        String body = heading(subject)
            + paragraph(text(l, KEY_GREETING_DEAR, escapeHtml(patientName)))
            + paragraph(text(l, "email.appointment.noshow.body.intro", escapeHtml(hospitalName), escapeHtml(staffName),
                escapeHtml(appointmentDate), escapeHtml(appointmentTime)))
            + paragraph(text(l, "email.appointment.noshow.contact", escapeHtml(hospitalEmail), escapeHtml(hospitalPhone)));
        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("✅ Appointment no-show email sent to {}", to);
    }

    @Override
    public void sendAppointmentConfirmationEmail(String to, String patientName, String hospitalName, String staffName,
                                                 String appointmentDate, String appointmentTime,
                                                 String hospitalEmail, String hospitalPhone,
                                                 String rescheduleLink, String cancelLink, Locale locale) {
        validateAddresses(List.of(to));
        Locale l = recipientLocale(locale);
        log.info("📧 Sending appointment confirmation email to: {}", to);
        String subject = text(l, "email.appointment.confirmed.subject");
        String body = heading(subject)
            + paragraph(text(l, KEY_GREETING_DEAR, escapeHtml(patientName)))
            + paragraph(text(l, "email.appointment.confirmed.body.intro", escapeHtml(hospitalName), escapeHtml(staffName)))
            + appointmentDetails(l, KEY_LABEL_DATE, KEY_LABEL_TIME, appointmentDate, appointmentTime)
            + appointmentLinks(l, "email.appointment.links.intro", rescheduleLink, cancelLink)
            + paragraph(text(l, KEY_APPOINTMENT_CONTACT, escapeHtml(hospitalEmail), escapeHtml(hospitalPhone)));
        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("✅ Appointment confirmation email sent to {}", to);
    }

    private static String heading(String s) {
        return "<h2>" + s + "</h2>";
    }

    private static String paragraph(String s) {
        return "<p>" + s + CLOSE_PARAGRAPH;
    }

    private static String anchor(String rawUrl) {
        String url = escapeHtml(rawUrl);
        return HTML_A_OPEN + url + "\">" + url + "</a>";
    }

    private String appointmentDetails(Locale l, String dateLabelKey, String timeLabelKey, String date, String time) {
        return "<p><strong>" + text(l, dateLabelKey) + HTML_STRONG_CLOSE_SP + escapeHtml(date) + "<br>"
            + HTML_STRONG_OPEN + text(l, timeLabelKey) + HTML_STRONG_CLOSE_SP + escapeHtml(time) + CLOSE_PARAGRAPH;
    }

    private String appointmentLinks(Locale l, String introKey, String rescheduleLink, String cancelLink) {
        return paragraph(text(l, introKey))
            + "<p><a href=\"" + escapeHtml(rescheduleLink) + "\">" + text(l, "email.appointment.links.reschedule") + "</a><br>"
            + HTML_A_OPEN + escapeHtml(cancelLink) + "\">" + text(l, "email.appointment.links.cancel") + "</a></p>";
    }

    // -------------------------------------------------------------------------
    // Transport
    // -------------------------------------------------------------------------

    @Override
    public boolean deliversRealEmail() {
        if (configuredMailHost == null || configuredMailHost.isBlank()) {
            return false;
        }
        boolean authEnabled = !"false".equalsIgnoreCase(smtpAuthProperty);
        if (!authEnabled) {
            // Unauthenticated relay — credentials are not required.
            return true;
        }
        return configuredMailUsername != null && !configuredMailUsername.isBlank()
            && configuredMailPassword != null && !configuredMailPassword.isBlank();
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

    // -------------------------------------------------------------------------
    // Role assignment (recipient: the assignee — a patient or a staff member)
    // -------------------------------------------------------------------------

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
        // The assignee is a User, which records no language (see
        // EmailService#DEFAULT_RECIPIENT_LOCALE). A patient at this point has
        // no medical history yet either, so the resolver would answer the same.
        Locale l = DEFAULT_RECIPIENT_LOCALE;
        log.info("📧 Sending role assignment confirmation email to: {}", to);

        boolean isPatient = roleDisplayName != null
                && roleDisplayName.toUpperCase(Locale.ROOT).contains("PATIENT");
        // Plain for the subject line, escaped for the HTML body.
        String hospitalPlain = hasText(hospitalDisplayName)
            ? hospitalDisplayName : text(l, "email.role.assignment.fallback.hospital");
        String hospitalHtml = escapeHtml(hospitalPlain);
        // Only the staff mail names the role; the patient mail never did.
        String roleHtml = hasText(roleDisplayName) ? escapeHtml(roleDisplayName) : text(l, "email.role.assignment.fallback.role");
        String codeHtml = escapeHtml(confirmationCode);
        String greeting = greetingHi(l, userName);

        String linkSection = "";
        if (hasText(profileCompletionUrl)) {
            String url = escapeHtml(profileCompletionUrl);
            String buttonLabel = text(l, isPatient ? "email.patient.welcome.button" : "email.role.assignment.button");
            linkSection = "<p style=\"margin:24px 0;\">"
                + HTML_A_OPEN + url + "\" style=\"background:#2563eb;color:#fff;padding:12px 18px;border-radius:6px;"
                + "text-decoration:none;display:inline-block;\">" + buttonLabel + "</a></p>"
                + "<p style=\"font-size: 14px; color: #666;\">" + text(l, "email.role.assignment.link.help")
                + "<br />" + text(l, "email.role.assignment.link.fallback")
                + "<br /><a href=\"" + url + "\">" + url + "</a></p>";
        }

        String credentialsSection = "";
        if (hasText(tempUsername) && hasText(tempPassword)) {
            credentialsSection = "<div style=\"background:#f0f9ff;border:1px solid #bae6fd;border-radius:8px;padding:16px;margin:16px 0;\">"
                + "<p style=\"margin:0 0 8px;font-weight:600;color:#0369a1;\">" + text(l, "email.role.assignment.credentials.title") + CLOSE_PARAGRAPH
                + "<p style=\"margin:4px 0;\"><strong>" + text(l, "email.role.assignment.credentials.username") + HTML_STRONG_CLOSE_SP
                + escapeHtml(tempUsername) + CLOSE_PARAGRAPH
                + "<p style=\"margin:4px 0;\"><strong>" + text(l, "email.role.assignment.credentials.password") + HTML_STRONG_CLOSE_SP
                + "<code style=\"background:#e0f2fe;padding:2px 6px;border-radius:4px;\">" + escapeHtml(tempPassword) + "</code></p>"
                + "<p style=\"margin:8px 0 0;font-size:13px;color:#0369a1;\">" + text(l, "email.role.assignment.credentials.change") + CLOSE_PARAGRAPH
                + CLOSE_DIV;
        }

        String subject;
        String body;
        if (isPatient) {
            subject = text(l, "email.patient.welcome.subject", hospitalPlain);
            body = heading(text(l, "email.patient.welcome.heading", hospitalHtml))
                + paragraph(greeting)
                + paragraph(text(l, "email.patient.welcome.body.created", hospitalHtml))
                + paragraph(text(l, "email.patient.welcome.body.instructions"))
                + "<p style=\"font-size: 28px; font-weight: bold; letter-spacing: 6px; text-align: center; "
                + "background: #f8fafc; border: 2px dashed #2563eb; border-radius: 8px; padding: 16px; margin: 16px 0;\">"
                + codeHtml + CLOSE_PARAGRAPH
                + credentialsSection
                + linkSection
                + paragraph(HTML_STRONG_OPEN + text(l, "email.patient.welcome.body.inactive") + HTML_STRONG_CLOSE)
                + "<div style=\"background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:12px 16px;margin:16px 0;\">"
                + "<p style=\"margin:0;font-size:13px;color:#991b1b;\"><strong>" + text(l, "email.patient.welcome.body.unexpected.title")
                + HTML_STRONG_CLOSE_SP + text(l, "email.patient.welcome.body.unexpected") + CLOSE_PARAGRAPH
                + CLOSE_DIV
                + "<p style=\"color:#666;font-size:13px;\">" + text(l, "email.patient.welcome.body.expiry") + CLOSE_PARAGRAPH;
        } else {
            subject = text(l, KEY_ROLE_ASSIGNMENT_SUBJECT);
            body = heading(text(l, "email.role.assignment.heading"))
                + paragraph(greeting)
                + paragraph(text(l, "email.role.assignment.body.assigned", roleHtml, hospitalHtml))
                + paragraph(text(l, "email.role.assignment.body.instructions"))
                + "<p style=\"font-size: 24px; font-weight: bold; letter-spacing: 4px;\">" + codeHtml + CLOSE_PARAGRAPH
                + paragraph(text(l, "email.role.assignment.body.reference", escapeHtml(assignmentCode)))
                + linkSection
                + credentialsSection
                + paragraph(HTML_STRONG_OPEN + text(l, "email.role.assignment.body.inactive") + HTML_STRONG_CLOSE)
                + paragraph(text(l, "email.role.assignment.body.unexpected"))
                + "<p style=\"color:#666\">" + text(l, "email.role.assignment.body.expiry") + CLOSE_PARAGRAPH;
        }

        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("✅ Role assignment confirmation email sent to {}", to);
    }

    // -------------------------------------------------------------------------
    // Account mails (recipient: the account holder)
    // -------------------------------------------------------------------------

    @Override
    public void sendActivationEmail(String to, String activationLink) {
        sendActivationEmail(to, activationLink, null, null, null);
    }

    @Override
    public void sendActivationEmail(String to, String activationLink,
                                     String patientName, String username,
                                     String hospitalName) {
        Locale l = DEFAULT_RECIPIENT_LOCALE;
        var body = buildActivationEmailBody(l, activationLink, patientName, username, hospitalName);
        sendHtml(List.of(to), List.of(), List.of(), text(l, "email.activation.subject"), body);
        log.info("✅ Activation email sent to {}", to);
    }

    @Override
    public void sendPasswordResetEmail(String to, String resetLink) {
        Locale l = DEFAULT_RECIPIENT_LOCALE;
        var body = buildResetEmailBody(l, resetLink);
        sendHtml(List.of(to), List.of(), List.of(), text(l, "email.password.reset.subject"), body);
        log.info("✅ Password reset email sent to {}", to);
    }

    @Override
    public void sendPasswordResetConfirmationEmail(String to, String displayName) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        Locale l = DEFAULT_RECIPIENT_LOCALE;
        LocalDateTime changedAt = LocalDateTime.now(ZoneOffset.UTC);

        String header = brandHeader(l, GRADIENT_GREEN, "#a7f3d0",
            "&#9989; " + text(l, "email.password.changed.heading"));

        String signInNow = "<strong><a href=\"" + escapeHtml(loginUrl()) + "\" style=\"color:#991b1b;\">"
            + text(l, "email.password.changed.body.unexpected.link") + "</a></strong>";

        String bodyContent = BODY_OPEN
            + GREETING_PARAGRAPH_OPEN + greetingHi(l, displayName) + CLOSE_PARAGRAPH
            + BODY_PARAGRAPH_OPEN
            + text(l, "email.password.changed.body.intro", humanDate(changedAt.toLocalDate(), l), CLOCK_TIME.format(changedAt))
            + CLOSE_PARAGRAPH
            + signInButton(l)
            + HR
            + alertBox(text(l, "email.password.changed.body.unexpected.title"),
                text(l, "email.password.changed.body.unexpected", signInNow))
            + "<ul style=\"padding-left:20px;color:#64748b;font-size:13px;line-height:1.8;margin:0;\">"
            + "<li>" + text(l, "email.password.changed.tip.share") + "</li>"
            + "<li>" + text(l, "email.password.changed.tip.unique") + "</li>"
            + "<li>" + text(l, "email.password.changed.tip.mfa") + "</li>"
            + "</ul>"
            + CLOSE_DIV;

        String body = htmlEmailWrapper(header + bodyContent + htmlEmailFooter(l));

        sendHtml(List.of(to), List.of(), List.of(), text(l, "email.password.changed.subject"), body);
        log.info("✅ Password reset confirmation email sent to {}", to);
    }

    @Override
    public void sendAccountRestoredEmail(String to, String displayName) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        Locale l = DEFAULT_RECIPIENT_LOCALE;
        LocalDateTime restoredAt = LocalDateTime.now(ZoneOffset.UTC);

        String header = brandHeader(l, GRADIENT_BLUE, COLOR_BRAND_TINT,
            "&#9989; " + text(l, "email.account.restored.heading"));

        String bodyContent = BODY_OPEN
            + GREETING_PARAGRAPH_OPEN + greetingHi(l, displayName) + CLOSE_PARAGRAPH
            + BODY_PARAGRAPH_OPEN
            + text(l, "email.account.restored.body.intro", humanDate(restoredAt.toLocalDate(), l), CLOCK_TIME.format(restoredAt))
            + CLOSE_PARAGRAPH
            + signInButton(l)
            + HR
            + alertBox(text(l, "email.account.restored.body.unexpected.title"),
                text(l, "email.account.restored.body.unexpected"))
            + CLOSE_DIV;

        String body = htmlEmailWrapper(header + bodyContent + htmlEmailFooter(l));
        sendHtml(List.of(to), List.of(), List.of(), text(l, "email.account.restored.subject"), body);
        log.info("✅ Account restored notification email sent to {}", to);
    }

    @Override
    public void sendRecoveryContactVerificationEmail(String to, String verificationCode) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        Locale l = DEFAULT_RECIPIENT_LOCALE;
        String escapedCode = escapeHtml(verificationCode);

        String header = brandHeader(l, GRADIENT_BLUE, COLOR_BRAND_TINT,
            "&#128274; " + text(l, "email.recovery.contact.heading"));

        String bodyContent = BODY_OPEN
            + BODY_PARAGRAPH_OPEN + text(l, "email.recovery.contact.body.intro") + CLOSE_PARAGRAPH
            + HTML_CENTER_BLOCK
            + "<div style=\"display:inline-block;background:#f1f5f9;border:2px dashed #94a3b8;"
            + "border-radius:12px;padding:20px 40px;\">"
            + "<span style=\"font-size:32px;font-weight:700;letter-spacing:8px;color:#1e293b;font-family:monospace;\">"
            + escapedCode
            + "</span>"
            + CLOSE_DIV
            + CLOSE_DIV
            + "<p style=\"font-size:14px;color:#64748b;text-align:center;margin:0 0 24px;\">"
            + text(l, "email.recovery.contact.body.expiry")
            + CLOSE_PARAGRAPH
            + HR
            + alertBox(text(l, KEY_UNEXPECTED_TITLE), text(l, "email.recovery.contact.body.unexpected"))
            + CLOSE_DIV;

        String body = htmlEmailWrapper(header + bodyContent + htmlEmailFooter(l));
        sendHtml(List.of(to), List.of(), List.of(), text(l, "email.recovery.contact.subject"), body);
        log.info("✅ Recovery contact verification email sent to {}", to);
    }

    @Override
    public void sendEmailChangeVerificationEmail(String to, String verificationCode, Locale locale) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        Locale l = recipientLocale(locale);
        String escapedCode = escapeHtml(verificationCode);

        String header = brandHeader(l, GRADIENT_BLUE, COLOR_BRAND_TINT,
            "&#128274; " + text(l, "email.change.code.heading"));

        String bodyContent = BODY_OPEN
            + BODY_PARAGRAPH_OPEN + text(l, "email.change.code.body.intro") + CLOSE_PARAGRAPH
            + HTML_CENTER_BLOCK
            + "<div style=\"display:inline-block;background:#f1f5f9;border:2px dashed #94a3b8;"
            + "border-radius:12px;padding:20px 40px;\">"
            + "<span style=\"font-size:32px;font-weight:700;letter-spacing:8px;color:#1e293b;font-family:monospace;\">"
            + escapedCode
            + "</span>"
            + CLOSE_DIV
            + CLOSE_DIV
            + "<p style=\"font-size:14px;color:#64748b;text-align:center;margin:0 0 24px;\">"
            + text(l, "email.change.code.body.expiry")
            + CLOSE_PARAGRAPH
            + HR
            + alertBox(text(l, KEY_UNEXPECTED_TITLE), text(l, "email.change.code.body.unexpected"))
            + CLOSE_DIV;

        String body = htmlEmailWrapper(header + bodyContent + htmlEmailFooter(l));
        sendHtml(List.of(to), List.of(), List.of(), text(l, "email.change.code.subject"), body);
        log.info("✅ Email-change verification code sent");
    }

    @Override
    public void sendEmailChangedNoticeEmail(String to, String displayName, String maskedAddress, Locale locale) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        Locale l = recipientLocale(locale);
        LocalDateTime changedAt = LocalDateTime.now(ZoneOffset.UTC);

        String header = brandHeader(l, GRADIENT_BLUE, COLOR_BRAND_TINT,
            "&#128274; " + text(l, "email.change.notice.heading"));

        String bodyContent = BODY_OPEN
            + GREETING_PARAGRAPH_OPEN + greetingHi(l, displayName) + CLOSE_PARAGRAPH
            + BODY_PARAGRAPH_OPEN
            + text(l, "email.change.notice.body.intro", escapeHtml(maskedAddress),
                humanDate(changedAt.toLocalDate(), l), CLOCK_TIME.format(changedAt))
            + CLOSE_PARAGRAPH
            + HR
            + alertBox(text(l, KEY_UNEXPECTED_TITLE), text(l, "email.change.notice.body.unexpected"))
            + CLOSE_DIV;

        String body = htmlEmailWrapper(header + bodyContent + htmlEmailFooter(l));
        sendHtml(List.of(to), List.of(), List.of(), text(l, "email.change.notice.subject"), body);
        log.info("✅ Email-changed notice sent to the previous address");
    }

    @Override
    public void sendUsernameReminderEmail(String toEmail, String username, Locale locale) {
        Locale l = recipientLocale(locale);
        var subject = text(l, "email.username.reminder.subject");
        var body = heading(text(l, "email.username.reminder.heading"))
            + paragraph(text(l, "email.username.reminder.body.intro"))
            + paragraph(HTML_STRONG_OPEN + text(l, "email.username.reminder.body.username") + HTML_STRONG_CLOSE_SP + escapeHtml(username))
            + paragraph(text(l, "email.username.reminder.body.signin", anchor(loginUrl())))
            + "<p style=\"color:#666\">" + text(l, "email.username.reminder.body.unexpected") + CLOSE_PARAGRAPH;
        sendHtml(List.of(toEmail), List.of(), List.of(), subject, body);
        log.info("✅ Username reminder email sent to {}", toEmail);
    }

    @Override
    public void sendPasswordRotationReminderEmail(String to, String displayName, long daysRemaining, LocalDate dueOn) {
        validateAddresses(List.of(to));
        Locale l = DEFAULT_RECIPIENT_LOCALE;
        var subject = text(l, "email.password.rotation.reminder.subject");
        // Two sentences rather than a ChoiceFormat: the bundle rule is bare
        // {n} placeholders only. The count goes in as text so MessageFormat
        // does not group digits ("1 234").
        String remaining = daysRemaining == 1
            ? text(l, "email.password.rotation.reminder.body.remaining.one")
            : text(l, "email.password.rotation.reminder.body.remaining.many", String.valueOf(daysRemaining));
        var body = heading(text(l, "email.password.rotation.reminder.heading"))
            + paragraph(greetingHi(l, displayName))
            + paragraph(text(l, "email.password.rotation.reminder.body.due", humanDate(dueOn, l)))
            + paragraph(remaining)
            + paragraph(text(l, "email.password.rotation.reminder.body.action", anchor(loginUrl())))
            + paragraph(text(l, "email.password.rotation.reminder.body.ignore"));
        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("📧 Password rotation reminder sent to {} ({} day(s) remaining)", to, daysRemaining);
    }

    @Override
    public void sendPasswordRotationForceChangeEmail(String to, String displayName, LocalDate dueOn, long daysOverdue) {
        validateAddresses(List.of(to));
        Locale l = DEFAULT_RECIPIENT_LOCALE;
        var subject = text(l, "email.password.rotation.force.subject");
        String due = humanDate(dueOn, l);
        String overdue = daysOverdue == 1
            ? text(l, "email.password.rotation.force.body.overdue.one", due)
            : text(l, "email.password.rotation.force.body.overdue.many", due, String.valueOf(daysOverdue));
        var body = heading(text(l, "email.password.rotation.force.heading"))
            + paragraph(greetingHi(l, displayName))
            + paragraph(overdue)
            + paragraph(text(l, "email.password.rotation.force.body.action", anchor(loginUrl())))
            + paragraph(text(l, "email.password.rotation.force.body.restricted"));
        sendHtml(List.of(to), List.of(), List.of(), subject, body);
        log.info("📧 Password rotation enforcement notice sent to {} ({} day(s) overdue)", to, daysOverdue);
    }

    private String buildActivationEmailBody(Locale l, String link, String patientName,
                                            String username, String hospitalName) {
        String safeHosp = hasText(hospitalName) ? escapeHtml(hospitalName) : null;
        String safeLink = escapeHtml(link);

        String header = HTML_DIV_BACKGROUND + GRADIENT_NAVY + ";"
            + "padding:32px 40px;text-align:center;\">"
            + "<h1 style=\"color:#ffffff;margin:0;font-size:22px;font-weight:700;\">"
            + text(l, "email.activation.heading") + "</h1>"
            + (safeHosp != null
                ? "<p style=\"color:#bfdbfe;margin:8px 0 0;font-size:14px;\">" + safeHosp + CLOSE_PARAGRAPH
                : "")
            + CLOSE_DIV;

        StringBuilder body = new StringBuilder();
        body.append(BODY_OPEN);
        if (hasText(patientName)) {
            body.append(GREETING_PARAGRAPH_OPEN).append(text(l, KEY_GREETING_HI, escapeHtml(patientName))).append(CLOSE_PARAGRAPH);
        }
        body.append(BODY_PARAGRAPH_OPEN)
            .append(text(l, "email.activation.body.created"))
            .append(CLOSE_PARAGRAPH);

        if (hasText(username)) {
            body.append("<div style=\"background:#f0f9ff;border:1px solid #bae6fd;border-radius:8px;")
                .append("padding:16px 20px;margin:0 0 24px;\">")
                .append("<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;color:#0c4a6e;\">")
                .append(text(l, "email.activation.credentials.title")).append(CLOSE_PARAGRAPH)
                .append("<p style=\"margin:0;font-size:14px;color:#075985;\">")
                .append(HTML_STRONG_OPEN).append(text(l, "email.activation.credentials.username")).append(HTML_STRONG_CLOSE_SP)
                .append(escapeHtml(username)).append(HTML_BR)
                .append(HTML_STRONG_OPEN).append(text(l, "email.activation.credentials.password")).append(HTML_STRONG_CLOSE_SP)
                .append(text(l, "email.activation.credentials.password.hint"))
                .append(HTML_P_DIV_CLOSE);
        }

        body.append(HTML_CENTER_BLOCK)
            .append(HTML_A_OPEN).append(safeLink).append(HTML_STYLE_ATTR).append(PRIMARY_BUTTON_STYLE).append("\">")
            .append(text(l, KEY_ACTIVATION_BUTTON)).append("</a>")
            .append(CLOSE_DIV)
            .append("<p style=\"font-size:13px;color:#64748b;text-align:center;margin:0 0 32px;\">")
            .append(text(l, KEY_LINK_FALLBACK)).append(HTML_BR)
            .append(HTML_A_OPEN).append(safeLink).append("\" style=\"color:#2563eb;word-break:break-all;\">")
            .append(safeLink).append("</a>")
            .append(CLOSE_PARAGRAPH)
            .append("<hr style=\"border:none;border-top:1px solid #e2e8f0;margin:0 0 20px;\"/>")
            .append("<p style=\"font-size:13px;color:#94a3b8;text-align:center;\">")
            .append(text(l, "email.activation.body.expiry")).append(' ')
            .append(text(l, "email.activation.body.unexpected"))
            .append(HTML_P_DIV_CLOSE);

        return htmlEmailWrapper(header + body + htmlEmailFooter(l));
    }

    private String buildResetEmailBody(Locale l, String link) {
        String safeLink = escapeHtml(link);
        String header = brandHeader(l, GRADIENT_NAVY, COLOR_BRAND_TINT,
            "&#128274; " + text(l, "email.password.reset.heading"));

        String signIn = HTML_A_OPEN + escapeHtml(loginUrl()) + "\" style=\"color:#b45309;font-weight:600;\">"
            + text(l, "email.password.reset.body.unexpected.link") + "</a>";

        String bodyContent = BODY_OPEN
            + GREETING_PARAGRAPH_OPEN + text(l, "email.common.greeting.hello") + CLOSE_PARAGRAPH
            + BODY_PARAGRAPH_OPEN + text(l, "email.password.reset.body.intro") + CLOSE_PARAGRAPH
            + HTML_CENTER_BLOCK
            + HTML_A_OPEN + safeLink + HTML_STYLE_ATTR + PRIMARY_BUTTON_STYLE + "\">"
            + text(l, "email.password.reset.button") + "</a>"
            + CLOSE_DIV
            + "<p style=\"font-size:13px;color:#64748b;text-align:center;margin:0 0 32px;\">"
            + text(l, KEY_LINK_FALLBACK) + HTML_BR
            + HTML_A_OPEN + safeLink + "\" style=\"color:#2563eb;word-break:break-all;\">" + safeLink + "</a>"
            + CLOSE_PARAGRAPH
            + HR
            + "<div style=\"background:#fef9ec;border:1px solid #fcd34d;border-radius:8px;"
            + "padding:16px 20px;margin-bottom:24px;\">"
            + "<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;color:#92400e;\">"
            + "&#9888;&#65039; " + text(l, KEY_UNEXPECTED_TITLE) + CLOSE_PARAGRAPH
            + "<p style=\"margin:0;font-size:14px;color:#78350f;line-height:1.6;\">"
            + text(l, "email.password.reset.body.unexpected.risk") + ' '
            + text(l, "email.password.reset.body.unexpected.action", signIn)
            + HTML_P_DIV_CLOSE
            + "<ul style=\"padding-left:20px;color:#64748b;font-size:13px;line-height:1.8;margin:0;\">"
            + "<li>" + text(l, "email.password.reset.body.expiry") + "</li>"
            + "<li>" + text(l, "email.password.reset.body.once") + "</li>"
            + "<li>" + text(l, "email.password.reset.body.share") + "</li>"
            + "</ul>"
            + CLOSE_DIV;

        return htmlEmailWrapper(header + bodyContent + htmlEmailFooter(l));
    }

    // -------------------------------------------------------------------------
    // Shared HTML building blocks
    // -------------------------------------------------------------------------

    /** Coloured header band with the brand line under the title. */
    private String brandHeader(Locale l, String gradient, String subtitleColor, String titleHtml) {
        return HTML_DIV_BACKGROUND + gradient + ";padding:32px 40px;text-align:center;\">"
            + "<h1 style=\"color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:0.5px;\">"
            + titleHtml
            + "</h1>"
            + "<p style=\"color:" + subtitleColor + ";margin:8px 0 0;font-size:14px;\">" + text(l, KEY_BRAND) + CLOSE_PARAGRAPH
            + CLOSE_DIV;
    }

    private String signInButton(Locale l) {
        return HTML_CENTER_BLOCK
            + HTML_A_OPEN + escapeHtml(loginUrl()) + HTML_STYLE_ATTR + PRIMARY_BUTTON_STYLE + "\">"
            + text(l, KEY_SIGNIN_BUTTON) + "</a>"
            + CLOSE_DIV;
    }

    /** Red "did you expect this?" box. Both arguments are already-resolved HTML. */
    private static String alertBox(String titleHtml, String bodyHtml) {
        return ALERT_BOX_OPEN
            + ALERT_TITLE_OPEN + "&#128680; " + titleHtml + CLOSE_PARAGRAPH
            + ALERT_TEXT_OPEN + bodyHtml + CLOSE_PARAGRAPH
            + CLOSE_DIV;
    }

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
    private String htmlEmailFooter(Locale l) {
        // The year goes in as text: MessageFormat would render 2026 as "2,026".
        return "<div style=\"background:#f1f5f9;padding:20px 40px;text-align:center;"
             + "border-top:1px solid #e2e8f0;\">"
             + "<p style=\"margin:0;font-size:12px;color:#94a3b8;\">"
             + text(l, "email.common.footer.copyright", String.valueOf(Year.now(ZoneOffset.UTC).getValue()))
             + " &nbsp;|&nbsp; " + text(l, "email.common.footer.automated")
             + HTML_P_DIV_CLOSE;
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
                                      String hospitalName,
                                      String activationUrl) {
        if (to == null) throw new IllegalArgumentException("Recipient address must not be null");
        validateAddresses(List.of(to));
        Locale l = DEFAULT_RECIPIENT_LOCALE;
        log.info("📧 Sending admin welcome email to: {}", to);

        String escapedRole     = hasText(roleName)     ? escapeHtml(roleName)     : text(l, "email.admin.welcome.fallback.role");
        String escapedHospital = hasText(hospitalName) ? escapeHtml(hospitalName) : null;
        String escapedUsername = hasText(username)     ? escapeHtml(username)     : "—";
        String escapedPassword = hasText(tempPassword) ? escapeHtml(tempPassword) : "—";
        String loginUrl        = loginUrl();

        String greeting = hasText(displayName)
            ? text(l, KEY_GREETING_HI, HTML_STRONG_OPEN + escapeHtml(displayName) + HTML_STRONG_CLOSE)
            : text(l, KEY_GREETING_ANONYMOUS);

        String hospitalLine = escapedHospital != null
            ? "<tr><td style='padding:6px 0;color:#64748b;font-weight:600;'>" + text(l, "email.admin.welcome.credentials.hospital") + "</td>"
              + "<td style='padding:6px 0 6px 16px;'>" + escapedHospital + "</td></tr>"
            : "";
        String created = escapedHospital != null
            ? text(l, "email.admin.welcome.body.created.hospital", escapedRole, escapedHospital)
            : text(l, "email.admin.welcome.body.created", escapedRole);

        String header = HTML_DIV_BACKGROUND + GRADIENT_NAVY + ";"
            + "padding:32px 40px;text-align:center;\">"
            + "<div style=\"font-size:36px;margin-bottom:8px;\">&#127973;</div>"
            + "<h1 style=\"color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:-0.5px;\">"
            + text(l, "email.admin.welcome.heading") + "</h1>"
            + "<p style=\"color:#bfdbfe;margin:6px 0 0;font-size:14px;\">" + text(l, "email.admin.welcome.tagline") + CLOSE_PARAGRAPH
            + CLOSE_DIV;

        // The step names the assignment mail by its subject, resolved through
        // the same key so the two mails agree in every language.
        String bodyContent = "<div style=\"padding:32px 40px;\">"
            + "<p style=\"color:#1e293b;font-size:16px;margin-top:0;\">" + greeting + CLOSE_PARAGRAPH
            + "<p style=\"color:#475569;line-height:1.6;\">"
            + created + ' '
            + text(l, "email.admin.welcome.body.step", text(l, KEY_ROLE_ASSIGNMENT_SUBJECT)) + ' '
            + text(l, "email.admin.welcome.body.keep")
            + CLOSE_PARAGRAPH
            + "<div style=\"background:#eff6ff;border:2px solid #bfdbfe;border-radius:10px;"
            + "padding:20px 24px;margin:24px 0;\">"
            + "<p style=\"margin:0 0 12px;font-weight:700;color:#1e3a8a;font-size:14px;"
            + "text-transform:uppercase;letter-spacing:0.5px;\">" + text(l, "email.admin.welcome.credentials.title") + CLOSE_PARAGRAPH
            + "<table style=\"border-collapse:collapse;width:100%;\">"
            + "<tr><td style=\"padding:6px 0;color:#64748b;font-weight:600;\">" + text(l, "email.admin.welcome.credentials.username") + "</td>"
            + "<td style=\"padding:6px 0 6px 16px;font-family:monospace;font-size:16px;color:#1e293b;\">"
            + escapedUsername + "</td></tr>"
            + "<tr><td style=\"padding:6px 0;color:#64748b;font-weight:600;\">" + text(l, "email.admin.welcome.credentials.password") + "</td>"
            + "<td style=\"padding:6px 0 6px 16px;font-family:monospace;font-size:16px;color:#1e293b;\">"
            + escapedPassword + "</td></tr>"
            + "<tr><td style=\"padding:6px 0;color:#64748b;font-weight:600;\">" + text(l, "email.admin.welcome.credentials.role") + "</td>"
            + "<td style=\"padding:6px 0 6px 16px;\">" + escapedRole + "</td></tr>"
            + hospitalLine
            + "</table></div>"
            + ctaBlock(l, activationUrl, loginUrl)
            + "<div style=\"background:#fef3c7;border-left:4px solid #f59e0b;padding:14px 16px;"
            + "border-radius:0 8px 8px 0;margin-top:24px;\">"
            + "<p style=\"margin:0;font-size:14px;color:#92400e;\">"
            + "<strong>&#9888; " + text(l, "email.admin.welcome.security.title") + HTML_STRONG_CLOSE_SP
            + text(l, "email.admin.welcome.security.body")
            + HTML_P_DIV_CLOSE
            + CLOSE_DIV;

        String body = htmlEmailWrapper(header + bodyContent + htmlEmailFooter(l));

        sendHtml(List.of(to), List.of(), List.of(), text(l, "email.admin.welcome.subject"), body);
        log.info("✅ Admin welcome email sent to {}", to);
    }


    /**
     * Activation-first call to action.
     *
     * <p>With an activation URL the button goes to the confirmation screen.
     * Without one there is NO button: the only address we could offer is
     * {@code /login}, and the account is inactive, so a prominent button
     * there leads straight to the rejection this mail exists to prevent —
     * which is what it used to do. The step is named in prose instead, with
     * the login address kept as secondary text for after activation.
     *
     * <p>That prose names the <em>code</em>, not a link: one of the two ways
     * the URL is null is a blank {@code app.portal.profile-completion-url-template},
     * and in that configuration the assignment mail renders no link section
     * either — so a link is the one thing the reader might not have. The code
     * is always in the assignment mail.
     */
    private String ctaBlock(Locale l, String rawActivationUrl, String rawLoginUrl) {
        String loginUrl = escapeHtml(rawLoginUrl);
        String loginAnchor = HTML_A_OPEN + loginUrl + "\" " + LINK_STYLE + ">" + loginUrl + "</a>";
        boolean hasActivation = hasText(rawActivationUrl);

        if (!hasActivation) {
            return "<p style=\"text-align:center;font-size:14px;color:#475569;margin:28px 0 8px;\">"
                + text(l, "email.admin.welcome.cta.code")
                + CLOSE_PARAGRAPH
                + "<p style=\"text-align:center;font-size:13px;color:#94a3b8;margin-top:0;\">"
                + text(l, "email.admin.welcome.cta.signin.after", loginAnchor) + CLOSE_PARAGRAPH;
        }

        String activationUrl = escapeHtml(rawActivationUrl);
        return "<p style=\"text-align:center;margin:28px 0;\">"
            + HTML_A_OPEN + activationUrl + "\" style=\"background:#2563eb;color:#ffffff;"
            + "text-decoration:none;padding:14px 32px;border-radius:8px;font-size:15px;"
            + "font-weight:600;display:inline-block;\">" + text(l, KEY_ACTIVATION_BUTTON) + "</a></p>"
            + "<p style=\"text-align:center;font-size:13px;color:#94a3b8;\">" + text(l, "email.admin.welcome.cta.copy") + HTML_BR
            + HTML_A_OPEN + activationUrl + "\" " + LINK_STYLE + ">" + activationUrl
            + "</a><br/><br/>" + text(l, "email.admin.welcome.cta.signin.activated", loginAnchor) + CLOSE_PARAGRAPH;
    }

}
