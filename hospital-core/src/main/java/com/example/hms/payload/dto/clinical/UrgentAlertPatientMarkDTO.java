package com.example.hms.payload.dto.clinical;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D4101 (r81): the mark of a patient with urgent alerts nobody has acknowledged: how many, and since when the
 * oldest waits. It carries no clinical text.
 */
public record UrgentAlertPatientMarkDTO(UUID patientId, long open, LocalDateTime oldestRaisedAt) {
}
