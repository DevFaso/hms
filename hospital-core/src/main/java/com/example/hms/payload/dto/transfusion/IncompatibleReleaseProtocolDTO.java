package com.example.hms.payload.dto.transfusion;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * The setting of one hospital for a release despite an incompatible crossmatch when no physician can be reached
 * (V381, rule set INCOMPATIBLE_RELEASE_AUTHORITY): « Libération incompatible sans médecin joignable ». Off until
 * the laboratory director switches it on.
 *
 * @param fallbackEnabled          the switch; required on a write, so an empty body changes nothing by accident
 * @param declarerRoles            the roles that may declare that no physician can be reached; laboratory staff
 *                                 when left out
 * @param countersignDeadlineHours read-only: the deadline of the emergency release protocol of the hospital
 * @param allowedDeclarerRoles     read-only: the roles the setting may name
 */
public record IncompatibleReleaseProtocolDTO(
    UUID hospitalId,
    @NotNull Boolean fallbackEnabled,
    List<String> declarerRoles,
    Integer countersignDeadlineHours,
    List<String> allowedDeclarerRoles) {
}
