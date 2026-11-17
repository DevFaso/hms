package com.example.hms.payload.dto.mortality;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * QA D1516: what a death later marked entered in error had closed at the acting hospital, for the chart. Nothing
 * was reopened (#1068): a clinician readmits or rebooks. Dates and ids only; no cause of death and no reason.
 *
 * @param deathRecordId    the voided record
 * @param enteredInErrorAt when it was marked entered in error
 * @param stays            the stays it closed: admitted then, ended when the closure ran
 * @param visits           the visits it completed
 * @param appointments     the appointments it cancelled
 */
public record VoidedDeathClosuresDTO(UUID deathRecordId, LocalDateTime enteredInErrorAt, List<Stay> stays,
                                     List<Visit> visits, List<Booking> appointments) {

    public record Stay(UUID admissionId, LocalDateTime admittedAt, LocalDateTime closedAt) {
    }

    public record Visit(UUID encounterId, LocalDateTime visitAt) {
    }

    public record Booking(UUID appointmentId, LocalDate appointmentDate) {
    }
}
