package com.example.hms.architecture;

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
 * QA D2051 (r58): {@code POST /auth/password/confirm} and the legacy
 * {@code /auth/reset-password} caught the service's "invalid / expired / used
 * link" refusal and answered 204, so the portal said « Mot de passe
 * réinitialisé » for a password that never changed.
 *
 * <p>The class: an endpoint that CONFIRMS something (a reset link, an e-mail
 * verification, an activation, a recovery-contact or MFA code) must not catch
 * the refusal and still answer success. Every {@code catch} in such a handler
 * either throws, or returns a non-2xx answer. Neutral 204 answers to
 * {@code request…} endpoints (no account oracle, QA D802 / D1017) are not
 * confirmations and are not read here.</p>
 */
@DisplayName("A confirmation endpoint never swallows its refusal into a success")
class HonestConfirmationGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern CLASS_MAPPING = Pattern.compile("\\n@RequestMapping\\b");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
        "\\n    @(?:Get|Post|Put|Patch|Delete|Request)Mapping\\b");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"]*+)\"");

    /** A path that confirms: a reset, a verification, an activation (not de-/re-activate), a confirm. */
    static final Pattern CONFIRMING_PATH = Pattern.compile(
        "(?i)confirm|(?<!resend-)verif|(?<!de|re)activat|reset-password|/recovery/");

    private static final Pattern TRY = Pattern.compile("\\btry\\b");
    /**
     * The confirming call itself ({@code confirmReset(}, {@code verifyEmail(}, ...). A catch around
     * a side effect after the confirmation succeeded (a cookie write, a notice) is not a swallowed refusal.
     */
    static final Pattern CONFIRMING_CALL = Pattern.compile(
        "\\.(?:confirm|verify|activate|reset|consume|redeem)\\w*+\\s*+\\(");
    private static final Pattern THROW = Pattern.compile("\\bthrow\\b");
    private static final Pattern RETURN = Pattern.compile("\\breturn\\b");
    /** A 2xx answer: what a swallowed refusal must never become. */
    private static final Pattern SUCCESS = Pattern.compile(
        "ResponseEntity\\s*+\\.\\s*+(?:ok|noContent|accepted|created)\\b"
            + "|HttpStatus\\.(?:OK|NO_CONTENT|ACCEPTED|CREATED)\\b|\\bstatus\\(\\s*+20\\d\\s*+\\)");

    static List<String> offenders(String file, String source) {
        List<String> out = new ArrayList<>();
        String classPath = mappingPaths(source, CLASS_MAPPING);
        Matcher m = METHOD_MAPPING.matcher(source);
        while (m.find()) {
            String paths = classPath + " " + annotationPaths(source, m.end());
            if (!CONFIRMING_PATH.matcher(paths).find()) {
                continue;
            }
            int open = bodyOpen(source, m.end());
            if (open < 0) {
                continue;
            }
            String body = source.substring(open, SourceScan.closingBrace(source, open) + 1);
            Matcher t = TRY.matcher(body);
            while (t.find()) {
                int i = skipSpace(body, t.end());
                if (i < body.length() && body.charAt(i) == '(') {
                    i = skipSpace(body, SourceScan.closingParen(body, i) + 1);
                }
                if (i >= body.length() || body.charAt(i) != '{') {
                    continue;
                }
                int tryClose = SourceScan.closingBrace(body, i);
                boolean confirms = CONFIRMING_CALL.matcher(body.substring(i, tryClose + 1)).find();
                int next = skipSpace(body, tryClose + 1);
                while (body.startsWith("catch", next)) {
                    int paren = body.indexOf('(', next);
                    int blockOpen = body.indexOf('{', SourceScan.closingParen(body, paren));
                    int blockClose = SourceScan.closingBrace(body, blockOpen);
                    String block = body.substring(blockOpen, blockClose + 1);
                    boolean throwsOn = THROW.matcher(block).find();
                    boolean refuses = RETURN.matcher(block).find() && !SUCCESS.matcher(block).find();
                    if (confirms && !throwsOn && !refuses) {
                        out.add(file + " " + paths.trim() + " (line " + SourceScan.lineOf(source, open + next) + ")");
                    }
                    next = skipSpace(body, blockClose + 1);
                }
            }
        }
        return out;
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String mappingPaths(String source, Pattern annotation) {
        Matcher m = annotation.matcher(source);
        return m.find() ? annotationPaths(source, m.end()) : "";
    }

    /** The string literals of the annotation whose name ends at {@code end}, if it has arguments. */
    private static String annotationPaths(String source, int end) {
        int i = end;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        if (i >= source.length() || source.charAt(i) != '(') {
            return "";
        }
        String args = source.substring(i, SourceScan.closingParen(source, i) + 1);
        StringBuilder paths = new StringBuilder();
        Matcher lit = LITERAL.matcher(args);
        while (lit.find()) {
            paths.append(lit.group(1)).append(' ');
        }
        return paths.toString();
    }

    /** The {@code {} of the method body after the annotation: the first brace outside every paren and literal. */
    private static int bodyOpen(String source, int from) {
        int depth = 0;
        for (int i = from; i < source.length(); i++) {
            int skipped = SourceScan.endOfLiteralOrComment(source, i);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            char ch = source.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ';' && depth == 0) {
                return -1;
            } else if (ch == '{' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("the scan catches the D2051 shape and accepts its fix and an honest non-2xx catch")
    void catchesTheD2051Shape() {
        String before = """
            package x;
            @RestController
            @RequestMapping("/auth/password")
            public class C {
                @PostMapping("/request")
                public ResponseEntity<Void> request(@RequestBody Dto dto) {
                    try { service.request(dto); } catch (RuntimeException e) { log.debug("x"); }
                    return ResponseEntity.noContent().build();
                }

                @PostMapping("/confirm")
                public ResponseEntity<Void> confirmReset(@Valid @RequestBody Dto dto) {
                    try {
                        service.confirmReset(dto.getToken(), dto.getNewPassword());
                    } catch (ResourceNotFoundException | IllegalStateException e) {
                        log.debug("Password reset confirm failed: {}", e.getMessage());
                    }
                    return ResponseEntity.noContent().build();
                }
            }
            """;
        assertThat(offenders("C", before)).singleElement().asString().contains("/confirm");

        String fixed = before.replace("""
                    try {
                        service.confirmReset(dto.getToken(), dto.getNewPassword());
                    } catch (ResourceNotFoundException | IllegalStateException e) {
                        log.debug("Password reset confirm failed: {}", e.getMessage());
                    }
            """, """
                    service.confirmReset(dto.getToken(), dto.getNewPassword());
            """);
        assertThat(fixed).isNotEqualTo(before);
        assertThat(offenders("C", fixed)).as("the neutral request answer is not a confirmation").isEmpty();

        String gone = before.replace("log.debug(\"Password reset confirm failed: {}\", e.getMessage());",
            "return ResponseEntity.status(HttpStatus.GONE).build();");
        assertThat(offenders("C", gone)).as("a catch that answers non-2xx is honest").isEmpty();

        String okInCatch = before.replace("log.debug(\"Password reset confirm failed: {}\", e.getMessage());",
            "return ResponseEntity.ok().build();");
        assertThat(offenders("C", okInCatch)).as("a catch that answers 200 is the same swallow").hasSize(1);

        String sideEffect = fixed.replace("return ResponseEntity.noContent().build();\n    }\n}",
            "try { cookies.write(response); } catch (Exception ex) { log.warn(\"cookie\"); }\n"
                + "        return ResponseEntity.noContent().build();\n    }\n}");
        assertThat(sideEffect).isNotEqualTo(fixed);
        assertThat(offenders("C", sideEffect))
            .as("a catch around a side effect after the confirmation (MFA's cookie write) is not a swallowed refusal")
            .isEmpty();
    }

    @Test
    @DisplayName("no confirmation endpoint answers success after catching its refusal")
    void noConfirmationSwallowsItsRefusal() throws IOException {
        List<String> offenders = new ArrayList<>();
        int confirming = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!source.contains("@RestController") && !source.contains("@Controller")) {
                    continue;
                }
                String classPath = mappingPaths(source, CLASS_MAPPING);
                Matcher m = METHOD_MAPPING.matcher(source);
                while (m.find()) {
                    if (CONFIRMING_PATH.matcher(classPath + " " + annotationPaths(source, m.end())).find()) {
                        confirming++;
                    }
                }
                offenders.addAll(offenders(file.getFileName().toString(), source));
            }
        }
        assertThat(confirming).as("the scan found the confirmation endpoints").isGreaterThanOrEqualTo(15);
        assertThat(offenders)
            .as("a confirmation that catches its refusal and still answers success (QA D2051)" + SourceScan.MANDATE)
            .isEmpty();
    }
}
