package com.example.hms.repository;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.model.Admission;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D1537 review (round 40): what the course status reads of a discharged stay
 * ({@code MedicationCourseStatus}, {@code InpatientOrderEnd}): its id, when it began and ended, its
 * hospital and its status. A lean row, read without the admission's entity graph.
 */
public record ClosedStayRow(UUID id, LocalDateTime admittedAt, LocalDateTime dischargedAt, UUID hospitalId,
                            AdmissionStatus status) {

    /** A detached admission carrying only these values, for the readers that take admissions. */
    public Admission toAdmission() {
        return Admission.detachedClosedStay(id, admittedAt, dischargedAt, hospitalId, status);
    }
}
