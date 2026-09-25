package com.example.hms.service.integration.message;

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
 * No unbounded sender-controlled field reaches a log line or a bounded column
 * on the MLLP paths.
 *
 * <p>Written because finding these one at a time did not converge. Over six
 * review rounds the same defect was reported in six places — MSH-10 into an
 * error message, the sender pair beside it, the sender pair on eight more
 * lines, the audit descriptions, the visit number, the not-allowlisted reason
 * — each time as "the fix did not reach this one". The fields are read
 * verbatim by {@code Hl7MessageInspector} and {@code Hl7v2MessageBuilder},
 * which apply no length limit, so an allowlisted (and on one path,
 * unallowlisted) sender chooses how many bytes each one costs. The sinks care:
 * {@code error_message} truncates at 2,000 and
 * {@code AuditEventLog.eventDescription} at 2,048, where exceeding it throws a
 * validation error that two layers of best-effort catch then swallow — so the
 * misbehaving sender's own rows are the ones that silently vanish.
 *
 * <p>This scans instead of asserting behaviour because the failure mode is a
 * <em>new</em> call site, which no runtime test sees until someone writes one.
 * It is a tripwire for the copy-paste case, not a proof: it matches raw
 * identifiers, so a field reached through a local alias still slips past.
 */
class MllpSenderControlledSinkTest {

    /** Fields an HL7 sender fills in, with no length the parser enforces. */
    private static final List<String> SENDER_CONTROLLED = List.of(
        "sendingApplication", "sendingFacility", "messageControlId",
        "senderApp", "senderFac", "controlId",
        "ctx.app", "ctx.fac", "ctx.controlId", "ctx.visitNumber");

    /** The wrappers that bound them. A call through any of these is fine. */
    private static final List<String> CAPS = List.of(
        "senderLabel(", "cappedSenderPair(", "messageControlId(", "cappedField(",
        "safeControlId(", "senderLabelFor(", "withControlId(", "integrationId(",
        "senderScope(", "trimToNull(");

    /** Statements whose arguments end up somewhere with a width. */
    private static final Pattern SINK = Pattern.compile(
        "(log\\.(?:warn|info|error|debug)|String\\.format|eventDescription)\\s*\\(");

    private static final List<String> FILES = List.of(
        "com/example/hms/hl7/mllp/Hl7MessageDispatcher.java",
        "com/example/hms/service/integration/impl/MllpInboundAdtServiceImpl.java",
        "com/example/hms/service/integration/impl/MllpInboundMergeServiceImpl.java",
        "com/example/hms/service/integration/impl/MllpInboundLabServiceImpl.java",
        "com/example/hms/service/integration/impl/MllpInboundAdtVisitProjectionServiceImpl.java");

    @Test
    @DisplayName("No MLLP log line or audit description takes a sender-controlled field raw")
    void noRawSenderControlledFieldReachesASink() throws IOException {
        Path main = Paths.get("src", "main", "java");
        assertThat(Files.isDirectory(main))
            .as("expected to scan %s from the module directory", main.toAbsolutePath())
            .isTrue();

        List<String> offenders = new ArrayList<>();
        for (String file : FILES) {
            Path path = main.resolve(file);
            assertThat(Files.isRegularFile(path))
                .as("%s has moved; update this guard rather than deleting the entry", file)
                .isTrue();
            String source = Files.readString(path, StandardCharsets.UTF_8);
            for (String statement : sinkStatements(source)) {
                statement = withoutStringLiterals(statement);
                for (String field : SENDER_CONTROLLED) {
                    if (containsRaw(statement, field)) {
                        offenders.add(file + " -> " + field + " in: " + oneLine(statement));
                    }
                }
            }
        }

        assertThat(offenders)
            .as("pass these through MllpRecordingContext (senderLabel / messageControlId / "
                + "cappedField / cappedSenderPair) before they reach a log line or a "
                + "length-limited column")
            .isEmpty();
    }

