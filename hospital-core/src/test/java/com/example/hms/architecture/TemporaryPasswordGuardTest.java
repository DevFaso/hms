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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A password a person did not choose for themselves is temporary: stored as a hash only, expiring, changed before
 * any other use, never e-mailed and never logged ({@code TemporaryPasswords}). Each rule reads the source, so a new
 * code path that sets a password, mails one or keeps one in clear fails here:
 * <ul>
 *   <li>an account password is set in the frozen places below only. {@code TemporaryPasswords#assign} is the one
 *       place for a password someone else chose; the others are the holder's own choice (self-registration, the
 *       first-user bootstrap, a change, the first sign-in setup, a reset from the e-mailed link) or the dev seed.
 *       A new setter is either a new own-password path (add it here, with the reason) or goes through
 *       {@code assign};</li>
 *   <li>a file that generates a temporary password also assigns it through {@code TemporaryPasswords#assign};</li>
 *   <li>no e-mail or SMS method takes a password;</li>
 *   <li>no entity keeps a password in clear;</li>
 *   <li>no log statement is handed a password variable.</li>
 * </ul>
 */
@DisplayName("Temporary passwords: hashed, expiring, changed first, never mailed or logged")
class TemporaryPasswordGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern SETS_PASSWORD_HASH = Pattern.compile("\\.(?:setPasswordHash|passwordHash)\\(");

    /**
     * Every place that sets a password hash, by file, with how many times. Only grows with a reason.
     */
    private static final Map<String, Integer> PASSWORD_SETTERS = Map.of(
        // The one place for a password someone else chose: hashed, forced change, expiry.
        "utility/TemporaryPasswords.java", 1,
        // The holder's own choice: bootstrapFirstUser, changeOwnPassword, completeAccountSetup.
        "service/UserServiceImpl.java", 3,
        // The holder's own choice, through the link e-mailed or texted to them.
        "service/PasswordResetServiceImpl.java", 1,
        // toEntity: public self-registration, the holder's own choice.
        "mapper/UserMapper.java", 1,
        // A PasswordHistory row (reuse check), not an account password.
        "service/PasswordHistoryService.java", 1,
        // Dev-only synthetic seed (never on prod, application-prod.yml).
        "bootstrap/DevSyntheticDataSeeder.java", 5);

    private static final Pattern GENERATES = Pattern.compile("TemporaryPasswords\\.generate\\(");
    private static final Pattern ASSIGNS = Pattern.compile("TemporaryPasswords\\.assign\\(");

    /** A {@code String} parameter: its name is group 1. */
    private static final Pattern STRING_PARAMETER = Pattern.compile("\\bString\\s+(\\w+)\\s*[,)]");

    /** A {@code String} field: its name is group 1. */
    private static final Pattern STRING_FIELD = Pattern.compile("\\bString\\s+(\\w+)\\s*[;=]");

    /** A column name: group 1. */
    private static final Pattern COLUMN_NAME = Pattern.compile("@Column\\s*\\(\\s*name\\s*=\\s*\"(\\w+)\"");

    /** A logging call on any logger receiver ({@code log}, {@code logger}, {@code LOG}, {@code auditLog}, ...). */
    private static final Pattern LOG_CALL = Pattern.compile("\\b(\\w+)\\.(?:trace|debug|info|warn|error)\\(");

    /** A getter that returns a password: {@code getTempPassword()}, {@code getTemporaryPassword()}. */
    private static final Pattern PASSWORD_GETTER = Pattern.compile("\\.get\\w*Password\\(\\)");

    /** Message services and gateways: a password must never be a parameter of one. */
    private static final java.util.List<String> MESSAGE_CLASS_WORDS = java.util.List.of(
        "Sms", "Email", "Notification", "Gateway");

    /**
     * Named like a message service, sends no password: the self-service e-mail change takes the holder's CURRENT
     * password to confirm it is them (the request body), and its mails carry a code, never the password.
     */
    private static final java.util.Set<String> NOT_MESSAGE_SENDERS = java.util.Set.of("OwnEmailChangeService.java");

    /** An identifier, not part of a qualified name. */
    private static final Pattern IDENTIFIER = Pattern.compile("(?<![\\w.])[A-Za-z_]\\w*");

    /** The variables a password is held in (not {@code passwordChanged}, {@code passwordHash}, ...). */
    private static final java.util.Set<String> PASSWORD_VARIABLES = java.util.Set.of(
        "password", "rawpassword", "temppassword", "temporarypassword", "generatedpassword", "plainpassword",
        "newpassword", "initialpassword", "currentpassword");

    @Test
    @DisplayName("an account password is set only through TemporaryPasswords.assign or a listed own-password path")
    void passwordsAreSetOnlyInTheListedPlaces() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        for (Path file : javaFiles()) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            int count = count(SETS_PASSWORD_HASH, code);
            if (count > 0) {
                found.put(MAIN.relativize(file).toString().replace('\\', '/'), count);
            }
        }
        assertThat(found)
            .as("a password set outside TemporaryPasswords.assign must be the holder's own choice, listed "
                + "here with its reason; one someone else chose goes through TemporaryPasswords.assign")
            .isEqualTo(new TreeMap<>(PASSWORD_SETTERS));
    }

    @Test
    @DisplayName("a temporary password that is generated is assigned through TemporaryPasswords.assign")
    void generatedPasswordsAreAssignedAsTemporary() throws IOException {
        List<String> offenders = new ArrayList<>();
        int generators = 0;
        for (Path file : javaFiles()) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            int generated = count(GENERATES, code);
            if (generated > 0 && !file.endsWith("TemporaryPasswords.java")) {
                generators++;
                int assigned = count(ASSIGNS, code);
                if (assigned < generated) {
                    offenders.add(file.getFileName() + ": " + generated + " generate, " + assigned + " assign");
                }
            }
        }
        assertThat(generators).as("the scan found the generators").isGreaterThanOrEqualTo(2);
        assertThat(offenders).as("generate without assign: a password with no expiry or forced change").isEmpty();
    }

    @Test
    @DisplayName("no e-mail, SMS, notification or gateway method takes a password")
    void noMessageCarriesAPassword() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path file : javaFiles()) {
            String name = file.getFileName().toString();
            if (MESSAGE_CLASS_WORDS.stream().noneMatch(name::contains) || name.endsWith("Controller.java")
                    || NOT_MESSAGE_SENDERS.contains(name)) {
                continue;
            }
            scanned++;
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = STRING_PARAMETER.matcher(code);
            while (m.find()) {
                if (m.group(1).toLowerCase(Locale.ROOT).contains("password")) {
                    offenders.add(name + ": " + m.group(1));
                }
            }
        }
        assertThat(scanned).as("the scan found the message services").isGreaterThan(10);
        assertThat(offenders)
            .as("a first password is shown once on the activation page; a lost one is replaced by a reset link")
            .isEmpty();
    }

    @Test
    @DisplayName("no entity keeps a password in clear")
    void noEntityKeepsAPlainPassword() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("model"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                List<String> names = new ArrayList<>();
                names.addAll(groups(STRING_FIELD, code));
                names.addAll(groups(COLUMN_NAME, code));
                for (String name : names) {
                    if (holdsAPasswordInClear(name)) {
                        offenders.add(file.getFileName() + ": " + name);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("no log statement is handed a password variable or getter")
    void noPasswordIsLogged() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher log = LOG_CALL.matcher(code);
            while (log.find()) {
                if (!log.group(1).toLowerCase(Locale.ROOT).contains("log")) {
                    continue;
                }
                int open = log.end() - 1;
                int close = SourceScan.closingParen(code, open);
                String args = withoutStringLiterals(code.substring(open + 1, Math.max(open + 1, close)));
                boolean handsAPassword = PASSWORD_GETTER.matcher(args).find()
                    || groups(IDENTIFIER, args).stream()
                        .anyMatch(id -> PASSWORD_VARIABLES.contains(id.toLowerCase(Locale.ROOT)));
                if (handsAPassword) {
                    offenders.add(file.getFileName() + ":" + SourceScan.lineOf(code, log.start()));
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static String withoutStringLiterals(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"') {
                int end = SourceScan.endOfStringLiteral(code, i);
                out.append("\"\"");
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** A field or column name that would hold a password in clear: {@code tempPlainPassword}, {@code temp_plain_password}. */
    private static boolean holdsAPasswordInClear(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace("_", "");
        return lower.endsWith("password")
            && (lower.contains("plain") || lower.contains("temp") || lower.contains("raw") || lower.contains("clear"));
    }

    private static List<String> groups(Pattern pattern, String code) {
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(code);
        while (m.find()) {
            out.add(m.groupCount() > 0 ? m.group(1) : m.group());
        }
        return out;
    }

    private static int count(Pattern pattern, String code) {
        Matcher m = pattern.matcher(code);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
