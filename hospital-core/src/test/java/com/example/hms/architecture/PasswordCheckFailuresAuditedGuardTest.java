package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2020 (round 57): a refused check of a user's own password leaves an audit row.
 *
 * <p>D2001 made every check of a signed-in user's own password count in the account's sign-in bucket
 * ({@code LoginAttemptService#checkPassword}), so a wrong current password on a password change could lock the
 * account. The lock screen audited its failures and the lock; the change of password and the forced setup wrote
 * nothing, so an admin read four sign-in failures and a lock, which the five-failure rule cannot explain.
 *
 * <p>Rule, over every class of {@code src/main/java}: a method that calls {@code checkPassword(} also writes the
 * outcome to the audit trail after the call — through {@code auditOwnPasswordCheck(} (AuthController's change and
 * setup), {@code auditLoginFailure(} / {@code auditEventLogService.logEvent(} (the lock screen), or the class's own
 * refusal helper that audits ({@code refused(} / {@code refusedOn(} in the e-mail change and the controlled-drug
 * re-authentication). Allowlist: none.
 */
@DisplayName("QA D2020: every refused own-password check is audited")
class PasswordCheckFailuresAuditedGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern CALL = Pattern.compile("\\.checkPassword\\s*\\(");
    private static final Pattern AUDITS = Pattern.compile(
        "\\bauditOwnPasswordCheck\\s*\\(|\\bauditLoginFailure\\s*\\(|\\bauditEventLogService\\s*\\.\\s*logEvent\\s*\\("
            + "|\\brefused\\s*\\(|\\brefusedOn\\s*\\(");
    private static final Pattern METHOD = Pattern.compile(
        "(?m)^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:public|protected|private)\\s[^;=(]*?\\b(\\w+)\\s*\\([^;{]*?\\)"
            + "\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");

    @Test
    @DisplayName("each method that checks a user's own password audits a refusal")
    void everyCheckIsAudited() throws IOException {
        List<String> silent = new ArrayList<>();
        int checks = 0;
        try (Stream<Path> tree = Files.walk(MAIN)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if ("LoginAttemptService".equals(name)) {
                    continue; // the bucket itself: it defines checkPassword
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String method : silentChecks(source)) {
                    silent.add(name + "#" + method);
                }
                checks += (int) CALL.matcher(source).results().count();
            }
        }
        // Not vacuous: the lock screen, the change, the setup, the e-mail change, the controlled-drug signature.
        assertThat(checks).as("checkPassword calls found in main").isGreaterThanOrEqualTo(5);
        assertThat(silent).as("audit the refusal after checkPassword (auditOwnPasswordCheck in AuthController)")
            .isEmpty();
    }

    @Test
    @DisplayName("QA D2020 guard: the scan sees the silent change of password, not the audited one")
    void theScanSeesTheShape() {
        String silent = """
            class C {
                public ResponseEntity<Object> changeOwnPassword(ChangePasswordRequest request) {
                    check = loginAttemptService.checkPassword(user.getId(), () -> ok());
                    if (check == REJECTED) { return unauthorized(); }
                    return ok();
                }
            }
            """;
        assertThat(silentChecks(silent)).containsExactly("changeOwnPassword");
        String audited = silent.replace("if (check == REJECTED)",
            "auditOwnPasswordCheck(check, user.getId(), \"Password change\");\n if (check == REJECTED)");
        assertThat(silentChecks(audited)).isEmpty();
    }

    /** The methods of a source that call {@code checkPassword(} with no audit after the call. */
    static List<String> silentChecks(String source) {
        List<String> silent = new ArrayList<>();
        Matcher method = METHOD.matcher(source);
        while (method.find()) {
            int open = method.end() - 1;
            int close = closingBrace(source, open);
            String body = source.substring(open, close + 1);
            Matcher call = CALL.matcher(body);
            if (call.find() && !AUDITS.matcher(body.substring(call.end())).find()) {
                silent.add(method.group(1));
            }
        }
        return silent;
    }

    private static int closingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return source.length() - 1;
    }
}
