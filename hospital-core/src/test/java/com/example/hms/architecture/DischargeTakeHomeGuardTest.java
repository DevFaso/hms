package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2553 (round 65), the class: the take-home flag had an effect on one path only (a discharge
 * that wrote the summary itself), so a summary written first, or a take-home order signed after it,
 * left the medicines out. Which orders go home, and how they enter a summary, is decided in one
 * place, and every path that writes a stay's summary or signs an order goes through it.
 */
@DisplayName("Take-home medicines: one carrier, reached by every summary write and every signature")
class DischargeTakeHomeGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String CARRIER = "service/DischargeTakeHomeMedicines.java";

    private static String code(Path file) throws IOException {
        return SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void theStaySummaryWritersDoNotDecideWhatGoesHomeThemselves() throws IOException {
        for (String writer : new String[] {"service/AdmissionStayEncounterService.java",
                "service/DischargeSummaryServiceImpl.java"}) {
            assertThat(code(MAIN.resolve(writer))).as(writer).doesNotContain("isTakeHome");
        }
        assertThat(code(MAIN.resolve(CARRIER))).contains("isTakeHome");
    }

    @Test
    void everyStaySummaryWriteAndTheSignatureReachTheCarrier() throws IOException {
        assertThat(code(MAIN.resolve("service/AdmissionStayEncounterService.java")))
            .as("the discharge, whether it writes the summary or finds one written")
            .contains("takeHomeMedicines.carryInto(");
        assertThat(code(MAIN.resolve("service/DischargeSummaryServiceImpl.java")))
            .as("a summary created on the discharge screen")
            .contains("takeHomeMedicines.carryInto(");
        assertThat(code(MAIN.resolve("service/PrescriptionServiceImpl.java")))
            .as("a take-home order signed after its stay's summary was written")
            .contains("takeHomeMedicines.onSigned(");
    }
    /**
     * Review of #1276: the list follows the orders both ways. Every path that withdraws a signed order
     * (the ones that write the withdrawal audit) also tells the carrier, so a stopped take-home order
     * leaves its stay's summary.
     */
    @Test
    void everySignedWithdrawalReachesTheCarrier() throws IOException {
        String src = code(MAIN.resolve("service/PrescriptionServiceImpl.java"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("auditSignedWithdrawalAfterCommit\\(saved").matcher(src);
        int withdrawals = 0;
        while (m.find()) {
            withdrawals++;
            assertThat(AppointmentSlotRulesGuardTest.enclosingMethod(src, m.start()))
                .as("a signed order's withdrawal that leaves its take-home entry on the summary")
                .contains("takeHomeLeavesTheSummary(");
        }
        assertThat(withdrawals).as("signed withdrawals found").isGreaterThanOrEqualTo(2);
        assertThat(src).contains("takeHomeMedicines.onWithdrawn(");
    }
}
