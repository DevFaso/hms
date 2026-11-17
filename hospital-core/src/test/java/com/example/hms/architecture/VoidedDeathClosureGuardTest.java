package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1516 (class: a record a death closed that stays marked as the death's outcome once the death is entered in
 * error, with nothing naming it to a clinician). The death's cascade remembers every record it closes; entered in
 * error flags each one and files the review; and every reader that decides on a stay a death closed decides on a stay
 * a voided death closed too, so no census, bed or access rule treats one as the other by omission.
 */
@DisplayName("A death entered in error leaves nothing it closed marked as a death, and says what it closed")
class VoidedDeathClosureGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path MORTALITY = MAIN.resolve("service/impl/MortalityServiceImpl.java");
    private static final Pattern STATUS_WRITE = Pattern.compile("\\.setStatus\\((AdmissionStatus|EncounterStatus|AppointmentStatus)\\.");

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String code(Path file) throws IOException {
        return SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static String name(Path file) {
        return MAIN.relativize(file).toString().replace('\\', '/');
    }

    /** Only the death's cascade writes DECEASED onto a stay, so it is the one place that must remember it. */
    @Test
    void onlyTheDeathsCascadeMarksAStayDeceased() throws IOException {
        Set<String> writers = new TreeSet<>();
        for (Path source : javaSources()) {
            if (code(source).contains("setStatus(AdmissionStatus.DECEASED)")) {
                writers.add(name(source));
            }
        }
        assertThat(writers).containsExactly("service/impl/MortalityServiceImpl.java");
    }

    /** Each record the cascade closes (stay, visit, appointment) is kept on the death record by id. */
    @Test
    void theCascadeRemembersEveryRecordItCloses() throws IOException {
        String code = code(MORTALITY);
        String cascade = between(code, "private DeathClosureSummaryDTO closeOpenRecords(", "private int closeRecalls(");
        Matcher writes = STATUS_WRITE.matcher(cascade);
        int statusWrites = 0;
        while (writes.find()) {
            statusWrites++;
        }
        assertThat(statusWrites).as("status writes in the cascade").isEqualTo(3);
        assertThat(count(cascade, "closedIds.add(")).as("one remembered id per status write").isEqualTo(statusWrites);
        assertThat(cascade).contains("deathRecord.setClosedRecords(closed);");
    }

    /** Entered in error settles what the death closed and files the review, after the record is marked. */
    @Test
    void enteredInErrorFlagsWhatTheDeathClosedAndFilesTheReview() throws IOException {
        String body = between(code(MORTALITY), "public DeathRecordResponseDTO markEnteredInError(", "record VoidedClosures(");
        assertThat(body)
            .contains("VoidedClosures closures = settleWhatTheDeathClosed(saved);")
            .contains("fileClosureReview(saved, patient, closures);")
            .doesNotContain("AdmissionStatus.ACTIVE");
        String settle = between(code(MORTALITY), "private VoidedClosures settleWhatTheDeathClosed(", "private static java.util.Optional<List<UUID>> recordedIds(");
        assertThat(settle).contains("stay.setStatus(AdmissionStatus.CLOSED_IN_ERROR);")
            .contains("stay.setActualDischargeDateTime(closedAt);");
        String filing = between(code(MORTALITY), "private void fileClosureReview(", "private static String listOf(");
        // After commit, in its own transaction, and a failure is caught: it never undoes the entered-in-error.
        assertThat(filing).contains("TransactionCallbacks.afterCommit(").contains("catch (RuntimeException ex)");
    }

    /**
     * Every file that decides on a stay a death closed (DECEASED) decides on one a voided death closed
     * (CLOSED_IN_ERROR): the closed-stay checks, the MLLP projection, and the cascade itself.
     */
    @Test
    void everyReaderOfADeathClosedStayReadsAVoidedOneToo() throws IOException {
        Set<String> missing = new TreeSet<>();
        for (Path source : javaSources()) {
            String code = code(source);
            if (code.contains("AdmissionStatus.DECEASED") && !code.contains("AdmissionStatus.CLOSED_IN_ERROR")) {
                missing.add(name(source));
            }
        }
        assertThat(missing).isEmpty();
    }

    /**
     * The sets of ended stays a discharge belongs to take a voided closure too (orders end with it, its clinicians
     * keep the chart); the discharge counts do not (it is no discharge): the dashboard's discharged-today and the
     * average length of stay read DISCHARGED alone.
     */
    @Test
    void theEndedStaySetsTakeItAndTheDischargeCountsDoNot() throws IOException {
        assertThat(code(MAIN.resolve("service/InpatientOrderEnd.java"))).contains("AdmissionStatus.CLOSED_IN_ERROR");
        assertThat(code(MAIN.resolve("service/recordaccess/TreatmentRelationshipResolverImpl.java")))
            .contains("AdmissionStatus.CLOSED_IN_ERROR");
        assertThat(code(MAIN.resolve("service/impl/HospitalAdminDashboardServiceImpl.java"))).doesNotContain("CLOSED_IN_ERROR");
        assertThat(code(MAIN.resolve("repository/AdmissionRepository.java"))).doesNotContain("CLOSED_IN_ERROR");
    }

    private static String between(String code, String from, String to) {
        int start = code.indexOf(from);
        int end = code.indexOf(to, start + 1);
        assertThat(start).as(from).isNotNegative();
        assertThat(end).as(to).isGreaterThan(start);
        return code.substring(start, end);
    }

    private static int count(String code, String token) {
        int n = 0;
        for (int at = code.indexOf(token); at >= 0; at = code.indexOf(token, at + token.length())) {
            n++;
        }
        return n;
    }
}
