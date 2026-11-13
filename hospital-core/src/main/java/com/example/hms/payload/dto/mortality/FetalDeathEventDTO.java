package com.example.hms.payload.dto.mortality;

import com.example.hms.enums.FetalDeathEventSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D1714: a stillbirth on the mortality register, from a fetal-death event or, when no event holds it, from a
 * stillbirth death record. Each named classification is given with its own flag; {@code classificationIncomplete}
 * when the gestation is not known.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FetalDeathEventDTO {

    /** The event's id; null for a row read from a death record. */
    private UUID id;
    /** The death record's id when the row comes from one (no event holds it). */
    private UUID deathRecordId;
    /** On an event's row: the death record that later declared it, counted once, through the event. */
    private UUID declaredByDeathRecordId;
    private UUID deliveryRecordId;
    private UUID motherPatientId;
    private String motherName;
    private Integer birthOrder;
    private LocalDateTime occurredAt;
    private Integer gestationalAgeWeeks;
    private boolean noSignsOfLife;
    /** DELIVERY, DELIVERY_HISTORICAL; null for a row read from a death record. */
    private FetalDeathEventSource source;
    private String recordedByName;
    /** The WHO stillbirth indicator (StillbirthClassification, provisional). */
    private boolean whoStillbirth;
    /** The broader ICD level (StillbirthClassification, provisional). */
    private boolean icdFetalDeath;
    private boolean classificationIncomplete;
    /** Set when a correction (live to stillborn) created the event: the reason given. */
    private String creationReason;
    /** A correction (stillborn to live) superseded the event: when, by whom, why. It is kept, never deleted. */
    private LocalDateTime supersededAt;
    private String supersededByName;
    private String supersedeReason;
}
