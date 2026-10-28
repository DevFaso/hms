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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * {@code ResponseEntity.badRequest().body("…")}, {@code Map.of("message", "…")}.
 * The global handler never touches those, so they are read in English by
 * every French and Spanish user.
 *
 * <p>Unlike the per-file count of the exception ratchet, this list is per
 * SITE: a file may not swap one English sentence for another. Each entry is
 * {@code file::literal}; a sentence not listed fails the build — resolve a
 * {@code messages*.properties} key with {@code MessageUtil} instead. When a
 * site is converted, delete its line.
 */
@DisplayName("Client-facing response prose is keyed, not written in English")
class ResponseProseGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** Response DTOs whose first constructor argument is the message shown to the user. */
    static final Pattern MESSAGE_DTO = Pattern.compile(
        "new\\s+(MessageResponse|EmailVerificationResponseDTO)\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** A literal written straight into the body. */
    static final Pattern BODY_LITERAL = Pattern.compile("\\.body\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** A message-bearing entry of an ad hoc body map. */
    static final Pattern MAP_MESSAGE = Pattern.compile(
        "Map\\.of\\((?:[^()]*?,\\s*)?\"(?:message|error|detail|reason)\"\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * The same shapes built from a constant ({@code new MessageResponse(REFRESH_INVALID_MESSAGE)}),
     * resolved against the {@code static final String} declared in the same file — the
     * way {@link ExceptionProseRatchetTest} does, so hoisting a sentence into a constant
     * is not a way around the guard.
     */
    static final Pattern MESSAGE_DTO_CONSTANT = Pattern.compile(
        "new\\s+(?:MessageResponse|EmailVerificationResponseDTO)\\s*\\(\\s*([A-Z][A-Z0-9_]*)\\s*[,)]");
    static final Pattern BODY_CONSTANT = Pattern.compile("\\.body\\(\\s*([A-Z][A-Z0-9_]*)\\s*\\)");
    static final Pattern MAP_MESSAGE_CONSTANT = Pattern.compile(
        "Map\\.of\\((?:[^()]*?,\\s*)?\"(?:message|error|detail|reason)\"\\s*,\\s*([A-Z][A-Z0-9_]*)\\s*[,)]");
    static final Pattern STRING_CONSTANT = Pattern.compile(
        "static\\s+final\\s+String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");

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

    private static final String PRE_EXISTING = "pre-existing English response (class of QA D940/D977); key it with the next touch";
    private static final String D921 = "QA D921 path: verifyEmail's success body";

    /** Sites as they stand, with why each is tolerated. Shrink only. */
    private static final Map<String, String> ALLOWED = new TreeMap<>(Map.ofEntries(
        Map.entry("controller/AuthController.java::Public self-registration is no longer available.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Email verified successfully.", D921),
        Map.entry("controller/AuthController.java::Email verification failed.", D921 + " (EMAIL_VERIFICATION_FAILED constant)"),
        Map.entry("controller/AuthController.java::Refresh token is invalid or has expired. Please log in again.",
            PRE_EXISTING + " (REFRESH_INVALID_MESSAGE constant, 4 sites)"),
        Map.entry("controller/AuthController.java::This session was just refreshed by another tab or request. Retry with the latest token.",
            PRE_EXISTING + " (REFRESH_SUPERSEDED_MESSAGE constant)"),
        Map.entry("controller/FileUploadController.java::User not found", PRE_EXISTING + " (USER_NOT_FOUND_MESSAGE constant, 5 sites)"),
        Map.entry("controller/AuthController.java::If the email is registered, a new verification link has been sent.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Account is already verified. You can log in.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::If the number is registered, a new activation code has been sent.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Logged out successfully.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Refresh token has already been used. Please log in again.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Refresh token is required.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::User account not found or inactive.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Active support-impersonation session — call /super-admin/impersonation/stop before refreshing.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Session timed out due to inactivity. Please log in again.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Cannot verify password for a different user.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Password verified.", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Missing or invalid Authorization header", PRE_EXISTING),
        Map.entry("controller/AuthController.java::Verification code sent", PRE_EXISTING),
        Map.entry("controller/BillingInvoiceController.java::Mail service unavailable", PRE_EXISTING),
        Map.entry("controller/FileUploadController.java::Failed to upload profile image", PRE_EXISTING),
        Map.entry("controller/FileUploadController.java::Failed to delete profile image", PRE_EXISTING),
        Map.entry("controller/FileUploadController.java::Failed to upload referral attachment", PRE_EXISTING),
        Map.entry("controller/FileUploadController.java::Failed to upload chart attachment", PRE_EXISTING),
        Map.entry("controller/FileUploadController.java::Voice memo duration must be between 1 and 90 seconds", PRE_EXISTING),
        Map.entry("controller/FileUploadController.java::Photo attachments must not carry a duration", PRE_EXISTING),
        Map.entry("controller/FileUploadController.java::Failed to upload chat attachment", PRE_EXISTING),
        Map.entry("controller/InvoiceEmailController.java::Invalid email", PRE_EXISTING),
        Map.entry("controller/OrganizationController.java::Default security policies applied successfully", PRE_EXISTING),
        Map.entry("controller/OwnEmailChangeController.java::Not authenticated.", PRE_EXISTING),
        Map.entry("controller/PatientController.java::Allergy entry deactivated.", PRE_EXISTING),
        Map.entry("controller/PatientController.java::Diagnosis removed successfully.", PRE_EXISTING)));

    /** {@code file::literal} for every English response literal in the source (comments stripped). */
    static Set<String> sites(String file, String rawSource) {
        String source = SourceScan.stripComments(rawSource);
        Set<String> out = new TreeSet<>();
        for (Pattern pattern : List.of(MESSAGE_DTO, BODY_LITERAL, MAP_MESSAGE)) {
            Matcher m = pattern.matcher(source);
            while (m.find()) {
                String literal = m.group(m.groupCount());
                if (isProse(literal)) {
                    out.add(file + "::" + literal);
                }
            }
        }
        Map<String, String> constants = new TreeMap<>();
        Matcher c = STRING_CONSTANT.matcher(source);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        for (Pattern pattern : List.of(MESSAGE_DTO_CONSTANT, BODY_CONSTANT, MAP_MESSAGE_CONSTANT)) {
            Matcher m = pattern.matcher(source);
            while (m.find()) {
                String value = constants.get(m.group(1));
                if (value != null && isProse(value)) {
                    out.add(file + "::" + value);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("no controller writes an English sentence into a response body beyond the listed sites")
    void responseBodiesAreKeyed() throws IOException {
        Set<String> actual = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                actual.addAll(sites(rel, Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        List<String> fresh = new ArrayList<>();
        for (String site : actual) {
            if (!ALLOWED.containsKey(site)) {
                fresh.add(site);
            }
        }
        assertThat(fresh)
            .as("resolve a messages*.properties key (MessageUtil.resolve) instead of an English sentence in the body"
                + SourceScan.MANDATE)
            .isEmpty();

        List<String> stale = new ArrayList<>();
        for (String site : ALLOWED.keySet()) {
            if (!actual.contains(site)) {
                stale.add(site);
            }
        }
        assertThat(stale)
            .as("the site was keyed: delete its line so the gain cannot be spent again" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches every body shape QA has reported, and not a key, a code or a comment")
    void theScanCatchesTheShapes() {
        String source = """
            // was: new MessageResponse("Signed out, see you soon.") — a comment is not a site
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
            """;
        assertThat(sites("x", source)).containsExactly(
            "x::Logged out successfully.",
            "x::Mail service unavailable",
            "x::Pre-check-in is available 1–7 days before your appointment.",
            "x::Refresh token is invalid or has expired.",
            "x::Verification code sent");
    }
}
