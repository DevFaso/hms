package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D938 / D931: every class that releases a lab result tells the people a
 * release is for, and every class that supersedes one closes what pointed at
 * the old one.
 *
 * <p>The defects were two halves of one shape: the release path wrote the
 * released flag and the critical page, and nothing else - the patient was
 * never told a result was ready, and a correction withdrew the old result
 * while its in-basket items stayed open on a 404. A new release path (an
 * importer, a FHIR write, a bulk release) would repeat it with nothing
 * failing, so this test reads the source:
 * <ul>
 *   <li>a class that writes {@code setReleased(true)} must call
 *       {@code releaseNotifier.patientResultReleased};</li>
 *   <li>a class that links a superseded result
 *       ({@code setSupersededByResultId(<non-null>)}) must call
 *       {@code removeOpenItemsForReference} and
 *       {@code releaseNotifier.correctionReleased}.</li>
 * </ul>
 */
class LabResultReleaseAnnouncementGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern RELEASES = Pattern.compile("\\.setReleased\\(\\s*true\\s*\\)|\\.released\\(\\s*true\\s*\\)");
    private static final Pattern SUPERSEDES = Pattern.compile("\\.setSupersededByResultId\\(\\s*(?!null\\s*\\))");

    @Test
    void everyReleasePathTellsThePatient() throws IOException {
        TreeSet<String> releasing = filesMatching(RELEASES);
        assertThat(releasing)
            .as("the scan must see the known release paths, or it is scanning nothing")
            .contains("com/example/hms/service/LabResultServiceImpl.java",
                "com/example/hms/service/integration/impl/MllpInboundLabServiceImpl.java");
        List<String> silent = releasing.stream()
            .filter(file -> {
                String source = read(file);
                // The service's announce is the choke point (in-basket item + patient notice);
                // a path outside the service calls it through LabResultService.announceReleasedResult.
                return !source.contains("releaseNotifier.patientResultReleased(")
                    && !source.contains("labResultService.announceReleasedResult(");
            })
            .toList();
        assertThat(silent)
            .as("a class that releases a lab result must announce it (QA D938): call "
                + "LabResultReleaseNotifier.patientResultReleased")
            .isEmpty();
    }

    @Test
    void everySupersedingPathClosesTheOldItemsAndTellsTheClinician() throws IOException {
        TreeSet<String> superseding = filesMatching(SUPERSEDES);
        assertThat(superseding).contains("com/example/hms/service/LabResultServiceImpl.java");
        List<String> incomplete = superseding.stream()
            .filter(file -> {
                String source = read(file);
                return !source.contains("removeOpenItemsForReference(")
                    || !source.contains("releaseNotifier.correctionReleased(");
            })
            .toList();
        assertThat(incomplete)
            .as("a class that supersedes a released lab result must close its open in-basket items and "
                + "tell the ordering clinician (QA D931)")
            .isEmpty();
    }

    private static final Pattern READS_VALUE = Pattern.compile("\\.getResultValue\\(\\)");
    private static final Pattern ASKS_CORRECTION =
        Pattern.compile("isCorrection\\(|isCorrected\\(\\)|getSupersedesResultId\\(\\)");

    /**
     * QA D1016 (D931 was fixed on the clinician's side only): a corrected
     * result reached the patient portal, the patient's notification, the
     * in-basket table, the chart review and the release worklist as an
     * ordinary result. Every class that reads a lab result's value to show or
     * send it must also ask whether it is a correction
     * ({@code LabResultCorrections.isCorrection}, a DTO's {@code isCorrected()},
     * or the link itself), so a new screen or export cannot drop the marker.
     */
    @Test
    void everyClassShowingAResultValueSaysWhetherItIsACorrection() throws IOException {
        TreeSet<String> showing = filesMatching(READS_VALUE);
        assertThat(showing)
            .as("the scan must see the known result readers, or it is scanning nothing")
            .contains("com/example/hms/service/ResultReviewServiceImpl.java",
                "com/example/hms/service/impl/PatientLabResultServiceImpl.java",
                "com/example/hms/service/impl/ChartReviewServiceImpl.java");
        List<String> unmarked = showing.stream()
            .filter(file -> !ASKS_CORRECTION.matcher(code(file)).find())
            .toList();
        assertThat(unmarked)
            .as("a class that shows or sends a lab result's value must say whether it corrects a released "
                + "result (QA D1016): call LabResultCorrections.isCorrection and carry it to the reader")
            .isEmpty();
    }

    private static final Pattern BUILDER = Pattern.compile("\\b(\\w+)\\.builder\\(\\)");
    private static final Pattern PRIVATE_HELPER = Pattern.compile(
        "\\n    private\\s++(?:static\\s++)?+(?:[\\w<>\\[\\],]++\\s++)++(\\w++)\\(((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+)\\)\\s*+\\{");

    /**
     * QA D1016/D931 (round 18, NOT_FIXED a third time): the class-level rule
     * above passed for the chart review because its Results section asked the
     * question - and its Chronologie, built from the same row, dropped the
     * answer. Per row, now: every DTO built with a lab result's value (read
     * directly, or through a helper of the same class such as
     * {@code formatResult(r)}) must carry the correction in the same builder
     * ({@code .corrected(…)}, {@code isCorrection}, {@code isCorrected()}).
     */
    @Test
    void everyRowBuiltFromAResultValueCarriesTheCorrection() throws IOException {
        java.util.List<String> unmarked = new java.util.ArrayList<>();
        for (String file : filesMatching(READS_VALUE)) {
            unmarked.addAll(rowsWithoutCorrection(file, code(file)));
        }
        assertThat(unmarked)
            .as("a row built from a lab result's value drops whether it corrects a released result, so one "
                + "screen shows the corrected value as the original (QA D1016/D931): carry .corrected(...) "
                + "in the same builder")
            .isEmpty();
    }

    @Test
    void theRowScanCatchesTheChronologyShape() {
        String before = """
            class ChartReviewServiceImpl {
                private List<TimelineEventDTO> buildTimeline(List<ResultEntryDTO> results) {
                    results.forEach(r -> events.add(TimelineEventDTO.builder()
                        .title(joinNonBlank(" ", r.getTestName(), formatResult(r)))
                        .status(r.getAbnormalFlag())
                        .build()));
                }
                private static String formatResult(ResultEntryDTO r) {
                    return r.getResultValue() + " " + r.getResultUnit();
                }
            }
            """;
        assertThat(rowsWithoutCorrection("ChartReviewServiceImpl.java", before)).hasSize(1);
        String after = before.replace(".status(r.getAbnormalFlag())", ".status(r.getAbnormalFlag()).corrected(r.isCorrected())");
        assertThat(rowsWithoutCorrection("ChartReviewServiceImpl.java", after)).isEmpty();
    }

    static java.util.List<String> rowsWithoutCorrection(String file, String source) {
        java.util.Set<String> valueHelpers = new java.util.TreeSet<>();
        java.util.regex.Matcher helper = PRIVATE_HELPER.matcher(source);
        while (helper.find()) {
            int open = helper.end() - 1;
            int close = com.example.hms.testsupport.SourceScan.closingBrace(source, open);
            if (READS_VALUE.matcher(source.substring(open, Math.min(close + 1, source.length()))).find()) {
                valueHelpers.add(helper.group(1));
            }
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher builder = BUILDER.matcher(source);
        while (builder.find()) {
            int end = source.indexOf(".build()", builder.end());
            if (!builder.group(1).endsWith("DTO")) {
                continue; // an entity built from a request, not a row shown to someone
            }
            String whole = end < 0 ? source.substring(builder.end()) : source.substring(builder.end(), end);
            int nested = whole.indexOf(".builder()");
            String chain = nested >= 0 ? whole.substring(0, nested) : whole;
            boolean takesValue = READS_VALUE.matcher(chain).find()
                || valueHelpers.stream().anyMatch(h -> chain.matches("(?s).*\\b" + h + "\\(.*"));
            if (takesValue && !chain.contains(".corrected(") && !ASKS_CORRECTION.matcher(chain).find()) {
                out.add(file + ":" + com.example.hms.testsupport.SourceScan.lineOf(source, builder.start())
                    + " " + builder.group(1) + ".builder()");
            }
        }
        return out;
    }

    private static final Pattern IN_BASKET_KEY = Pattern.compile("\"([A-Za-z0-9_.]*\\.inBasket[A-Za-z0-9_]*)\"");

    /**
     * QA D1053: the correction and rejection items were resolved in French
     * when they were written and stored as prose, so Spanish and English
     * readers saw French. Every in-basket message key a class resolves must
     * also be stored as the item's key ({@code setTitleKey} /
     * {@code setBodyKey}), through the literal or the constant that holds it,
     * so the item is rendered in the reader's language.
     */
    @Test
    void everyInBasketTextIsStoredAsAKey() throws IOException {
        TreeSet<String> composing = filesMatching(IN_BASKET_KEY);
        assertThat(composing)
            .as("the scan must see the known in-basket composers")
            .contains("com/example/hms/service/LabResultServiceImpl.java",
                "com/example/hms/service/lab/LabSpecimenRejectionNotifier.java");
        List<String> prose = new java.util.ArrayList<>();
        for (String file : composing) {
            String source = code(file);
            java.util.regex.Matcher m = IN_BASKET_KEY.matcher(source);
            while (m.find()) {
                String key = m.group(1);
                java.util.regex.Matcher constant = Pattern
                    .compile("(\\w+)\\s*=\\s*\"" + Pattern.quote(key) + "\"").matcher(source);
                String name = constant.find() ? constant.group(1) : null;
                boolean stored = source.matches("(?s).*set(Title|Body)Key\\(\\s*\"" + Pattern.quote(key) + "\".*")
                    || name != null && source.matches("(?s).*set(Title|Body)Key\\(\\s*" + name + "\\s*\\).*");
                if (!stored) {
                    prose.add(file + " -> " + key);
                }
            }
        }
        assertThat(prose)
            .as("an in-basket title or body resolved from a message key must also store the key "
                + "(setTitleKey / setBodyKey) so it renders in the reader's language (QA D1053)")
            .isEmpty();
    }

    private static String code(String relative) {
        return com.example.hms.testsupport.SourceScan.stripComments(read(relative));
    }

    private static TreeSet<String> filesMatching(Pattern pattern) throws IOException {
        TreeSet<String> files = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(MAIN)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    String relative = MAIN.relativize(p).toString().replace('\\', '/');
                    if (pattern.matcher(code(relative)).find()) {
                        files.add(relative);
                    }
                });
        }
        return files;
    }

    private static String read(String relative) {
        try {
            return Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
