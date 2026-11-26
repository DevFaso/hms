package com.example.hms.payload.dto.transfusion;

import java.util.UUID;

/**
 * The emergency O RhD positive protocol of a hospital (V380), as read and as set. On a write a missing age
 * cut-off or deadline takes its default, and a missing switch reads as off.
 */
public record OPositiveProtocolDTO(UUID hospitalId, Boolean active, Integer femaleAgeCutoff, Boolean boysLowRisk,
                                   Integer reviewDeadlineHours) {
}
