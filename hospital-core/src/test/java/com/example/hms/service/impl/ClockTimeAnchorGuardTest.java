package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Prescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE GUARD of QA D1514 (round 37): a clock-time wording never yields a schedule anchored on the signing moment.
 *
 * <p>Every wording of the shared frequency fixture ({@code as-needed-frequency.cases.json}: every QA script's
 * wordings, every filed wording, every form of the grammar, the repo's seeds) that writes clock times and no
 * interval, count or course is found here by a pattern of its own, not by the reader. Each one the reader
 * accepts is signed at moments across the day (just after a stated time, across midnight) and walked on the MAR
 * dose after dose; each due time must be the next time the wording writes, in order. A reading that spreads the day
 * evenly from the signing moment (the D1514 fault: "7 h 13 h 19 h" every 8 h from 00:01, "a 9 h" at 00:01)
 * fails it. A wording the reader asks about is never timed at all, so it passes: asking is the other lawful
 * answer.
 */
class ClockTimeAnchorGuardTest {

    /** A clock time as written: "8 h", "8h30", "08:00", "8 am", "a las 8"; groups: hour, minutes, minutes, mark, hour. */
    private static final Pattern CLOCK = Pattern.compile(
        "(?<![A-Z0-9])(\\d{1,2}) ?(?:H(\\d{2})?|:(\\d{2})|(AM|PM))(?![A-Z0-9])|(?<![A-Z])LAS (\\d{1,2})(?![\\d:])");

    /** What makes a number an interval, a count, a course or a duration rather than a clock time. */
    private static final Pattern NOT_ONLY_CLOCK = Pattern.compile(
        "(?<![A-Z])(?:TOUTES?|TOUS|TTES?|TT|EVERY|CADA|Q|C|FOIS|TIMES?|VECES|VEZ|X|PAR|PER|POR|EN|PENDANT|DURANT|"
            + "APRES|AFTER|AVANT|BEFORE|ANTES|DANS|DE|ENTRE|FROM|TO|OU|OR|DOSE|DOSIS|DEMAIN|CE|TONIGHT|ONCE)(?![A-Z])"
            // A course entry ("H0", "J1"); the H behind a number ("7 H 13") is that number's.
            + "|/|-|(?<![A-Z0-9])(?<!\\d )[HJ] ?\\d");

    private static final List<LocalTime> SIGNED_AT = List.of(LocalTime.of(0, 1), LocalTime.of(3, 17), LocalTime.of(7, 5),
        LocalTime.of(8, 1), LocalTime.of(10, 41), LocalTime.of(14, 1), LocalTime.of(20, 1), LocalTime.of(23, 59));

    private static final int DOSES_WALKED = 6;

    @Test
    void noClockTimeWordingIsTimedFromTheSigningMoment() throws IOException {
        Map<String, Set<LocalTime>> clockWordings = clockWordingsOfTheFixture();
        assertThat(clockWordings).as("the fixture's clock-time wordings").hasSizeGreaterThan(30);
        Map<String, String> anchored = new TreeMap<>();
        int walked = 0;
        for (Map.Entry<String, Set<LocalTime>> wording : clockWordings.entrySet()) {
            MarDoseSchedule.Reading reading = MarDoseSchedule.read(wording.getKey());
            if (reading.kind() == MarDoseSchedule.Kind.UNREAD) {
                continue;
            }
            walked++;
            if (reading.kind() != MarDoseSchedule.Kind.TIMES_OF_DAY) {
                anchored.put(wording.getKey(), "read as " + reading.kind() + ", not as times of day");
                continue;
            }
            for (LocalTime signed : SIGNED_AT) {
                String wrong = firstDueOffItsTimes(wording.getKey(), wording.getValue(), signed);
                if (wrong != null) {
                    anchored.put(wording.getKey(), wrong);
                }
            }
        }
        assertThat(walked).as("the clock-time wordings the reader accepts").isGreaterThan(20);
        assertThat(anchored).as("a clock-time wording timed from the signing moment (QA D1514)").isEmpty();
    }

    /** The guard's own pattern finds what it should, and leaves out intervals, counts and courses. */
    @Test
    void thePatternTellsAClockTimeFromAnInterval() {
        assertThat(clockTimes("7 h 13 h 19 h")).containsExactly(LocalTime.of(7, 0), LocalTime.of(13, 0), LocalTime.of(19, 0));
        assertThat(clockTimes("à 8h30")).containsExactly(LocalTime.of(8, 30));
        assertThat(clockTimes("at 8am and 8pm")).containsExactly(LocalTime.of(8, 0), LocalTime.of(20, 0));
        assertThat(clockTimes("a las 8 y a las 20")).containsExactly(LocalTime.of(8, 0), LocalTime.of(20, 0));
        for (String notOnlyClock : List.of("toutes les 8 h", "q8h", "6h/6h", "3 fois par 24 h", "H0 H8 H24", "J1 J3",
            "48 h après chirurgie", "8 h - 20 h", "de 8h à 20h", "dose unique à 8 h", "every 8 hours")) {
            assertThat(clockTimes(notOnlyClock)).as(notOnlyClock).isEmpty();
        }
    }

    /** The guard's own walk over the written times: the first at (or after) {@code from}, today or tomorrow. */
    private static LocalDateTime nextWritten(Set<LocalTime> written, LocalDateTime from, boolean orAt) {
        for (int days = 0; days <= 1; days++) {
            for (LocalTime time : written) {
                LocalDateTime candidate = from.toLocalDate().plusDays(days).atTime(time);
                if (candidate.isAfter(from) || (orAt && candidate.equals(from))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String firstDueOffItsTimes(String wording, Set<LocalTime> written, LocalTime signed) {
        LocalDateTime signedAt = LocalDate.of(2026, 11, 7).atTime(signed);
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setFrequency(wording);
        rx.setSignedAt(signedAt);
        List<MedicationAdministrationRecord> rows = new ArrayList<>();
        LocalDateTime now = signedAt;
        // The written times in order, the first at or after the signature: none skipped, none repeated.
        LocalDateTime expected = nextWritten(written, signedAt, true);
        for (int dose = 1; dose <= DOSES_WALKED; dose++) {
            MarNextDose.Due due = MarNextDose.of(rx, now, rows, NurseTaskServiceImpl.EARLY_ADMINISTRATION_WINDOW);
            if (due.dueTime() == null || !due.dueTime().equals(expected) || !expected.equals(due.scheduledSlot())) {
                return "signed at " + signed + ", dose " + dose + " due at " + due.dueTime() + ", not at " + expected
                    + " (written " + written + ")";
            }
            expected = nextWritten(written, expected, false);
            MedicationAdministrationRecord given = new MedicationAdministrationRecord();
            given.setId(UUID.randomUUID());
            given.setStatus(MedicationAdministrationStatus.GIVEN);
            given.setScheduledTime(due.dueTime());
            given.setAdministeredAt(due.dueTime());
            rows.add(0, given);
            now = due.dueTime();
        }
        return null;
    }

    private static Map<String, Set<LocalTime>> clockWordingsOfTheFixture() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(MarDoseScheduleTest.AS_NEEDED_CASES));
        List<String> all = new ArrayList<>();
        for (String list : List.of("fixedSchedule", "unreadable", "conditional", "dropdown")) {
            root.get(list).forEach(node -> all.add(node.asText()));
        }
        root.get("grammarForms").fieldNames().forEachRemaining(all::add);
        root.get("readings").fieldNames().forEachRemaining(all::add);
        root.get("filed").forEach(defect -> defect.forEach(node -> all.add(node.asText())));
        Map<String, Set<LocalTime>> out = new TreeMap<>();
        for (String wording : all) {
            Set<LocalTime> times = clockTimes(wording);
            if (!times.isEmpty()) {
                out.put(wording, times);
            }
        }
        return out;
    }

    /** The clock times a wording writes, or none when it writes no clock time or also an interval, count or course. */
    private static Set<LocalTime> clockTimes(String wording) {
        String upper = MarDoseSchedule.accentlessUpper(wording);
        Set<LocalTime> times = new TreeSet<>();
        if (NOT_ONLY_CLOCK.matcher(upper).find()) {
            return times;
        }
        Matcher clock = CLOCK.matcher(upper);
        while (clock.find()) {
            LocalTime time = timeOf(clock);
            if (time != null) {
                times.add(time);
            }
        }
        return times;
    }

    private static LocalTime timeOf(Matcher clock) {
        int hour = Integer.parseInt(clock.group(1) != null ? clock.group(1) : clock.group(5));
        String minutesWritten = clock.group(2) != null ? clock.group(2) : clock.group(3);
        int minutes = minutesWritten == null ? 0 : Integer.parseInt(minutesWritten);
        String mark = clock.group(4);
        if ("PM".equals(mark) && hour < 12) {
            hour += 12;
        } else if ("AM".equals(mark) && hour == 12) {
            hour = 0;
        }
        if (hour > 24 || minutes > 59 || (hour == 24 && minutes > 0)) {
            return null;
        }
        return LocalTime.of(hour % 24, minutes);
    }
}
