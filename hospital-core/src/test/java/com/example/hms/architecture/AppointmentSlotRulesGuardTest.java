package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D937, D977, D978 guard: every booking path decides overlaps and patient
 * booking times with {@code AppointmentSlotRules}, so a cancelled booking can
 * never block a slot again on one path while another is fixed.
 */
class AppointmentSlotRulesGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /**
     * Whether {@code src} holds the hand-written interval-overlap idiom, in its
     * two spellings and with any argument ({@code a.isBefore(x) && y.isBefore(b)},
     * {@code !a.isAfter(LocalDateTime.of(d, t)) && !b.isAfter(c)}): an
     * isBefore/isAfter call, then in the same statement a {@code )} followed by
     * {@code &&}, then (after an optional {@code !}) a receiver chain that makes
     * another isBefore/isAfter call. A linear scan, not a backtracking regex.
     */
    static boolean hasOverlapIdiom(String src) {
        for (int i = nextComparison(src, 0); i >= 0; i = nextComparison(src, i + 1)) {
            int end = src.indexOf(';', i);
            if (end < 0) {
                end = src.length();
            }
            for (int j = src.indexOf("&&", i); j >= 0 && j < end; j = src.indexOf("&&", j + 2)) {
                if (closesACallBefore(src, i, j) && opensAComparison(src, j + 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int nextComparison(String src, int from) {
        int before = src.indexOf(".isBefore(", from);
        int after = src.indexOf(".isAfter(", from);
        if (before < 0) {
            return after;
        }
        return after < 0 ? before : Math.min(before, after);
    }

    /** The last non-blank character before {@code and} (and after {@code start}) is a ')'. */
    private static boolean closesACallBefore(String src, int start, int and) {
        int k = and - 1;
        while (k > start && Character.isWhitespace(src.charAt(k))) {
            k--;
        }
        return k > start && src.charAt(k) == ')';
    }

    /** After blanks and an optional '!', a run of [\\w.()] that contains an isBefore/isAfter call. */
    private static boolean opensAComparison(String src, int from) {
        int k = skipBlanks(src, from);
        if (k < src.length() && src.charAt(k) == '!') {
            k = skipBlanks(src, k + 1);
        }
        int start = k;
        while (k < src.length() && isReceiverChar(src.charAt(k))) {
            k++;
        }
        String run = src.substring(start, k);
        return run.contains(".isBefore(") || run.contains(".isAfter(");
    }

    private static int skipBlanks(String src, int from) {
        int k = from;
        while (k < src.length() && Character.isWhitespace(src.charAt(k))) {
            k++;
        }
        return k;
    }

    private static boolean isReceiverChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || c == '_' || c == '.' || c == '(' || c == ')';
    }

    @Test
    void noAppointmentOverlapIsHandWrittenOutsideTheRules() throws IOException {
        for (Path f : javaFiles()) {
            String src = read(f);
            if (f.endsWith("AppointmentSlotRules.java") || !src.contains("Appointment")) {
                continue;
            }
            assertThat(hasOverlapIdiom(src))
                .as(f + " decides an appointment overlap by hand; use AppointmentSlotRules.overlapsHeldSlot")
                .isFalse();
        }
    }

    @Test
    void everyBookingPathUsesTheRules() {
        String staff = read(MAIN.resolve("service/AppointmentServiceImpl.java"));
        assertThat(count(staff, "AppointmentSlotRules.overlapsHeldSlot(")).as("staff create + edit")
            .isGreaterThanOrEqualTo(2);

        String portal = read(MAIN.resolve("service/impl/PatientPortalServiceImpl.java"));
        assertThat(count(portal, "AppointmentSlotRules.overlapsHeldSlot(")).as("portal book + reschedule")
            .isGreaterThanOrEqualTo(2);
        assertThat(count(portal, "AppointmentSlotRules.requirePatientBookable(")).as("portal book + reschedule")
            .isGreaterThanOrEqualTo(2);
        // QA D977: a moved appointment is booked at its new time, never parked in RESCHEDULED.
        assertThat(portal).doesNotContain("appointment.setStatus(AppointmentStatus.RESCHEDULED)");

        // The check-out follow-up placeholder books a time too (review round 4).
        String encounters = read(MAIN.resolve("service/EncounterServiceImpl.java"));
        assertThat(count(encounters, "AppointmentSlotRules.overlapsHeldSlot(")).as("check-out follow-up placeholder")
            .isGreaterThanOrEqualTo(1);
    }

    /**
     * QA D1013 (class guard): every method that gives a clinician's time to a
     * patient - builds a new appointment, or moves one to another date or
     * clinician - checks in its own body that the clinician is free, through
     * the shared rule. The slot inventory's {@code book} (an accepted waitlist
     * offer) and the referral's appointment had no check, and a clinician got
     * two patients at 09:20. The mappers only copy fields (their callers are
     * checked), the dev seeder writes fixtures, and the FHIR mapper builds
     * HAPI's own {@code Appointment}.
     */
    @Test
    void everyMethodThatBooksAClinicianChecksTheyAreFree() throws IOException {
        List<String> unchecked = new java.util.ArrayList<>();
        int seen = 0;
        for (Path f : javaFiles()) {
            String rel = MAIN.relativize(f).toString().replace('\\', '/');
            if (rel.startsWith("mapper/") || rel.startsWith("fhir/") || rel.startsWith("model/")
                    || rel.contains("/dto/") || rel.equals("bootstrap/DevSyntheticDataSeeder.java")) {
                continue;
            }
            String src = com.example.hms.testsupport.SourceScan.stripComments(read(f));
            List<String> checks = checksIn(src);
            for (String token : BOOKING_WRITES) {
                for (int at = src.indexOf(token); at >= 0; at = src.indexOf(token, at + 1)) {
                    if (token.equals(".setAppointmentDate(") && !rel.startsWith("service/")) {
                        continue;
                    }
                    seen++;
                    String body = enclosingMethod(src, at);
                    if (checks.stream().noneMatch(body::contains)) {
                        unchecked.add(rel + ":" + com.example.hms.testsupport.SourceScan.lineOf(src, at) + " " + token);
                    }
                }
            }
        }
        assertThat(seen).as("booking writes found").isGreaterThanOrEqualTo(7);
        assertThat(unchecked)
            .as("a method gives a clinician's time without checking they are free (QA D1013): "
                + "call AppointmentSlotRules.overlapsHeldSlot in it")
            .isEmpty();
    }

    @Test
    void theBookingScanSeesAnUncheckedBuilder() {
        String src = """
            class X {
                public Appointment book(UUID id) {
                    return repo.save(Appointment.builder().build());
                }
            }
            """;
        String body = enclosingMethod(src, src.indexOf("Appointment.builder()"));
        assertThat(body).contains("public Appointment book");
        assertThat(CLINICIAN_FREE_CHECKS.stream().noneMatch(body::contains)).isTrue();
    }

    private static final List<String> BOOKING_WRITES = List.of(
        "Appointment.builder()", "new Appointment()", ".setAppointmentDate(", "updateAppointmentFromDto(");

    private static final List<String> CLINICIAN_FREE_CHECKS = List.of("AppointmentSlotRules.overlapsHeldSlot(");

    /**
     * The shared rule, plus every method of the same file whose own body calls
     * it (a helper such as {@code requireClinicianFree} or
     * {@code freeFollowUpSlot}): calling one of those is the check too.
     */
    static List<String> checksIn(String src) {
        List<String> out = new java.util.ArrayList<>(CLINICIAN_FREE_CHECKS);
        java.util.Map<String, String> bodies = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher header = java.util.regex.Pattern.compile("(?m)^    [a-zA-Z][^\\n(]*\\(").matcher(src);
        while (header.find()) {
            int open = com.example.hms.testsupport.SourceScan.indexOfOutsideLiterals(src, '{', header.end());
            int semi = com.example.hms.testsupport.SourceScan.indexOfOutsideLiterals(src, ';', header.end());
            if (open < 0 || (semi >= 0 && semi < open)) {
                continue;
            }
            String head = src.substring(header.start(), header.end() - 1).trim();
            bodies.merge(head.substring(head.lastIndexOf(' ') + 1) + "(",
                src.substring(open, com.example.hms.testsupport.SourceScan.closingBrace(src, open)), String::concat);
        }
        // To a fixpoint: a helper that calls a helper that calls the rule is a check too.
        boolean grew = true;
        while (grew) {
            grew = false;
            for (java.util.Map.Entry<String, String> m : bodies.entrySet()) {
                if (!out.contains(m.getKey()) && out.stream().anyMatch(m.getValue()::contains)) {
                    out.add(m.getKey());
                    grew = true;
                }
            }
        }
        return out;
    }

    /** The body of the method (4-space indented member) around {@code index}. */
    static String enclosingMethod(String src, int index) {
        java.util.regex.Matcher header = java.util.regex.Pattern.compile(
            "(?m)^    [a-zA-Z][^\\n(]*\\(")
            .matcher(src);
        int bestStart = -1;
        int bestEnd = -1;
        while (header.find() && header.start() < index) {
            int open = com.example.hms.testsupport.SourceScan.indexOfOutsideLiterals(src, '{', header.end());
            int semi = com.example.hms.testsupport.SourceScan.indexOfOutsideLiterals(src, ';', header.end());
            if (open < 0 || (semi >= 0 && semi < open)) {
                continue;
            }
            int close = com.example.hms.testsupport.SourceScan.closingBrace(src, open);
            if (close > index) {
                bestStart = header.start();
                bestEnd = close;
            }
        }
        assertThat(bestStart).as("no method encloses offset %d", index).isNotNegative();
        return src.substring(bestStart, bestEnd);
    }

    @Test
    void everyPatientBookingCheckReadsTheHospitalsClock() throws IOException {
        // QA D978 (review round 8): "in the past" is judged at the hospital,
        // never on the server's zone, wherever requirePatientBookable is called.
        int calls = 0;
        for (Path f : javaFiles()) {
            if (f.endsWith("AppointmentSlotRules.java")) {
                continue;
            }
            String src = read(f);
            for (int at = src.indexOf("requirePatientBookable("); at >= 0;
                    at = src.indexOf("requirePatientBookable(", at + 1)) {
                String call = src.substring(at, src.indexOf(';', at));
                assertThat(call).as(f + ": " + call).doesNotContain("systemDefault").doesNotContain("now()")
                    .contains("nowAt(");
                calls++;
            }
        }
        assertThat(calls).as("portal book + reschedule").isGreaterThanOrEqualTo(2);
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
