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
 * QA D1592 guard (round 44, class: a recorded live birth that leaves the baby
 * without a patient record). A delivery created no newborn patient: no MRN, no
 * link to the mother, nothing in pediatrics.
 *
 * <p>Every production method that saves a {@code DeliveryRecord} through a
 * {@code DeliveryRecordRepository} must call
 * {@code newbornRegistration.registerLiveBorn(} in the same method. The
 * registration bypass stays narrow: only {@code NewbornRegistrationService}
 * registers a newborn or writes a delivery-newborn link, only
 * {@code PatientAccountRegistrationService} creates a newborn account, and
 * only {@code UserServiceImpl} builds the newborn grant. No allowlist.
 */
@DisplayName("Every recorded live birth registers its babies as patients")
class NewbornRegistrationGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:private|public|protected|static)[^;{=(]*+\\(((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+)\\)\\s*+(?:throws [\\w., ]++)?+\\{");
    private static final Pattern REPO_FIELD = Pattern.compile("DeliveryRecordRepository\\s++(\\w++)\\s*+[;=,)]");
    /** The direct call, or the after-commit helper that makes it in its own transaction. */
    private static final Pattern REGISTER =
        Pattern.compile("\\bnewbornRegistration\\.registerLiveBorn\\(|\\bregisterNewbornsAfterCommit\\(");
    private static final Path LABOR = MAIN.resolve("service/impl/LaborServiceImpl.java");

    /** Call -> the only class allowed to make it. */
    private static final Map<Pattern, String> ONLY_CALLER = Map.of(
        Pattern.compile("\\.registerNewborn\\("), "service/maternity/NewbornRegistrationService.java",
        Pattern.compile("\\.createNewbornPatientAccount\\("), "service/PatientAccountRegistrationService.java",
        Pattern.compile("\\.createNewbornPatient\\("), "service/PatientAccountRegistrationService.java",
        Pattern.compile("\\bGrant\\.newbornPatientAt\\("), "service/UserServiceImpl.java",
        Pattern.compile("\\bdeliveryNewbornRepository\\.save(?:All|AndFlush)?+\\("),
        "service/maternity/NewbornRegistrationService.java");

    @Test
    @DisplayName("every method that saves a delivery registers its live-born babies")
    void everyDeliveryWriteRegistersTheBabies() throws IOException {
        List<String> writers = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        Map<String, Set<String>> strayCallers = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                String cls = file.getFileName().toString().replace(".java", "");
                writers.addAll(scan(cls, source, false));
                offenders.addAll(scan(cls, source, true));
                ONLY_CALLER.forEach((call, owner) -> {
                    if (call.matcher(source).find() && !rel.equals(owner)) {
                        strayCallers.computeIfAbsent(call.pattern(), k -> new TreeSet<>()).add(rel);
                    }
                });
            }
        }
        assertThat(writers).as("the delivery write paths found").contains("LaborServiceImpl#recordDelivery");
        assertThat(offenders)
            .as("a method saves a delivery without registering its live-born babies (QA D1592: the baby had no "
                + "patient record, no MRN and no link to the mother): call newbornRegistration.registerLiveBorn(saved, "
                + "recorderUserId) after the save" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(strayCallers)
            .as("the newborn registration bypass is called from outside its one owner" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("a failed registration never undoes the birth: it runs after commit, isolated, and leaves the flag")
    void theRegistrationIsIsolatedFromTheDelivery() throws IOException {
        String labor = SourceScan.stripComments(Files.readString(LABOR, StandardCharsets.UTF_8));
        assertThat(labor).contains("public void afterCommit()")
            .containsPattern("try \\{\\s*+response\\.setNewborns\\(newbornRegistration\\.registerLiveBorn\\(")
            .contains("newbornRegistration.recordRegistrationFailure(");
        String service = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/maternity/NewbornRegistrationService.java"), StandardCharsets.UTF_8));
        assertThat(service).contains("Propagation.REQUIRES_NEW");
    }

    @Test
    @DisplayName("the scan catches the round-44 shape")
    void catchesTheOldShape() {
        String before = """
            class LaborServiceImpl {
                private final DeliveryRecordRepository deliveryRecordRepository;

                public DeliveryRecordResponseDTO recordDelivery(UUID patientId, UUID episodeId, UUID recorderUserId,
                                                                DeliveryRecordRequestDTO request) {
                    DeliveryRecord saved = deliveryRecordRepository.save(record);
                    chargeDelivery(saved, recorderUserId);
                    return laborMapper.toDeliveryResponse(saved);
                }
            }
            """;
        assertThat(scan("LaborServiceImpl", before, true)).containsExactly("LaborServiceImpl#recordDelivery");
        String after = before.replace("chargeDelivery(saved, recorderUserId);",
            "newbornRegistration.registerLiveBorn(saved, recorderUserId);");
        assertThat(scan("LaborServiceImpl", after, true)).isEmpty();
    }

    private static List<String> scan(String cls, String source, boolean offendersOnly) {
        List<String> out = new ArrayList<>();
        Pattern save = savePattern(source);
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
            if (!offendersOnly || !REGISTER.matcher(body).find()) {
                out.add(cls + "#" + CancelledOrderLockGuardTest.methodName(source.substring(m.start(), m.start(1))));
            }
        }
        return out;
    }

    /** A save through a DeliveryRecordRepository field of this source, or null when it has none. */
    private static Pattern savePattern(String source) {
        List<String> alternatives = new ArrayList<>();
        Matcher repo = REPO_FIELD.matcher(source);
        while (repo.find()) {
            alternatives.add("\\b" + repo.group(1) + "\\.save(?:All|AndFlush)?+\\(");
        }
        return alternatives.isEmpty() ? null : Pattern.compile(String.join("|", alternatives));
    }
}
