package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2001 (the class): the lock screen checked the password with
 * {@code authenticationManager.authenticate} and never counted a wrong one, so
 * an unattended locked workstation allowed unlimited guesses. Every check of a
 * user's own password must go through {@code LoginAttemptService#checkPassword},
 * which counts it in the account's sign-in bucket, refuses while the account is
 * locked, and clears it on success.
 *
 * <p>The guard reads every main source file for a password comparison
 * ({@code authenticationManager.authenticate(...)}, {@code new
 * UsernamePasswordAuthenticationToken} handed to {@code authenticate}, or
 * {@code passwordEncoder.matches(...)}). A comparison passes when it sits in
 * the lambda handed to {@code checkPassword(}, or in a private helper whose
 * every call sits there. Anything else must be on {@link #NOT_A_COUNTED_CHECK}
 * with the reason it is not a guess at an account's password, and the count of
 * such sites per method is exact: a new one fails, and so does a stale entry.
 */
@DisplayName("D2001: every check of a user's password counts in the sign-in bucket")
class PasswordChecksCountInLoginBucketGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** {@code File#method} → number of uncounted comparisons there, each with its reason. */
    private static final Map<String, Integer> NOT_A_COUNTED_CHECK = Map.ofEntries(
        // The sign-in itself: the bucket's owner. It checks remainingLockMs
        // before the comparison, records the failure in its BadCredentials and
        // Disabled arms and resets on success (LoginLockoutIntegrationTest).
        // It keys on the TYPED name (an unknown name is throttled too), so it
        // cannot use the account-id checkPassword.
        Map.entry("AuthController#authenticateUser", 1),
        // A dev-only seeder comparing its own constant with the stored hash to
        // decide whether to re-seed; no user input.
        Map.entry("DevSyntheticDataSeeder#ensureSuperAdminUser", 1),
        Map.entry("DevSyntheticDataSeeder#ensureFeaturedUser", 1),
        // MFA backup codes, not a password: MfaService locks the step-up after
        // 5 wrong codes itself (STEP_UP_KEY_PREFIX).
        Map.entry("MfaService#findMatchingBackupCode", 1),
        // The 6-digit code mailed for an e-mail change, not a password: dead
        // after MAX_CODE_ATTEMPTS wrong tries.
        Map.entry("OwnEmailChangeService#confirmChange", 1),
        // The 6-digit code sent to a recovery contact, not a password: has its
        // own attempt limit.
        Map.entry("UserCredentialLifecycleServiceImpl#verifyRecoveryContact", 1),
        // Compares a NEW password with the account's previous hashes (reuse
        // check) after the current one was already verified.
        Map.entry("PasswordHistoryService#isPasswordReused", 1),
        // An administrator setting a password: compares the new password with
        // the current hash only to skip a no-op write. Not a guess; the caller
        // is an administrator of the account.
        Map.entry("UserServiceImpl#updateUser", 1));

    private static final Pattern COMPARISON = Pattern.compile(
        "\\b\\w*[aA]uthenticationManager\\s*\\.\\s*authenticate\\s*\\("
            + "|\\.\\s*authenticate\\s*\\(\\s*new\\s+UsernamePasswordAuthenticationToken"
            + "|\\b\\w*[pP]asswordEncoder\\s*\\.\\s*matches\\s*\\("
            + "|\\bencoder\\s*\\.\\s*matches\\s*\\(");

    private static final Pattern METHOD_DECLARATION = Pattern.compile(
        "^\\s*((?:public|protected|private|static|final|synchronized|default|\\s)*)"
            + "[\\w<>\\[\\],.?\\s]+?\\s+(\\w+)\\s*\\([^;]*$");

    private static final Pattern NOT_A_DECLARATION = Pattern.compile(
        "^\\s*(if|for|while|switch|catch|return|new|else|try|throw|do|synchronized\\s*\\()\\b.*");

    @Test
    @DisplayName("every password comparison in main is counted, or listed with its reason")
    void everyComparisonIsCountedOrListed() throws IOException {
        Map<String, Integer> uncounted = new TreeMap<>();
        int counted = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                Result result = analyse(name, Files.readString(file, StandardCharsets.UTF_8));
                result.uncounted().forEach((key, n) -> uncounted.merge(key, n, Integer::sum));
                counted += result.counted();
            }
        }
        // Not vacuous: AuthController's helper (the lock screen, the change of
        // password, the forced setup), the controlled-drug signature and the
        // e-mail change.
        assertThat(counted).as("counted comparisons found in main").isGreaterThanOrEqualTo(3);
        assertThat(uncounted)
            .as("a password comparison outside LoginAttemptService#checkPassword: route it through "
                + "checkPassword, or list it in NOT_A_COUNTED_CHECK with why it is not a guess at a password")
            .isEqualTo(new TreeMap<>(NOT_A_COUNTED_CHECK));
    }

    @Test
    @DisplayName("the analyser flags an uncounted comparison and passes the counted shapes (falsification)")
    void analyserSeesTheShapes() {
        String uncountedPublic = """
            class A {
                public boolean unlock(String u, String p) {
                    authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(u, p));
                    return true;
                }
            }
            """;
        assertThat(analyse("A", uncountedPublic).uncounted()).containsEntry("A#unlock", 1);

        String inlineLambda = """
            class B {
                public void sign(UUID id, String p) {
                    var check = loginAttemptService.checkPassword(id,
                        () -> passwordEncoder.matches(p, hash));
                }
            }
            """;
        Result inline = analyse("B", inlineLambda);
        assertThat(inline.uncounted()).isEmpty();
        assertThat(inline.counted()).isEqualTo(1);

        String helperOnlyInLambda = """
            class C {
                public void verify(UUID id, String u, String p) {
                    check = loginAttemptService.checkPassword(id,
                            () -> accepted(u, p));
                }
                private boolean accepted(String u, String p) {
                    try {
                        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(u, p));
                        return true;
                    } catch (BadCredentialsException ex) {
                        return false;
                    }
                }
            }
            """;
        assertThat(analyse("C", helperOnlyInLambda).uncounted()).isEmpty();

        String helperAlsoCalledBare = helperOnlyInLambda.replace(
            "    private boolean accepted",
            "    public void bypass(String u, String p) {\n        accepted(u, p);\n    }\n    private boolean accepted");
        assertThat(analyse("C", helperAlsoCalledBare).uncounted()).containsEntry("C#accepted", 1);
    }

    private record Result(Map<String, Integer> uncounted, int counted) {
    }

    static Result analyse(String className, String rawSource) {
        String source = rawSource.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = source.split("\n", -1);
        int[] lineStarts = new int[lines.length];
        for (int i = 1; i < lines.length; i++) {
            lineStarts[i] = lineStarts[i - 1] + lines[i - 1].length() + 1;
        }
        List<int[]> spans = checkPasswordArguments(source);
        Map<String, Integer> uncounted = new TreeMap<>();
        int counted = 0;
        for (int i = 0; i < lines.length; i++) {
            if (isComment(lines[i])) {
                continue;
            }
            Matcher comparison = COMPARISON.matcher(lines[i]);
            if (!comparison.find()) {
                continue;
            }
            if (inside(spans, lineStarts[i] + comparison.start())) {
                counted++;
                continue;
            }
            int declaration = enclosingDeclaration(lines, i);
            String method = declaration < 0 ? "?" : methodName(lines[declaration]);
            if (declaration >= 0 && isPrivate(lines[declaration])
                && everyCallCounted(lines, lineStarts, spans, method, declaration)) {
                counted++;
                continue;
            }
            uncounted.merge(className + "#" + method, 1, Integer::sum);
        }
        return new Result(uncounted, counted);
    }

    /** The argument list of every {@code checkPassword(...)} call, as [open, close] offsets. */
    private static List<int[]> checkPasswordArguments(String source) {
        List<int[]> spans = new ArrayList<>();
        Matcher call = Pattern.compile("\\bcheckPassword\\s*\\(").matcher(source);
        while (call.find()) {
            int depth = 0;
            for (int k = call.end() - 1; k < source.length(); k++) {
                char c = source.charAt(k);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    spans.add(new int[] {call.end(), k});
                    break;
                }
            }
        }
        return spans;
    }

    private static boolean inside(List<int[]> spans, int offset) {
        return spans.stream().anyMatch(span -> offset >= span[0] && offset <= span[1]);
    }

    private static boolean isComment(String line) {
        String t = line.trim();
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");
    }

    private static int enclosingDeclaration(String[] lines, int index) {
        for (int j = index; j >= 0; j--) {
            if (isComment(lines[j]) || NOT_A_DECLARATION.matcher(lines[j]).matches()) {
                continue;
            }
            Matcher m = METHOD_DECLARATION.matcher(lines[j]);
            if (m.matches() && !lines[j].contains("=") && !lines[j].contains("->")
                && !lines[j].trim().startsWith(".") && !lines[j].contains("new ")) {
                return j;
            }
        }
        return -1;
    }

    private static String methodName(String declaration) {
        Matcher m = METHOD_DECLARATION.matcher(declaration);
        return m.matches() ? m.group(2) : "?";
    }

    private static boolean isPrivate(String declaration) {
        return declaration.trim().startsWith("private ") || declaration.contains(" private ");
    }

    /** At least one call of the helper, and every one inside a {@code checkPassword(...)} argument list. */
    private static boolean everyCallCounted(String[] lines, int[] lineStarts, List<int[]> spans,
                                            String method, int declaration) {
        Pattern call = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(");
        boolean any = false;
        for (int j = 0; j < lines.length; j++) {
            if (j == declaration || isComment(lines[j])) {
                continue;
            }
            Matcher m = call.matcher(lines[j]);
            while (m.find()) {
                if (!inside(spans, lineStarts[j] + m.start())) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }
}
