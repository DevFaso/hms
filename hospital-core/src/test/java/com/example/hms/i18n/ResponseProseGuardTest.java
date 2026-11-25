package com.example.hms.i18n;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hard-coded English in a response BODY (the sibling of
 * {@link ExceptionProseRatchetTest}, which covers exceptions).
 *
 * <p>Round after round QA reads an English sentence on a French screen
 * (D940, D977, D1000, D934 — 25 reports over nine rounds). The exception
 * ratchet sees {@code throw new BusinessException("…")}; it cannot see a
 * sentence the controller writes straight into the body:
 * {@code new MessageResponse("Logged out successfully.")},
 * {@code ResponseEntity.badRequest().body("…")}, {@code Map.of("message", "…")}
 * — as a literal, or through a {@code static final String} constant of the
 * same file. The global handler never touches those, so they are read in
 * English by every French and Spanish user.
 *
 * <p>Unlike the per-file count of the exception ratchet, this list is per
 * SITE: each entry is {@code file::sentence} with the number of places the
 * sentence is written, so a file may not swap one English sentence for
 * another, nor add a second copy of an allowed one. A sentence not listed, or
 * listed fewer times than it occurs, fails the build — resolve a
 * {@code messages*.properties} key with {@code MessageUtil} instead. When a
 * site is converted, lower its count (delete the line at zero).
 */
