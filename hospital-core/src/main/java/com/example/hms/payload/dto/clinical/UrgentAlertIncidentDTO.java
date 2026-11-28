package com.example.hms.payload.dto.clinical;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D4102 (r81): one line of the register of unresolved urgent alerts.
 *
 * @param caseKey              the incidents of one patient opened on one day share it: they are read as one case
 * @param source               the kind of record behind the alert (a code, with {@code sourceLabel} in the
 *                             reader's language)
 * @param patientId            the patient, only for a reader who may open the chart; null for an administrator
 * @param raisedAt             when the alert was first notified; null when the register does not hold it
 * @param stepsTaken           the steps of the ladder taken so far (1 the covering clinician, 2 the médecin-chef,
 *                             3 the incident, then one per reminder); null when the register does not hold it
 * @param acknowledgedByName   who acknowledged it; null while open, or when the record itself closed the alert
 * @param resolution           how it ended ({@code ACKNOWLEDGED}, {@code NO_LONGER_URGENT}); null while open
 */
public record UrgentAlertIncidentDTO(UUID id, String caseKey, String source, String sourceLabel, UUID patientId,
                                     String patientName, LocalDateTime raisedAt, LocalDateTime openedAt,
                                     int minutesUnacknowledged, int recipientsTold, Integer stepsTaken,
                                     LocalDateTime lastStepAt, String acknowledgedByName, LocalDateTime closedAt,
                                     String resolution) {
}
