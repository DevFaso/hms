package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1378 / D1360 (round 32, coordinator decision 2026-11-03): "is this
 * order as-needed (PRN)?" was answered from the free-text frequency, by a
 * word list - and round 32 found ~45 more wordings (SOS, p.r.n., sur demande,
 * si precisa, when necessary ...) that created a PRN order with no minimum
 * interval, controlled morphine included. A word list never covers them all.
 * As-needed is now the prescriber's explicit tick ({@code Prescription.asNeeded}, V260).
 *
 * <p>The class: a reader decides PRN from the frequency text. Rules over every
 * main source file:
 * <ol>
 *   <li>the entity's flag is read only through {@code PrnMinInterval.isAsNeeded}
 *       (and copied by the mapper, compared by the edit check): one helper;</li>
 *   <li>{@code MarDoseSchedule.read(...)} outside its own class takes the
 *       order, never a frequency text;</li>
 *   <li>{@code Reading.AS_NEEDED} is produced only by {@code MarDoseSchedule.read(Prescription)},
 *       from the helper;</li>
 *   <li>the frequency text bears on as-needed ONLY through the wording rule:
 *       {@code FrequencyWording.classify(} is called only by
 *       {@code PrnMinInterval.safetyNet}, and the frozen phrase list is read only
 *       inside {@code MarDoseSchedule}.</li>
 *   <li>QA D1382 / D1396: every writer of order-set items (from a request) holds
 *       them to the prescription rule with {@code OrderSetItemPrnRules.requireValid}
 *       and to the quantity rule with {@code requireValid} of
 *       {@code OrderSetItemQuantityRules}.</li>
 *   <li>QA D1391 / D1395: the prescription service's edit goes through
 *       {@code PrnMinInterval.applyToEdit} (a frequency the edit did not write is
 *       not judged), its create through {@code applyTo}.</li>
 * </ol>
 * The portal half is {@code scripts/lib/as-needed-frequency.test.mjs}: no
 * screen reads the frequency for PRN, the forms show the interval from the
 * tick.
 */
class MarAsNeededFlagGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String SCHEDULE = "service/impl/MarDoseSchedule.java";
    private static final String HELPER = "service/impl/PrnMinInterval.java";
    /** QA D1419: the wording rule asks the one reader what a text says, which is what it is for. */
    private static final String WORDING = "service/impl/FrequencyWording.java";

    /** Rule 1: who may read the entity's flag directly, and why. */
    private static final Map<String, String> FLAG_READERS = Map.of(
        HELPER, "the one helper, PrnMinInterval.isAsNeeded",
        "mapper/PrescriptionMapper.java", "copies the flag into the response",
        "service/PrescriptionServiceImpl.java", "compares the requested tick with the stored one (signed-content edit check)");

    private static final Pattern FLAG_READ = Pattern.compile("\\.isAsNeeded\\(\\)");
    private static final Pattern READ_CALL = Pattern.compile("MarDoseSchedule\\.read\\(([^()]*+(?:\\(\\))?+)\\)");
    private static final Pattern AS_NEEDED_READING = Pattern.compile("Reading\\.AS_NEEDED\\b");
    private static final Pattern NET_CALL = Pattern.compile("\\bFrequencyWording\\.classify\\(");
    /** Rule 5 (QA D1382): an order-set writer stores the request's items. */
    /** Rule 5 (QA D1396): the writer's call of the quantity rule. */
    private static final Pattern READS_QUANTITY_RULE = Pattern.compile("[qQ]uantityRules\\.requireValid\\(");
    private static final Pattern WRITES_ORDER_ITEMS = Pattern.compile("\\.setOrderItems\\([^;]*request\\.getOrderItems\\(\\)");

    @Test
    void noReaderDecidesPrnFromTheFrequencyText() throws IOException {
        Map<String, String> found = new TreeMap<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String src = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                scanned++;
                check(rel, src, found);
            }
        }
        assertThat(scanned).as("the scan read the main sources").isGreaterThan(1000);
        assertThat(found)
            .as("as-needed decided outside the prescriber's tick (QA D1378)%s", SourceScan.MANDATE)
            .isEmpty();
    }

    private static void check(String rel, String src, Map<String, String> found) {
        if (FLAG_READ.matcher(src).find() && !FLAG_READERS.containsKey(rel) && !rel.startsWith("model/")
            && !rel.equals(SCHEDULE)) {
            found.put(rel + " #1", "reads Prescription.isAsNeeded() itself: go through PrnMinInterval.isAsNeeded");
        }
        if (!rel.equals(SCHEDULE)) {
            Matcher read = READ_CALL.matcher(src);
            while (read.find()) {
                if (!rel.equals(WORDING) && read.group(1).toLowerCase(java.util.Locale.ROOT).contains("frequency")) {
                    found.put(rel + " #2", "MarDoseSchedule.read(" + read.group(1) + "): pass the order, whose"
                        + " tick decides as-needed, not its frequency text");
                }
            }
            if (AS_NEEDED_READING.matcher(src).find()) {
                found.put(rel + " #3", "builds an AS_NEEDED reading itself");
            }
            if (src.contains("AS_NEEDED_PHRASES")) {
                found.put(rel + " #4", "reads the frozen as-needed phrase list");
            }
        }
        if (NET_CALL.matcher(src).find() && !rel.equals(HELPER)) {
            found.put(rel + " #4", "calls the wording rule itself: use PrnMinInterval.safetyNet");
        }
        if (WRITES_ORDER_ITEMS.matcher(src).find() && !src.contains("OrderSetItemPrnRules.requireValid(")) {
            found.put(rel + " #5", "writes order-set items without OrderSetItemPrnRules.requireValid (QA D1382)");
        }
        if (WRITES_ORDER_ITEMS.matcher(src).find() && !READS_QUANTITY_RULE.matcher(src).find()) {
            found.put(rel + " #5b", "writes order-set items without OrderSetItemQuantityRules (QA D1396)");
        }
    }

    /** Rule 3: inside MarDoseSchedule, the AS_NEEDED reading is returned by read(Prescription) only. */
    @Test
    void onlyTheTickMakesAnAsNeededReading() throws IOException {
        String src = SourceScan.stripComments(Files.readString(MAIN.resolve(SCHEDULE), StandardCharsets.UTF_8));
        int method = src.indexOf("public static Reading read(Prescription rx)");
        assertThat(method).as("read(Prescription) exists").isPositive();
        int body = src.indexOf('{', method);
        String readOrder = src.substring(body, SourceScan.closingBrace(src, body));
        assertThat(readOrder).contains("PrnMinInterval.isAsNeeded(rx) ? Reading.AS_NEEDED");
        Matcher uses = AS_NEEDED_READING.matcher(src);
        int count = 0;
        while (uses.find()) {
            count++;
        }
        // The constant's own declaration ("Reading AS_NEEDED = new Reading") does not match; the one use does.
        assertThat(count).as("Reading.AS_NEEDED used once, in read(Prescription)").isEqualTo(1);
    }

    /** Rule 6 (QA D1391 / D1395): an edit is judged on what it wrote; the MAR never reads the wording rule. */
    @Test
    void anEditIsJudgedOnWhatItWrote() throws IOException {
        String service = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/PrescriptionServiceImpl.java"), StandardCharsets.UTF_8));
        assertThat(service).contains("PrnMinInterval.applyToEdit(existing, frequencyBefore, tickedBefore,");
        assertThat(Pattern.compile("PrnMinInterval\\.applyTo\\(").matcher(service).results().count())
            .as("applyTo is the create path only").isEqualTo(1);
        for (String reader : java.util.List.of("service/impl/MarNextDose.java", "service/impl/MarDoseInterval.java")) {
            assertThat(SourceScan.stripComments(Files.readString(MAIN.resolve(reader), StandardCharsets.UTF_8)))
                .as(reader).doesNotContain("FrequencyWording");
        }
        String schedule = SourceScan.stripComments(Files.readString(MAIN.resolve(SCHEDULE), StandardCharsets.UTF_8));
        assertThat(schedule).as("the MAR's reader does not read the wording rule").doesNotContain("FrequencyWording");
    }

    @Test
    void theScanSeesTheShapesItGuards() {
        Map<String, String> found = new TreeMap<>();
        check("service/impl/Example.java",
            "if (MarDoseSchedule.read(rx.getFrequency()).kind() == K) {} boolean b = rx.isAsNeeded();"
                + " x = FrequencyWording.classify(f); y = Reading.AS_NEEDED;"
                + " next.setOrderItems(request.getOrderItems() == null ? List.of() : request.getOrderItems());",
            found);
        assertThat(found).containsKeys("service/impl/Example.java #1", "service/impl/Example.java #2",
            "service/impl/Example.java #3", "service/impl/Example.java #4", "service/impl/Example.java #5",
            "service/impl/Example.java #5b");
        Map<String, String> clean = new TreeMap<>();
        check("service/impl/Example.java", "MarDoseSchedule.read(rx); PrnMinInterval.isAsNeeded(rx);", clean);
        assertThat(clean).isEmpty();
    }

    @Test
    void theAllowlistNamesRealFiles() {
        for (String rel : FLAG_READERS.keySet()) {
            assertThat(MAIN.resolve(rel)).as("stale flag reader " + rel).exists();
        }
    }
}
