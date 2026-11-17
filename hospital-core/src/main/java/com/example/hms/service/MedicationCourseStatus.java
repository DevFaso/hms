package com.example.hms.service;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Prescription;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D720 / D737: the one rule for a course's status (ACTIVE, COMPLETED,
 * DISCONTINUED, ON_HOLD), shared by the patient's medication list and the
 * staff snapshot's "active medications". The snapshot used to list the
 * patient's last ten prescriptions whatever their status, so a draft and a
 * course that ended with a discharged stay showed as active while the
 * current stay's orders were pushed off the page.
 */
public final class MedicationCourseStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String COMPLETED = "COMPLETED";
    public static final String DISCONTINUED = "DISCONTINUED";
    public static final String ON_HOLD = "ON_HOLD";

    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d{1,5})\\s?days?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKS_PATTERN = Pattern.compile("(\\d{1,5})\\s?weeks?", Pattern.CASE_INSENSITIVE);

    private MedicationCourseStatus() {
    }

    /** The course's status today; {@code closedStays} are the patient's discharged admissions. */
    public static String of(Prescription prescription, List<Admission> closedStays) {
        String status = fromOrderStatus(prescription.getStatus(), endDate(prescription));
        if (ACTIVE.equals(status) && InpatientOrderEnd.endedWithAStay(prescription, closedStays)) {
            return COMPLETED;
        }
        return status;
    }

    public static boolean isActive(Prescription prescription, List<Admission> closedStays) {
        return ACTIVE.equals(of(prescription, closedStays));
    }

    public static LocalDate startDate(Prescription prescription) {
        LocalDateTime created = prescription.getCreatedAt();
        return created != null ? created.toLocalDate() : null;
    }

    public static LocalDate endDate(Prescription prescription) {
        LocalDate start = startDate(prescription);
        String duration = prescription.getDuration();
        if (start == null || duration == null || duration.isBlank()) {
            return null;
        }
        Matcher days = DAYS_PATTERN.matcher(duration);
        if (days.find()) {
            int n = parseInt(days.group(1));
            if (n > 0) {
                return start.plusDays(n);
            }
        }
        Matcher weeks = WEEKS_PATTERN.matcher(duration);
        if (weeks.find()) {
            int n = parseInt(weeks.group(1));
            if (n > 0) {
                return start.plusWeeks(n);
            }
        }
        return null;
    }

    private static String fromOrderStatus(PrescriptionStatus status, LocalDate endDate) {
        boolean ended = endDate != null && endDate.isBefore(LocalDate.now());
        if (status == null) {
            return ended ? COMPLETED : ACTIVE;
        }
        return switch (status) {
            case DISCONTINUED, CANCELLED -> DISCONTINUED;
            case DRAFT, PENDING_SIGNATURE, TRANSMISSION_FAILED -> ON_HOLD;
            default -> ended ? COMPLETED : ACTIVE;
        };
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