@DisplayName("Client-facing response prose is keyed, not written in English")
class ResponseProseGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** Response DTOs whose first constructor argument is the message shown to the user. */
    static final Pattern MESSAGE_DTO = Pattern.compile(
        "new\\s+(MessageResponse|EmailVerificationResponseDTO)\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    /**
     * A literal written straight into the body — {@code .body("…")}, {@code ResponseEntity.ok("…")}
     * — alone or as the head of a concatenation ({@code .body("Invalid slot: " + slot)}).
     */
    static final Pattern BODY_LITERAL = Pattern.compile(
        "(?:\\.body|ResponseEntity\\.ok)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*(?:[+)]|\\.formatted\\()");
    /** The same, parameterised: {@code .body(String.format("…", x))}, {@code new MessageResponse(String.format("…", x))}. */
    static final Pattern FORMAT_LITERAL = Pattern.compile(
        "(?:\\.body|ResponseEntity\\.ok|new\\s+MessageResponse|new\\s+EmailVerificationResponseDTO)\\(\\s*String\\.format\\(\\s*"
            + "\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** The opening of an ad hoc body map; its message-bearing entries are read from the whole argument list. */
    static final Pattern MAP_OF = Pattern.compile("\\bMap\\.of\\(");
    static final Pattern MAP_MESSAGE_ENTRY = Pattern.compile(
        "\"(?:message|error|detail|reason)\"\\s*,\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([A-Z][A-Z0-9_]*))\\s*(?:[,)+]|$)");

    /** The same shapes built from a constant declared in the file (the shape {@link ExceptionProseRatchetTest} reads). */
    static final Pattern MESSAGE_DTO_CONSTANT = Pattern.compile(
        "new\\s+(?:MessageResponse|EmailVerificationResponseDTO)\\s*\\(\\s*([A-Z][A-Z0-9_]*)\\s*[,)]");
    static final Pattern BODY_CONSTANT = Pattern.compile("(?:\\.body|ResponseEntity\\.ok)\\(\\s*([A-Z][A-Z0-9_]*)\\s*\\)");
    static final Pattern STRING_CONSTANT = ExceptionProseRatchetTest.STRING_CONSTANT;

    static boolean isProse(String literal) {
        if (literal.indexOf(' ') < 0) {
            return false;
        }
        int words = 0;
        for (String token : literal.split("\\s+")) {
            if (token.matches("[A-Za-zÀ-ÿ]{2,}[.,;:!?]*")) {
                words++;
            }
        }
        return words >= 2;
    }

    /** All former response literals are translated; new prose has no allowance. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow();

    /** {@code file::sentence} → how many places write it (comments stripped). */
    static Map<String, Integer> sites(String file, String rawSource) {
        String source = SourceScan.stripComments(rawSource);
        Map<String, Integer> out = new TreeMap<>();
        Map<String, String> constants = new TreeMap<>();
        Matcher c = STRING_CONSTANT.matcher(source);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        for (Pattern pattern : List.of(MESSAGE_DTO, BODY_LITERAL, FORMAT_LITERAL)) {
            Matcher m = pattern.matcher(source);
            while (m.find()) {
                String literal = m.group(m.groupCount());
                if (isProse(literal)) {
                    out.merge(file + "::" + literal, 1, Integer::sum);
                }
            }
        }
        for (Pattern pattern : List.of(MESSAGE_DTO_CONSTANT, BODY_CONSTANT)) {
            Matcher m = pattern.matcher(source);
            while (m.find()) {
                String value = constants.get(m.group(1));
                if (value != null && isProse(value)) {
                    out.merge(file + "::" + value, 1, Integer::sum);
                }
            }
        }
        // Every message-bearing entry of a Map.of body, wherever it sits in the argument
        // list (an earlier entry's `now()` must not hide it), as a literal or a constant.
        Matcher map = MAP_OF.matcher(source);
        while (map.find()) {
            int open = map.end() - 1;
            String arguments = source.substring(open + 1, SourceScan.closingParen(source, open));
            Matcher entry = MAP_MESSAGE_ENTRY.matcher(arguments);
            while (entry.find()) {
                String value = entry.group(1) != null ? entry.group(1) : constants.get(entry.group(2));
                if (value != null && isProse(value)) {
                    out.merge(file + "::" + value, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    @Test
    void authenticationControllersHaveNoLiteralResponseProse() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("controller"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.contains("Auth") || name.contains("Mfa") || name.contains("PasswordReset") || name.contains("OwnEmailChange")) {
                    found.putAll(sites(name, Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
        }
        assertThat(found).as("D2008: every authentication response must resolve its request locale").isEmpty();
    }

    @Test
    @DisplayName("no controller writes an English sentence into a response body beyond the listed sites")
    void responseBodiesAreKeyed() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                actual.putAll(sites(rel, Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("resolve a messages*.properties key (MessageUtil.resolve) instead of an English sentence in the body"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED))
            .as("a site was keyed: lower its count so the gain cannot be spent again" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches every body shape QA has reported, per site, and not a key, a code or a comment")
    void theScanCatchesTheShapes() {
        String source = """
            // was: new MessageResponse("Signed out, see you soon.") — a comment is not a site
            return ResponseEntity.ok(new MessageResponse("Logged out successfully."));
            return ResponseEntity.ok(new MessageResponse("Logged out successfully."));
            return ResponseEntity.badRequest().body("Pre-check-in is available 1–7 days before your appointment.");
            return ResponseEntity.status(503).body(Map.of("error", "Mail service unavailable"));
            return ResponseEntity.ok(Map.of("status", "ok", "message", "Verification code sent"));
            return ResponseEntity.ok(new MessageResponse(MessageUtil.resolve(locale, "auth.logout.ok")));
            return ResponseEntity.ok(new MessageResponse("auth.logout.ok"));
            return ResponseEntity.ok().body("OK");
            private static final String REFRESH_INVALID_MESSAGE = "Refresh token is invalid or has expired.";
            private static final String KEY = "auth.refresh.invalid";
            return ResponseEntity.status(401).body(new MessageResponse(REFRESH_INVALID_MESSAGE));
            return ResponseEntity.status(401).body(new MessageResponse(KEY));
            return ResponseEntity.ok(Map.of(STATUS, "FAILED", "sentAt", OffsetDateTime.now(), "error", "Failed to send email"));
            return ResponseEntity.ok(Map.of("count", items.size(), "message", REFRESH_INVALID_MESSAGE));
            return ResponseEntity.ok("Password changed successfully");
            return ResponseEntity.badRequest().body("Invalid appointment slot: " + slot);
            return ResponseEntity.badRequest().body("Invalid appointment slot: %s".formatted(slot));
            return ResponseEntity.ok(new MessageResponse(String.format("Slot %s is taken", slot)));
            """;
        assertThat(sites("x", source)).containsOnly(
            Map.entry("x::Logged out successfully.", 2),
            Map.entry("x::Invalid appointment slot: %s", 1),
            Map.entry("x::Slot %s is taken", 1),
            Map.entry("x::Mail service unavailable", 1),
            Map.entry("x::Failed to send email", 1),
            Map.entry("x::Invalid appointment slot: ", 1),
            Map.entry("x::Password changed successfully", 1),
            Map.entry("x::Pre-check-in is available 1–7 days before your appointment.", 1),
            Map.entry("x::Refresh token is invalid or has expired.", 2),
            Map.entry("x::Verification code sent", 1));
    }
}
