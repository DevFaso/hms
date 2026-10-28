package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D937, D977, D978 guard: every booking path decides overlaps and patient
 * booking times with {@code AppointmentSlotRules}, so a cancelled booking can
 * never block a slot again on one path while another is fixed.
 */
class AppointmentSlotRulesGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** The hand-written interval-overlap idiom: {@code a.isBefore(x) && y.isBefore(b)}. */
    private static final Pattern OVERLAP_IDIOM = Pattern.compile("\\.isBefore\\(\\w+\\)\\s*&&\\s*\\w+\\.isBefore\\(");

    @Test
    void noAppointmentOverlapIsHandWrittenOutsideTheRules() throws IOException {
        for (Path f : javaFiles()) {
            String src = read(f);
            if (f.endsWith("AppointmentSlotRules.java") || !src.contains("Appointment")) {
                continue;
            }
            assertThat(OVERLAP_IDIOM.matcher(src).find())
                .as(f + " decides an appointment overlap by hand; use AppointmentSlotRules.overlapsHeldSlot")
                .isFalse();
        }
    }

    @Test
    void everyBookingPathUsesTheRules() {
        String staff = read(MAIN.resolve("service/AppointmentServiceImpl.java"));
        assertThat(count(staff, "AppointmentSlotRules.overlapsHeldSlot(")).as("staff create + edit").isEqualTo(2);

        String portal = read(MAIN.resolve("service/impl/PatientPortalServiceImpl.java"));
        assertThat(count(portal, "AppointmentSlotRules.overlapsHeldSlot(")).as("portal book + reschedule").isEqualTo(2);
        assertThat(count(portal, "AppointmentSlotRules.requirePatientBookable(")).as("portal book + reschedule")
            .isEqualTo(2);
        // QA D977: a moved appointment is booked at its new time, never parked in RESCHEDULED.
        assertThat(portal).doesNotContain("appointment.setStatus(AppointmentStatus.RESCHEDULED)");
    }

    private static int count(String src, String needle) {
        int n = 0;
        for (int at = src.indexOf(needle); at >= 0; at = src.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
