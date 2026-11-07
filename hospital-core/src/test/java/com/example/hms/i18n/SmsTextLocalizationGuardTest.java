package com.example.hms.i18n;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1123 guard (round 20): an SMS written as an English literal.
 *
 * <p>The phone-only patient's activation SMS was {@code String.format("👋
 * Confirm your '%s' role at '%s'. Use code: %s (reference: %s)")}, the
 * registrar's and the hospital's copies were English too, a fourth SMS carried
 * the temporary password, and the username reminder was "Hello! Your username
 * is: %s" in every language. None of these is an exception, a response body or
 * a stored row, so the other prose guards never read them.
 *
 * <p>Rule: in every main class that holds an {@code SmsService} (or IS one), no
 * {@code String.format} on a literal of English prose (two words or more), and
 * no literal that labels a password ("Temp password: %s"): an SMS body comes from the reader's
 * bundle ({@code sms.*} keys), and a secret other than a one-time code never
 * goes into one. Audit descriptions and log lines are English by house rule
 * and are not read. Allowlist: none.
 */
class SmsTextLocalizationGuardTest {

    private static final Path MAIN = Paths.get("src/main/java");
    private static final Pattern HOLDS_SMS = Pattern.compile("\\bSmsService\\b");
    /** A {@code String.format} whose format is a string literal; the literal is group 1 (possessive: linear). */
    private static final Pattern FORMAT_LITERAL =
        Pattern.compile("String\\.format\\(\\s*+\"([^\"]*+)\"");
    /** Prose: two words of two letters or more, side by side ("Your username", "Temp password"). */
    private static final Pattern TWO_WORDS = Pattern.compile("[A-Za-z]{2} [A-Za-z]{2}");
    /** Read against ONE string literal's content at a time (see {@link #passwordLabels}). */
    private static final Pattern PASSWORD_LABEL =
        Pattern.compile("(?i:password|mot de passe|contrase\\S*)\\s*[:=]");
    /** Statements whose English is by design: the audit trail and the logs. */
    private static final Pattern EXEMPT_STATEMENT =
        Pattern.compile("eventDescription\\(|\\blog\\.(?:info|warn|debug|error|trace)\\(|Description\\(");

    @Test
    @DisplayName("QA D1123: no SMS body is an English literal and none names a password")
    void noSmsIsAnEnglishLiteral() throws IOException {
        List<String> offenders = new ArrayList<>();
        int classesRead = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!HOLDS_SMS.matcher(source).find()) {
                    continue;
                }
                classesRead++;
                int[] starts = SourceScan.statementStarts(source);
                collect(file, source, starts, "English String.format", offenders);
                passwordLabels(file, source, starts, offenders);
            }
        }
        assertThat(classesRead)
            .as("the scan must keep finding the SMS senders (assignment, reminders, pharmacy, critical values)")
            .isGreaterThanOrEqualTo(10);
        assertThat(offenders).as(SourceScan.MANDATE).isEmpty();
    }

    /** Every string literal that labels a password ("Temp password: %s"), outside audit and log statements. */
    private static void passwordLabels(Path file, String source, int[] starts, List<String> offenders) {
        for (int i = 0; i < source.length(); i++) {
            int end = SourceScan.endOfLiteralOrComment(source, i);
            if (end < 0) {
                continue;
            }
            if (source.charAt(i) == '"') {
                String literal = source.substring(i, Math.min(source.length(), end + 1));
                int start = SourceScan.statementStartBefore(starts, i);
                if (PASSWORD_LABEL.matcher(literal).find()
                    && !EXEMPT_STATEMENT.matcher(source.substring(start, i)).find()) {
                    offenders.add(MAIN.relativize(file) + ":" + SourceScan.lineOf(source, i)
                        + " a password label in a literal: " + literal);
                }
            }
            i = end;
        }
    }

    private static void collect(Path file, String source, int[] starts, String what, List<String> offenders) {
        Matcher m = FORMAT_LITERAL.matcher(source);
        while (m.find()) {
            if (!TWO_WORDS.matcher(m.group(1)).find()) {
                continue;
            }
            int start = SourceScan.statementStartBefore(starts, m.start());
            String statement = source.substring(start, Math.min(source.length(), m.end()));
            if (EXEMPT_STATEMENT.matcher(statement).find()) {
                continue;
            }
            offenders.add(MAIN.relativize(file) + ":" + SourceScan.lineOf(source, m.start()) + " " + what
                + ": " + m.group());
        }
    }
}
