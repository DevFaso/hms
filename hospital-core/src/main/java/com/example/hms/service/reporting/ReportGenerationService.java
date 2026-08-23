package com.example.hms.service.reporting;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.ReportPeriod;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.model.platform.ReportDefinition;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.EncounterRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CSV generation for the canned scheduled reports (P3 #25a).
 *
 * <p><strong>Aggregate-only, by design.</strong> Every report is counts
 * per day — never patient rows, names, or MRNs — because the delivery
 * channel is an email attachment and email must never carry PHI (the
 * recall-SMS stance applied to a second untrusted channel).
 */
@Service
@RequiredArgsConstructor
public class ReportGenerationService {

    private static final int PAGE_SIZE = 500;
    private static final DateTimeFormatter MONTH_TOKEN = DateTimeFormatter.ofPattern("yyyyMM");

    private final EncounterRepository encounterRepository;
    private final AppointmentRepository appointmentRepository;

    /**
     * One generated attachment: bytes + the data-row count + a filename.
     * A plain class rather than a record because Sonar S6218 flags records
     * with array components (array equals/hashCode compare identity) — the
     * V126 PhotoPayload precedent. Accessor names keep the record shape so
     * call sites read the same.
     */
    public static final class GeneratedReport {
        private final byte[] content;
        private final int rowCount;
        private final String filename;

        public GeneratedReport(byte[] content, int rowCount, String filename) {
            this.content = content;
            this.rowCount = rowCount;
            this.filename = filename;
        }

        public byte[] content() {
            return content;
        }

        public int rowCount() {
            return rowCount;
        }

        public String filename() {
            return filename;
        }
    }

    /** Closed date range a period token denotes (both ends inclusive). */
    public record PeriodRange(LocalDate start, LocalDate end) { }

    @Transactional(readOnly = true)
    public GeneratedReport generate(ReportDefinition definition, String periodToken) {
        PeriodRange range = parseToken(definition.getPeriod(), periodToken);
        return switch (definition.getReportType()) {
            case ENCOUNTER_ACTIVITY -> encounterActivity(definition, range, periodToken);
            case APPOINTMENT_ACTIVITY -> appointmentActivity(definition, range, periodToken);
        };
    }

    /** The token of the period that just closed, given today. */
    public static String priorPeriodToken(ReportPeriod period, LocalDate today) {
        return switch (period) {
            case DAILY -> today.minusDays(1).toString();
            case WEEKLY -> {
                LocalDate lastWeek = today.minusWeeks(1);
                yield "%d-W%02d".formatted(
                    lastWeek.get(IsoFields.WEEK_BASED_YEAR),
                    lastWeek.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            }
            case MONTHLY -> YearMonth.from(today).minusMonths(1).format(MONTH_TOKEN);
        };
    }

    /** Parse a period token back into its date range. Malformed → refusal. */
    public static PeriodRange parseToken(ReportPeriod period, String token) {
        try {
            return switch (period) {
                case DAILY -> {
                    LocalDate day = LocalDate.parse(token);
                    yield new PeriodRange(day, day);
                }
                case WEEKLY -> {
                    String[] parts = token.split("-W");
                    int year = Integer.parseInt(parts[0]);
                    int week = Integer.parseInt(parts[1]);
                    WeekFields iso = WeekFields.ISO;
                    // Jan 4 is always inside ISO week 1 — the safe anchor.
                    LocalDate monday = LocalDate.of(year, 1, 4)
                        .with(iso.weekOfWeekBasedYear(), week)
                        .with(iso.dayOfWeek(), DayOfWeek.MONDAY.getValue());
                    yield new PeriodRange(monday, monday.plusDays(6));
                }
                case MONTHLY -> {
                    YearMonth month = YearMonth.parse(token, MONTH_TOKEN);
                    yield new PeriodRange(month.atDay(1), month.atEndOfMonth());
                }
            };
        } catch (RuntimeException ex) {
            throw new BusinessException("Unparseable period token '" + token
                + "' for a " + period + " report.");
        }
    }

    /* ── canned reports ────────────────────────────────────────────── */

    private GeneratedReport encounterActivity(ReportDefinition definition, PeriodRange range,
                                              String periodToken) {
        Map<LocalDate, int[]> byDay = new TreeMap<>();
        int page = 0;
        Page<Encounter> current;
        do {
            current = encounterRepository.findByHospital_IdAndEncounterDateBetween(
                definition.getHospital().getId(),
                range.start().atStartOfDay(),
                range.end().plusDays(1).atStartOfDay(),
                PageRequest.of(page, PAGE_SIZE));
            current.forEach(encounter -> {
                if (encounter.getEncounterDate() == null) return;
                int[] counts = byDay.computeIfAbsent(
                    encounter.getEncounterDate().toLocalDate(), d -> new int[3]);
                counts[0]++;
                if (encounter.getStatus() == EncounterStatus.COMPLETED) counts[1]++;
                if (encounter.getStatus() == EncounterStatus.CANCELLED) counts[2]++;
            });
            page++;
        } while (current.hasNext());

        return toCsv(definition, periodToken,
            List.of("date", "encounters_total", "completed", "cancelled"),
            byDay, 3);
    }

    private GeneratedReport appointmentActivity(ReportDefinition definition, PeriodRange range,
                                                String periodToken) {
        Map<LocalDate, int[]> byDay = new TreeMap<>();
        List<Appointment> appointments = appointmentRepository
            .findByHospital_IdAndAppointmentDateBetween(
                definition.getHospital().getId(), range.start(), range.end());
        for (Appointment appointment : appointments) {
            if (appointment.getAppointmentDate() == null) continue;
            int[] counts = byDay.computeIfAbsent(appointment.getAppointmentDate(), d -> new int[4]);
            counts[0]++;
            if (appointment.getStatus() == AppointmentStatus.COMPLETED) counts[1]++;
            if (appointment.getStatus() == AppointmentStatus.CANCELLED) counts[2]++;
            if (appointment.getStatus() == AppointmentStatus.NO_SHOW) counts[3]++;
        }

        return toCsv(definition, periodToken,
            List.of("date", "appointments_total", "completed", "cancelled", "no_show"),
            byDay, 4);
    }

    private GeneratedReport toCsv(ReportDefinition definition, String periodToken,
                                  List<String> header, Map<LocalDate, int[]> byDay, int columns) {
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out,
            CSVFormat.DEFAULT.builder().setHeader(header.toArray(String[]::new)).build())) {
            for (Map.Entry<LocalDate, int[]> entry : byDay.entrySet()) {
                Object[] row = new Object[columns + 1];
                row[0] = entry.getKey().toString();
                for (int i = 0; i < columns; i++) {
                    row[i + 1] = entry.getValue()[i];
                }
                printer.printRecord(row);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("CSV generation failed", ex);
        }
        String filename = definition.getReportType().name().toLowerCase()
            + "_" + periodToken.replace("-", "") + ".csv";
        return new GeneratedReport(
            out.toString().getBytes(StandardCharsets.UTF_8), byDay.size(), filename);
    }
}
