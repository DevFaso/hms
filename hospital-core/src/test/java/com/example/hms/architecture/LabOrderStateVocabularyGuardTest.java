package com.example.hms.architecture;

import com.example.hms.enums.LabOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab requests: the portal reads every lab order status through ONE state
 * vocabulary ({@code lab/shared/lab-order-state.ts}). LAB-000015 read
 * "Terminé" and LAB-000031 "Résultats rendus", and the "Terminé" tab counted
 * RESULTED, an entered but unvalidated result, as finished.
 *
 * <p>The class: a new {@link LabOrderStatus} constant the portal does not
 * map shows raw, and a status before the last release
 * ({@code LabOrderLifecycle.statusAfterAllResultsReleased}) that maps to
 * COMPLETED tells the clinician the results are back when they are not.
 */
@DisplayName("Every lab order status has one portal state, and only COMPLETED reads finished")
class LabOrderStateVocabularyGuardTest {

    private static final Path STATE_TS = Path.of("../hospital-portal/src/app/lab/shared/lab-order-state.ts");
    private static final Pattern ENTRY = Pattern.compile("^\\s*+([A-Z_]++):\\s*+'([A-Z_]++)',", Pattern.MULTILINE);

    @Test
    @DisplayName("each backend status is mapped, and none before the release maps to COMPLETED")
    void everyStatusHasOneStateAndOnlyCompletedIsFinished() throws IOException {
        String source = Files.readString(STATE_TS, StandardCharsets.UTF_8);
        int start = source.indexOf("const STATE_OF");
        int end = source.indexOf("};", start);
        assertThat(start).as("the STATE_OF map is in lab-order-state.ts").isNotNegative();
        String map = source.substring(start, end);

        java.util.Map<String, String> stateOf = new java.util.HashMap<>();
        Matcher m = ENTRY.matcher(map);
        while (m.find()) {
            stateOf.put(m.group(1), m.group(2));
        }
        List<String> statuses = Arrays.stream(LabOrderStatus.values()).map(Enum::name).toList();
        assertThat(stateOf.keySet()).as("every LabOrderStatus has a portal state").containsAll(statuses);
        for (String status : statuses) {
            boolean finished = "COMPLETED".equals(stateOf.get(status));
            assertThat(finished).as(status + " reads finished").isEqualTo(status.equals("COMPLETED"));
        }
        assertThat(stateOf).containsEntry("RESULTED", "AWAITING_VALIDATION")
            .containsEntry("VERIFIED", "AWAITING_VALIDATION");
    }
}
