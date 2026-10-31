package com.example.hms.i18n;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1102 guard (round 19). Class: "a decision-support refusal shown to a
 * clinician as developer text": the paediatric soft stop answered "CDS ALERT:
 * … — set forceOverride after clinical review." in every language, and the
 * dispense-time interaction stop "CDS_CRITICAL: …" over French literals.
 *
 * <ol>
 *   <li>Every {@code new CdsCriticalBlockException(…)} in the main tree takes
 *       its message from a bundle ({@code MessageUtil.…}) or from a variable
 *       resolved that way, never from a string literal or a concatenation.</li>
 *   <li>No refusal or card sentence of the CDS families in any bundle names an
 *       API field ({@code forceOverride}, {@code cdsOverrideReason}) or a
 *       developer token ({@code CDS_CRITICAL}, "CDS ALERT"): it names what the
 *       clinician sees on the screen.</li>
 * </ol>
 * The English-literal half is also counted by {@link ExceptionProseRatchetTest},
 * which now includes {@code CdsCriticalBlockException}.
 */
@DisplayName("CDS refusals are clinical text in the reader's language (QA D1102)")
class CdsRefusalTextGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern THROW = Pattern.compile("new\\s+(?:[a-z]\\w*\\.)*CdsCriticalBlockException\\s*\\(");
    private static final Pattern RESOLVED_OR_VARIABLE = Pattern.compile(
        "(?:com\\.example\\.hms\\.utility\\.)?MessageUtil\\.\\w+\\(.*|[a-z]\\w*", Pattern.DOTALL);

    /** The bundle families a CDS refusal or card sentence comes from. */
    private static final Pattern REFUSAL_KEY = Pattern.compile(
        "cds\\..*|prescription\\.(?:cds|allergy|pediatricReview|safeguard)\\..*"
            + "|pharmacy\\.(?:dispense\\.cds|cds\\.).*|nurse\\.mar\\.(?:override|fiveRights).*");
    /** An API field name (camelCase) or a developer token (UPPER_SNAKE, "CDS ALERT"). */
    private static final Pattern DEVELOPER_TEXT = Pattern.compile(
        "\\b[a-z]+[A-Z][A-Za-z]*\\b|\\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\\b|CDS ALERT");
    /** Clinical abbreviations that look like camelCase. */
    private static final Set<String> CLINICAL_WORDS = Set.of("mmHg", "qSOFA", "sBP", "eMAR");

    private static final List<String> BUNDLES = List.of("clinical", "pharmacy", "common", "lab", "maternity");
    private static final List<String> LOCALES = List.of("", "_en", "_fr", "_es");

    @Test
    @DisplayName("every CdsCriticalBlockException message comes from a bundle")
    void refusalsAreResolvedFromABundle() throws IOException {
        List<String> offenders = new ArrayList<>();
        int sites = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = THROW.matcher(source);
                while (m.find()) {
                    sites++;
                    String arg = firstArgument(source, m.end());
                    if (!isResolved(arg)) {
                        offenders.add(MAIN.relativize(file).toString().replace('\\', '/') + ": " + arg);
                    }
                }
            }
        }
        assertThat(sites).as("CdsCriticalBlockException sites found").isGreaterThanOrEqualTo(5);
        assertThat(offenders)
            .as("a CDS refusal built from a literal reaches the clinician as written: resolve a bundle key"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("no CDS refusal or card sentence names an API field or a developer token")
    void refusalTextNamesWhatTheClinicianSees() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (String bundle : BUNDLES) {
            for (String locale : LOCALES) {
                String resource = "/i18n/messages-" + bundle + locale + ".properties";
                Properties props = new Properties();
                try (InputStream in = getClass().getResourceAsStream(resource)) {
                    if (in == null) {
                        continue;
                    }
                    props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
                for (String key : props.stringPropertyNames()) {
                    if (!REFUSAL_KEY.matcher(key).matches()) {
                        continue;
                    }
                    checked++;
                    for (String hit : developerText(props.getProperty(key))) {
                        offenders.add(resource + " " + key + ": " + hit);
                    }
                }
            }
        }
        assertThat(checked).as("CDS refusal and card sentences checked").isGreaterThan(200);
        assertThat(offenders)
            .as("name the box or field the clinician sees (\"Outrepasser l'alerte critique\"), never the API"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scans catch the D1102 shapes")
    void catchesTheOldShapes() {
        String old = "throw new CdsCriticalBlockException(\n \"CDS ALERT: \" + card.summary()\n"
            + " + \" — set forceOverride after clinical review.\", advisories);";
        Matcher m = THROW.matcher(old);
        assertThat(m.find()).isTrue();
        assertThat(isResolved(firstArgument(old, m.end()))).isFalse();
        String fixed = "throw new CdsCriticalBlockException(MessageUtil.resolveOrRaw(\"prescription.cds.x\", a, b), cards);";
        Matcher f = THROW.matcher(fixed);
        assertThat(f.find()).isTrue();
        assertThat(isResolved(firstArgument(fixed, f.end()))).isTrue();
        assertThat(developerText("Dose — set forceOverride after clinical review.")).containsExactly("forceOverride");
        assertThat(developerText("CDS_CRITICAL: a pharmacist override reason is required.")).contains("CDS_CRITICAL");
        assertThat(developerText("Pression ≥ 160 mmHg : revue obstétricale urgente.")).isEmpty();
    }

    /** The first argument of a call whose '(' ends at {@code from}, up to the top-level comma. */
    static String firstArgument(String source, int from) {
        int depth = 0;
        boolean inString = false;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return source.substring(from, i).trim();
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                return source.substring(from, i).trim();
            }
        }
        return source.substring(from).trim();
    }

    static boolean isResolved(String argument) {
        return RESOLVED_OR_VARIABLE.matcher(argument).matches() && !argument.startsWith("\"");
    }

    static List<String> developerText(String value) {
        List<String> hits = new ArrayList<>();
        Matcher m = DEVELOPER_TEXT.matcher(value);
        while (m.find()) {
            if (!CLINICAL_WORDS.contains(m.group())) {
                hits.add(m.group());
            }
        }
        return hits;
    }
}
