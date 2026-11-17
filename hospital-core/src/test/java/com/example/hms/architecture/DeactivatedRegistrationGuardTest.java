package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1476: the pickers hide a patient whose registration was deactivated, and the API booked,
 * walked in and waitlisted her all the same. Each way of opening a visit at the desk asks
 * {@code PatientRegistrationGuard.refuseDeactivatedRegistration}. A new desk path that takes a
 * patient id and opens a visit is added here.
 */
class DeactivatedRegistrationGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms/service");
    /** The call a path answers with: the desk's guard, or the portal's own booking check over active registrations. */
    private static final String DESK_CHECK = "refuseDeactivatedRegistration";
    private static final String PORTAL_CHECK = "requireBookingRegistration";
    private static final String PORTAL_MOVE_CHECK = "refuseDeactivatedRegistration";

    /** {file, method, the check its body must carry}. QA D1489/D1495 added the check-in and the patient's own paths. */
    private static final List<String[]> VISIT_OPENERS = List.of(
        new String[] {"AppointmentServiceImpl.java", "public AppointmentSummaryDTO createAppointment(", DESK_CHECK},
        new String[] {"EncounterServiceImpl.java", "public EncounterResponseDTO createEncounter(", DESK_CHECK},
        new String[] {"ReceptionServiceImpl.java", "public WaitlistEntryResponseDTO addToWaitlist(", DESK_CHECK},
        new String[] {"ReceptionServiceImpl.java", "public CheckInResponseDTO checkInPatient(", DESK_CHECK},
        new String[] {"impl/PatientPortalServiceImpl.java", "public AppointmentResponseDTO scheduleMyAppointment(", PORTAL_CHECK},
        new String[] {"impl/PatientPortalServiceImpl.java", "public AppointmentResponseDTO rescheduleMyAppointment(", PORTAL_MOVE_CHECK},
        new String[] {"impl/PatientPortalServiceImpl.java", "public PreCheckInResponseDTO submitPreCheckIn(", PORTAL_MOVE_CHECK});

    @Test
    void everyDeskVisitOpenerRefusesADeactivatedRegistration() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String[] opener : VISIT_OPENERS) {
            String source = Files.readString(MAIN.resolve(opener[0]), StandardCharsets.UTF_8);
            int start = source.indexOf(opener[1]);
            assertThat(start).as(opener[1]).isGreaterThanOrEqualTo(0);
            int next = source.indexOf("\n    public ", start + 1);
            String body = source.substring(start, next < 0 ? source.length() : next);
            if (!body.contains(opener[2])) {
                offenders.add(opener[0] + " " + opener[1]);
            }
        }
        assertThat(offenders).as("opens a visit on a deactivated registration (QA D1476)").isEmpty();
    }

    /** QA D1489: the refusal comes before the consent call, whose failure inside the transaction answered 500. */
    @Test
    void theCheckInRefusesBeforeItsConsentCall() throws IOException {
        String source = Files.readString(MAIN.resolve("ReceptionServiceImpl.java"), StandardCharsets.UTF_8);
        int start = source.indexOf("public CheckInResponseDTO checkInPatient(");
        int guard = source.indexOf(DESK_CHECK, start);
        int consent = source.indexOf("treatmentConsentService.record(", start);
        assertThat(guard).as("the guard").isPositive().isLessThan(consent);
        assertThat(source.lastIndexOf("try {", guard)).as("no catch around the guard").isLessThan(start);
    }
}
