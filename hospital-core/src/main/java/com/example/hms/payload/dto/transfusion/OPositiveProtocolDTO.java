package com.example.hms.payload.dto.transfusion;

import java.util.UUID;

/**
 * The emergency O RhD positive protocol of a hospital (V380), as read and as set. On a write a missing age
 * cut-off or deadline takes its default, and a missing switch reads as off.
 */
public record OPositiveProtocolDTO(
    UUID hospitalId,
    /** Required on a write: an empty body must not switch a hospital's protocol off by accident. */
    @jakarta.validation.constraints.NotNull(message = "{transfusion.oPositive.protocol.activeRequired}") Boolean active,
    Integer femaleAgeCutoff,
    Boolean boysLowRisk,
    Integer reviewDeadlineHours) {
}
