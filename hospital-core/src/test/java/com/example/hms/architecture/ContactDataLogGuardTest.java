package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Application logs go to third-party stores (Grafana Cloud, Splunk), so no log call may print an
 * e-mail address or a phone number. Log the user's id instead, or pass the value through a
 * masking helper ({@code ActivationDeliveryTracker.maskEmail} / {@code maskPhone}, or any
 * {@code mask…} method), which keeps the domain or the last digits for diagnosis.
 *
 * <p>Every log call of the application is read. An argument (the message expression included, so
 * a concatenation is caught too) offends when it reads an e-mail or phone getter
 * ({@code user.getEmail()}, {@code getPhoneNumber()}, {@code request.customerPhone()}) or a variable
 * named for one ({@code email}, {@code emails}, {@code phone}, {@code e164}, {@code senderPhone},
 * …), outside a {@code mask…(…)} call. A count or a presence check ({@code emails.size()},
 * {@code phone != null}) is fine.
 *
 * <p>{@link #ALLOWED} holds the reviewed exceptions, each with its reason.
 */
@DisplayName("No log statement prints an e-mail address or a phone number")
class ContactDataLogGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern LOG_CALL =
        Pattern.compile("\\b(?:log|LOG|logger|LOGGER)\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\(");

    /** A masking call, whose argument is therefore safe: {@code maskEmail(}, {@code SmsX.maskPhone(}, {@code mask(}. */
    private static final Pattern MASK_CALL = Pattern.compile("\\b\\w*[mM]ask\\w*\\s*\\(");

    /** A getter or record accessor that returns an e-mail or phone value. */
    private static final Pattern CONTACT_ACCESSOR = Pattern.compile(
        "\\.\\s*(?:get\\w*(?:Email|Emails|Phone|PhoneNumber|Phones|Msisdn|Mobile)"
            + "|email|emails|phone|phoneNumber|msisdn|mobile|\\w+Email|\\w+Phone|\\w+PhoneNumber)\\s*\\(\\s*\\)");

    /** A variable named for an e-mail or phone value (not a method name, not a field read off it). */
    private static final Pattern CONTACT_VARIABLE = Pattern.compile(
        "(?<![.\\w])(?:email|emails|phone|phones|phoneNumber|e164|msisdn|mobile"
            + "|[a-z]\\w*(?:Email|Emails|Phone|Phones|PhoneNumber|Msisdn))\\b"
            + "(?!\\s*\\()(?!\\s*\\.\\s*(?:size|length|isEmpty|isBlank)\\s*\\()"
            + "(?!\\s*(?:!=|==)\\s*null)");

    /**
     * Reviewed exceptions, "path:argument" → why it is acceptable. Keep this short: an entry is a
     * decision that the value is not a person's contact detail.
     */
    private static final Map<String, String> ALLOWED = Map.of();

    /** The log calls of {@code source} that print a contact value, as "line: argument". */
    static List<String> offenders(String source) {
        String code = SourceScan.stripComments(source);
        List<String> found = new ArrayList<>();
        Matcher call = LOG_CALL.matcher(code);
        while (call.find()) {
            int open = call.end() - 1;
            int close = SourceScan.closingParen(code, open);
            for (String argument : arguments(code, open + 1, close)) {
                String judged = withoutMaskedCalls(withoutLiterals(argument));
                if (CONTACT_ACCESSOR.matcher(judged).find() || CONTACT_VARIABLE.matcher(judged).find()) {
                    found.add(SourceScan.lineOf(code, call.start()) + ": " + argument.replaceAll("\\s+", " "));
                }
            }
        }
        return found;
    }

    /** The expression with every string and char literal emptied, so only code is judged. */
    private static String withoutLiterals(String expression) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < expression.length(); i++) {
            int skipped = SourceScan.endOfLiteralOrComment(expression, i);
            if (skipped >= 0) {
                out.append("\"\"");
                i = skipped;
                continue;
            }
            out.append(expression.charAt(i));
        }
        return out.toString();
    }

    /** The expression with each {@code mask…(…)} call (name and arguments) removed. */
    private static String withoutMaskedCalls(String expression) {
        String current = expression;
        Matcher mask = MASK_CALL.matcher(current);
        while (mask.find()) {
            int open = mask.end() - 1;
            int close = SourceScan.closingParen(current, open);
            current = current.substring(0, mask.start()) + "MASKED" + current.substring(close + 1);
            mask = MASK_CALL.matcher(current);
        }
        // a method reference into a masking helper: stream().map(ActivationDeliveryTracker::maskEmail)
        return current.replaceAll("::\\s*\\w*[mM]ask\\w*", "::MASKED");
    }

    /** The top-level arguments between {@code from} and {@code to}, trimmed. */
    private static List<String> arguments(String code, int from, int to) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            int skipped = SourceScan.endOfLiteralOrComment(code, i);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            char ch = code.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                out.add(code.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(code.substring(start, to).trim());
        return out;
    }

    @Test
    @DisplayName("no application log call prints an e-mail address or a phone number")
    void noLogCallPrintsContactData() throws IOException {
        List<String> offending = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String hit : offenders(source)) {
                    String argument = hit.substring(hit.indexOf(": ") + 2);
                    if (!ALLOWED.containsKey(relative + ":" + argument)) {
                        offending.add(relative + ":" + hit);
                    }
                }
            }
        }
        assertThat(offending)
            .as("log the user's id, or pass the value through maskEmail / maskPhone; never the raw "
                + "address or number" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("every allowlisted exception still exists, so the list cannot rot")
    void everyAllowedExceptionStillExists() throws IOException {
        for (String key : ALLOWED.keySet()) {
            String path = key.substring(0, key.indexOf(':'));
            String argument = key.substring(key.indexOf(':') + 1);
            String source = Files.readString(MAIN.resolve(path), StandardCharsets.UTF_8);
            assertThat(offenders(source))
                .as("allowlisted log argument %s in %s is gone: remove the entry", argument, path)
                .anyMatch(hit -> hit.endsWith(": " + argument));
        }
    }

    @Test
    @DisplayName("the scan sees the shapes it forbids, and lets masked values, ids and counts through")
    void theScanSeesTheShapes() {
        assertThat(offenders("""
            log.warn("Failed to send reminder to {}: {}", user.getEmail(), ex.getMessage());
            log.info("{} day(s) remaining -> {}", days, emails);
            log.debug("Dispatched to pharmacy ({})", phone);
            LOGGER.info("charge: phone={}", request.customerPhone());
            log.info("sent to " + user.getPhoneNumber());
            log.warn("assignment for user '{}'",
                newUser.getEmail());
            log.info("contact {}", recipientEmail);
            log.info("number {}", e164);
            """)).hasSize(8);
        assertThat(offenders("""
            log.warn("Failed to send reminder to user {}: {}", user.getId(), ex.getMessage());
            log.info("queued for {}", ActivationDeliveryTracker.maskEmail(user.getEmail()));
            log.info("queued for {}", to.stream().map(ActivationDeliveryTracker::maskEmail).toList());
            log.info("sms to {}", mask(e164));
            log.info("sms to {}", SmsPartnerNotificationChannel.maskPhone(senderPhone));
            log.info("{} addresses", emails.size());
            log.info("phone given: {}", phone != null);
            log.info("verified: {}", user.isEmailVerified());
            log.info("sent by {}", emailService.getClass().getSimpleName());
            // log.info("x {}", user.getEmail());
            log.info("the user's email changed");
            """)).isEmpty();
    }
}
