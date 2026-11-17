package com.example.hms.payload.dto.clinical;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of a hospital administrator's list of notes left unsigned (provisional platform rule): who wrote it,
 * for whom, the visit's date, when it was written and how many hours it has waited. No clinical content.
 */
public record UnsignedNoteOverdueDTO(
    UUID noteId,
    UUID encounterId,
    String authorName,
    String patientName,
    LocalDateTime visitDate,
    LocalDateTime writtenAt,
    long ageHours) {
}