    /**
     * Quoted text out, by scanning rather than by regex. A format string
     * naturally contains the field's own name - "controlId={}" - and that
     * is a label, not the value; matching it would make the guard cry wolf
     * on every line it is meant to bless.
     */
    private static String withoutStringLiterals(String statement) {
        StringBuilder out = new StringBuilder(statement.length());
        boolean inString = false;
        for (int i = 0; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Each sink call, from its opening paren to the balancing one. */
    private static List<String> sinkStatements(String source) {
        List<String> statements = new ArrayList<>();
        Matcher matcher = SINK.matcher(source);
        while (matcher.find()) {
            int open = source.indexOf('(', matcher.start());
            int depth = 0;
            for (int i = open; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        statements.add(source.substring(open, i + 1));
                        break;
                    }
                }
            }
        }
        return statements;
    }

    /**
     * The field appears, and not as the argument of one of the caps. Crude:
     * it looks for the field immediately inside a cap's parentheses, which is
     * how every current call site is written.
     */
    private static boolean containsRaw(String statement, String field) {
        int from = 0;
        while (true) {
            int at = statement.indexOf(field, from);
            if (at < 0) {
                return false;
            }
            // Not a longer identifier that merely contains this one.
            boolean wholeWord = (at == 0 || !isIdentifierPart(statement.charAt(at - 1)))
                && (at + field.length() >= statement.length()
                    || !isIdentifierPart(statement.charAt(at + field.length())));
            if (wholeWord && !precededByCap(statement, at)
                    && !isNullCheck(statement, at + field.length())) {
                return true;
            }
            from = at + field.length();
        }
    }

    /**
     * {@code field != null} and {@code field == null} are comparisons, not
     * values: nothing of the sender's reaches the sink through them, and they
     * are how the capped ternaries next door are written.
     */
    private static boolean isNullCheck(String statement, int after) {
        String rest = statement.substring(after).stripLeading();
        return rest.startsWith("!= null") || rest.startsWith("== null");
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    private static boolean precededByCap(String statement, int at) {
        String before = statement.substring(Math.max(0, at - 80), at);
        for (String cap : CAPS) {
            int capAt = before.lastIndexOf(cap);
            if (capAt >= 0 && before.indexOf(')', capAt) < 0) {
                return true;
            }
        }
        return false;
    }

    private static String oneLine(String statement) {
        String flat = statement.replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 160) + "..." : flat;
    }

    @Test
    @DisplayName("The guard can actually see an offender")
    void theGuardDetectsARawField() {
        // Falsifies the scan itself: the matcher must flag a sink that takes
        // the field raw and must not flag the same field behind a cap.
        String raw = "log.warn(\"sender={}\", sendingApplication);";
        String capped = "log.warn(\"sender={}\", MllpRecordingContext.senderLabel(sendingApplication, x));";

        assertThat(sinkStatements(raw)).singleElement()
            .matches(s -> containsRaw(withoutStringLiterals(s), "sendingApplication"));
        assertThat(sinkStatements(capped)).singleElement()
            .matches(s -> !containsRaw(withoutStringLiterals(s), "sendingApplication"));
        // And a format string naming the field is not the field.
        assertThat(sinkStatements("log.warn(\"controlId={}\", x);")).singleElement()
            .matches(s -> !containsRaw(withoutStringLiterals(s), "controlId"));
    }

    @Test
    @DisplayName("A null check is not a sink")
    void aNullCheckIsNotASink() {
        String guarded = "log.info(x + (controlId != null ? messageControlId(controlId) : \"\"));";
        assertThat(sinkStatements(guarded)).singleElement()
            .matches(s -> !containsRaw(withoutStringLiterals(s), "controlId"));
    }

    @Test
    @DisplayName("The scan reaches real files")
    void theScanIsNotVacuous() throws IOException {
        Path main = Paths.get("src", "main", "java");
        try (Stream<Path> files = Files.walk(main)) {
            assertThat(files.filter(Files::isRegularFile).findAny()).isPresent();
        }
        assertThat(FILES).isNotEmpty();
    }
}
