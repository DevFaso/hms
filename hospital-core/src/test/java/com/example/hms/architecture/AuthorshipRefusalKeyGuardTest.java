package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1112 guard (round 19): an authorship refusal that gives the wrong
 * reason. A doctor finalizing a colleague's discharge summary, or rewriting a
 * colleague's treatment plan, read "Vous ne pouvez agir qu'en votre propre
 * nom" ({@code identity.notCaller}) - the refusal for naming someone else in a
 * request - instead of "Seul le médecin qui a rédigé ce résumé peut le finaliser."
 *
 * <ol>
 *   <li>{@code CallerIdentity.requireCaller…} on a record's own author (an
 *       argument read from the loaded record, {@code x.getAuthor()}) is an
 *       authorship check: it must be {@code requireAuthor…(author, "<object>.…Only")}.
 *       {@code requireCaller} stays for the acting-on-behalf checks, whose
 *       argument is the staff the request names.</li>
 *   <li>Every {@code requireAuthor…} names a per-object key (not
 *       {@code identity.notCaller}) that the bundles define in en, fr and es.</li>
 *   <li>{@code "identity.notCaller"} is written only in {@code CallerIdentity}
 *       and the sites listed below, each a real acting-on-behalf refusal.</li>
 * </ol>
 */
@DisplayName("An authorship refusal names the authorship rule")
class AuthorshipRefusalKeyGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern CALLER_ON_RECORD = Pattern.compile(
        "CallerIdentity\\.requireCaller(?:UnlessSuperAdmin)?\\(\\s*\\w+\\.get\\w+\\(\\)\\s*\\)");
    private static final Pattern AUTHOR_CALL = Pattern.compile(
        "CallerIdentity\\.requireAuthor(?:UnlessSuperAdmin)?\\([^;]*?,\\s*(\"[^\"]+\"|[A-Z_]+)\\s*\\)");
    private static final Pattern CONSTANT = Pattern.compile(
        "static final String ([A-Z_]+)\\s*=\\s*\"([^\"]+)\"");

    /** Where identity.notCaller is the true reason (the request names someone else), each with why. */
    private static final Map<String, String> ACTING_ON_BEHALF = Map.of(
        "security/CallerIdentity.java", "the acting-on-behalf primitive itself (requireCaller, resolveActorUserId)",
        "service/DischargeSummaryServiceImpl.java",
        "finalize by its author with a providerId naming someone else: the request acts in another's name",
        "service/MedicalCertificateServiceImpl.java",
        "the signer is resolved from the session at this hospital; a caller with no staff record there signs in "
            + "nobody's name (not an authorship check)");

    @Test
    @DisplayName("authorship checks use requireAuthor with a per-object key, defined in every bundle")
    void authorshipRefusalsNameTheRule() throws IOException {
        List<String> callerOnRecord = new ArrayList<>();
        List<String> badKeys = new ArrayList<>();
        Map<String, Integer> notCallerSites = new TreeMap<>();
        List<String> authorKeys = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher c = CALLER_ON_RECORD.matcher(source);
                while (c.find()) {
                    callerOnRecord.add(rel + ":" + SourceScan.lineOf(source, c.start()) + " " + c.group());
                }
                Map<String, String> constants = new TreeMap<>();
                Matcher k = CONSTANT.matcher(source);
                while (k.find()) {
                    constants.put(k.group(1), k.group(2));
                }
                Matcher a = AUTHOR_CALL.matcher(source);
                while (a.find()) {
                    String arg = a.group(1);
                    String key = arg.startsWith("\"") ? arg.substring(1, arg.length() - 1) : constants.get(arg);
                    if (key == null || "identity.notCaller".equals(key)) {
                        badKeys.add(rel + ":" + SourceScan.lineOf(source, a.start()) + " " + arg);
                    } else {
                        authorKeys.add(key);
                    }
                }
                int n = source.split("\"identity\\.notCaller\"", -1).length - 1;
                if (n > 0) {
                    notCallerSites.put(rel, n);
                }
            }
        }
        assertThat(callerOnRecord)
            .as("requireCaller on a record's own author answers \"act in your own name\" (QA D1112): use "
                + "CallerIdentity.requireAuthor(author, \"<object>.authorOnly\")" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(badKeys).as("a requireAuthor call without its own literal or constant key").isEmpty();
        assertThat(authorKeys).as("the authorship checks found (summary, plan, history, discharge request)")
            .contains("dischargeSummary.finalize.authorOnly", "treatmentPlan.authorOnly",
                "maternalHistory.delete.recorderOnly", "dischargeApproval.cancel.requesterOnly");
        assertThat(notCallerSites.keySet())
            .as("identity.notCaller written outside the acting-on-behalf sites (QA D1112)" + SourceScan.MANDATE)
            .isSubsetOf(ACTING_ON_BEHALF.keySet());
        assertThat(notCallerSites.keySet()).as("a listed site no longer uses the key: delete its line")
            .containsAll(ACTING_ON_BEHALF.keySet());
        for (String bundle : List.of("messages-clinical", "messages-clinical_en", "messages-clinical_fr",
                "messages-clinical_es")) {
            Properties props = load("/i18n/" + bundle + ".properties");
            for (String key : authorKeys) {
                assertThat(props.getProperty(key)).as(bundle + " defines " + key).isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("the scan catches the round-19 shape")
    void catchesTheOldShape() {
        String before = "CallerIdentity.requireCaller(dischargeSummary.getDischargingProvider());";
        assertThat(CALLER_ON_RECORD.matcher(before).find()).isTrue();
        assertThat(CALLER_ON_RECORD.matcher("CallerIdentity.requireCallerUnlessSuperAdmin(author);").find())
            .as("naming the caller in the request is the acting-on-behalf check").isFalse();
        Matcher a = AUTHOR_CALL.matcher(
            "CallerIdentity.requireAuthor(dischargeSummary.getDischargingProvider(),\n"
                + "            \"dischargeSummary.finalize.authorOnly\");");
        assertThat(a.find()).isTrue();
        assertThat(a.group(1)).isEqualTo("\"dischargeSummary.finalize.authorOnly\"");
    }

    private static Properties load(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream in = AuthorshipRefusalKeyGuardTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }
}
