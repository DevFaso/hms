package com.example.hms.security;

import com.example.hms.enums.ActingMode;

import java.util.UUID;

/**
 * Describes the subject on whose behalf an action executes within the hospital platform.
 *
 * @param userId     unique identifier of the authenticated user initiating the action.
 * @param hospitalId target hospital context; required when {@link ActingMode#STAFF} actions need
 *                   hospital scoping.
 * @param mode       indicates whether the principal is acting as themselves or in an delegated mode.
 * @param roleCode   optional staff role code used when a specific assignment must be pinned while acting.
 */
public record ActingContext(
    UUID userId,
    UUID hospitalId,
    ActingMode mode,
    String roleCode
) {}
