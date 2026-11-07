package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1476: the pickers hide a patient whose registration was deactivated, and the API booked,
 * walked in and waitlisted her all the same. Each way of opening a visit at the desk asks
 * {@code PatientRegistrationGuard.refuseDeactivatedRegistration}. A new desk path that takes a
 * patient id and opens a visit is added here.
 */
class DeactivatedRegistrationGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms/service");
    private static final Map<String, String> VISIT_OPENERS = Map.of(
        "AppointmentServiceImpl.java", "public AppointmentSummaryDTO createAppointment(",
        "EncounterServiceImpl.java", "public EncounterResponseDTO createEncounter(",
        "ReceptionServiceImpl.java", "public WaitlistEntryResponseDTO addToWaitlist(");

    @Test
    void everyDeskVisitOpenerRefusesADeactivatedRegistration() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (var entry : VISIT_OPENERS.entrySet()) {
            String source = Files.readString(MAIN.resolve(entry.getKey()), StandardCharsets.UTF_8);
            int start = source.indexOf(entry.getValue());
            assertThat(start).as(entry.getValue()).isGreaterThanOrEqualTo(0);
            int next = source.indexOf("\n    public ", start + 1);
            String body = source.substring(start, next < 0 ? source.length() : next);
            if (!body.contains("refuseDeactivatedRegistration")) {
                offenders.add(entry.getKey());
            }
        }
        assertThat(offenders).as("opens a visit on a deactivated registration (QA D1476)").isEmpty();
    }
}
