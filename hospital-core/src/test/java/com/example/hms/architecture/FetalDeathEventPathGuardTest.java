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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1714 guard (round 46, class: a stillbirth recorded on a delivery that never reaches the mortality register).
 * The stillbirth lived only in the delivery's {@code liveBirth} flag, and the register counts records.
 *
 * <p>Every production method that saves a {@code DeliveryRecord} through a {@code DeliveryRecordRepository} records
 * or corrects its babies' outcomes through the fetal-death service ({@code fetalDeathEvents.recordOutcomes(} or
 * {@code fetalDeathEvents.correctOutcome(}) in the same method. Only {@code FetalDeathEventService} writes a
 * fetal-death event, nothing ever deletes one (a correction supersedes it), and a baby's outcome is set only by the
 * delivery's recording ({@code LaborServiceImpl}) or by the service. No allowlist.
 */
@DisplayName("Every path recording a delivery outcome goes through the fetal-death service")
class FetalDeathEventPathGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:private|public|protected|static)[^;{=(]*+\\(((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+)\\)\\s*+(?:throws [\\w., ]++)?+\\{");
    private static final Pattern DELIVERY_REPO_FIELD = Pattern.compile("DeliveryRecordRepository\\s++(\\w++)\\s*+[;=,)]");
    private static final Pattern EVENT_REPO_FIELD = Pattern.compile("FetalDeathEventRepository\\s++(\\w++)\\s*+[;=,)]");
    private static final Pattern THROUGH_THE_SERVICE =
        Pattern.compile("\\bfetalDeathEvents\\.(?:recordOutcomes|correctOutcome)\\(");

    private static final String SERVICE = "service/maternity/FetalDeathEventService.java";
    /** Call -> the only classes allowed to make it. */
    private static final Map<Pattern, Set<String>> ONLY_CALLERS = Map.of(
        Pattern.compile("\\.putBabyOutcome\\("),
        Set.of("service/impl/LaborServiceImpl.java", SERVICE, "model/labor/DeliveryRecord.java"),
        Pattern.compile("\\.setLiveBirth\\("), Set.of("model/labor/DeliveryRecord.java"),
        Pattern.compile("\\bFetalDeathEvent\\.builder\\(\\)"), Set.of(SERVICE),
        Pattern.compile("\\.setSupersededAt\\("), Set.of(SERVICE));

    @Test
    @DisplayName("every method that saves a delivery records its babies' outcomes through the fetal-death service")
    void everyDeliveryWriteGoesThroughTheService() throws IOException {
        List<String> writers = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        Map<String, Set<String>> strayCallers = new TreeMap<>();
        List<String> eventWriters = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                String cls = file.getFileName().toString().replace(".java", "");
                writers.addAll(scan(cls, source, false));
                offenders.addAll(scan(cls, source, true));
                ONLY_CALLERS.forEach((call, owners) -> {
                    if (call.matcher(source).find() && !owners.contains(rel)) {
                        strayCallers.computeIfAbsent(call.pattern(), k -> new TreeSet<>()).add(rel);
                    }
                });
                eventWriters.addAll(eventWrites(rel, source));
            }
        }
        assertThat(writers).as("the delivery write paths found")
            .contains("LaborServiceImpl#recordDelivery", "LaborServiceImpl#correctBabyOutcome");
        assertThat(offenders)
            .as("a method saves a delivery without recording its babies' outcomes through the fetal-death service "
                + "(QA D1714: a stillbirth never reached the mortality register): call "
                + "fetalDeathEvents.recordOutcomes(saved, recorderUserId) after the save" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(strayCallers)
            .as("a baby's outcome or a fetal-death event is written outside its owner" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(eventWrites(SERVICE, SourceScan.stripComments(Files.readString(MAIN.resolve(SERVICE),
            StandardCharsets.UTF_8)))).as("the service itself writes events").isNotEmpty();
        assertThat(eventWriters)
            .as("a fetal-death event is saved outside FetalDeathEventService, or deleted anywhere (a correction "
                + "supersedes it, keeping who, when and why)" + SourceScan.MANDATE)
            .allMatch(write -> write.startsWith(SERVICE + ":save"));
    }

    @Test
    @DisplayName("the register counts the fetal-death events")
    void theRegisterReadsTheEvents() throws IOException {
        String mortality = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/impl/MortalityServiceImpl.java"), StandardCharsets.UTF_8));
        assertThat(mortality).contains("fetalDeathEventRepository.findRegister(", "findLiveIdsAmong(");
    }

    @Test
    @DisplayName("the scan catches the round-46 shape")
    void catchesTheOldShape() {
        String before = """
            class LaborServiceImpl {
                private final DeliveryRecordRepository deliveryRecordRepository;

                public DeliveryRecordResponseDTO recordDelivery(UUID patientId, UUID episodeId, UUID recorderUserId,
                                                                DeliveryRecordRequestDTO request) {
                    DeliveryRecord saved = deliveryRecordRepository.save(record);
                    registerNewbornsAfterCommit(saved, recorderUserId, response);
                    return laborMapper.toDeliveryResponse(saved);
                }
            }
            """;
        assertThat(scan("LaborServiceImpl", before, true)).containsExactly("LaborServiceImpl#recordDelivery");
        String after = before.replace("registerNewbornsAfterCommit(saved, recorderUserId, response);",
            "fetalDeathEvents.recordOutcomes(saved, recorderUserId);");
        assertThat(scan("LaborServiceImpl", after, true)).isEmpty();

        String deleting = """
            class Cleanup {
                private final FetalDeathEventRepository events;
                void undo(FetalDeathEvent e) { events.delete(e); }
            }
            """;
        assertThat(eventWrites("service/Cleanup.java", deleting)).containsExactly("service/Cleanup.java:delete");
    }

    private static List<String> scan(String cls, String source, boolean offendersOnly) {
        List<String> out = new ArrayList<>();
        Pattern save = writePattern(DELIVERY_REPO_FIELD, source, "save(?:All|AndFlush)?+");
        if (save == null) {
            return out;
        }
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (!save.matcher(body).find()) {
                continue;
            }
            if (!offendersOnly || !THROUGH_THE_SERVICE.matcher(body).find()) {
                out.add(cls + "#" + CancelledOrderLockGuardTest.methodName(source.substring(m.start(), m.start(1))));
            }
        }
        return out;
    }

    /** Each save or delete through a FetalDeathEventRepository field of this source, as "file:verb". */
    private static List<String> eventWrites(String rel, String source) {
        List<String> out = new ArrayList<>();
        Pattern write = writePattern(EVENT_REPO_FIELD, source, "(save|delete)\\w*+");
        if (write == null) {
            return out;
        }
        Matcher m = write.matcher(source);
        while (m.find()) {
            out.add(rel + ":" + (m.group().contains(".delete") ? "delete" : "save"));
        }
        return out;
    }

    private static Pattern writePattern(Pattern field, String source, String verbs) {
        List<String> alternatives = new ArrayList<>();
        Matcher repo = field.matcher(source);
        while (repo.find()) {
            alternatives.add("\\b" + repo.group(1) + "\\." + verbs + "\\(");
        }
        return alternatives.isEmpty() ? null : Pattern.compile(String.join("|", alternatives));
    }
}
