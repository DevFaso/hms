package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1840 (class: a scope code rendered into a message a person reads). A super-admin's
 * platform-wide assignment has no hospital, and the assignment mail and SMS named the place
 * with {@code resolveHospitalName}, whose answer for "no hospital" is the scope code
 * {@code GLOBAL}: « Le rôle Super administrateur vous a été attribué à GLOBAL ».
 *
 * <p>No method in main code that composes or sends an e-mail or an SMS may reach a scope code:
 * the {@code "GLOBAL"} literal, a {@code GLOBAL_SCOPE} constant, or {@code resolveHospitalName}
 * (the DTO label, which keeps the code for the screens that compare it). A notification names
 * the hospital with {@code messageHospitalName}, whose "no hospital" is null, and the template
 * words it ("across the whole platform").
 */
class NotificationScopeCodeGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** A call that composes or sends a mail or an SMS. */
    private static final List<String> NOTIFICATION_CALLS =
        List.of("emailService.send", "smsService.send(", "BySms(", "smsText(", "sendSmsSilently(");

    /** What a scope code looks like in source. */
    private static final List<String> SCOPE_CODES = List.of("\"GLOBAL\"", "GLOBAL_SCOPE", "resolveHospitalName(");

    private static final Pattern METHOD = Pattern.compile(
        "(?m)^\\s*+(?:(?:public|protected|private|static|final|synchronized)\\s++)*+[\\w<>\\[\\]?,. ]+?\\s++(\\w++)\\s*+\\(");

    private static final Set<String> NOT_METHODS = Set.of("if", "for", "while", "switch", "catch", "return", "new",
        "else", "try", "synchronized", "throw");

    @Test
    void noNotificationReachesAScopeCode() throws IOException {
        List<String> offenders = new ArrayList<>();
        int notificationMethods = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher matcher = METHOD.matcher(code);
                while (matcher.find()) {
                    if (NOT_METHODS.contains(matcher.group(1))) {
                        continue;
                    }
                    int open = code.indexOf('(', matcher.end() - 1);
                    int close = SourceScan.closingParen(code, open);
                    int brace = SourceScan.indexOfOutsideLiterals(code, '{', close);
                    int semicolon = SourceScan.indexOfOutsideLiterals(code, ';', close);
                    if (brace < 0 || (semicolon >= 0 && semicolon < brace)) {
                        continue;
                    }
                    String body = code.substring(brace, SourceScan.closingBrace(code, brace) + 1);
                    if (NOTIFICATION_CALLS.stream().noneMatch(body::contains)) {
                        continue;
                    }
                    notificationMethods++;
                    for (String scopeCode : SCOPE_CODES) {
                        if (body.contains(scopeCode)) {
                            offenders.add(MAIN.relativize(file) + ":" + SourceScan.lineOf(code, brace) + " "
                                + matcher.group(1) + " uses " + scopeCode);
                        }
                    }
                }
            }
        }
        assertThat(notificationMethods).as("the notification methods were found").isGreaterThan(10);
        assertThat(offenders).as("a mail or an SMS that can name a scope code instead of a place (QA D1840)"
            + SourceScan.MANDATE).isEmpty();
    }
}
