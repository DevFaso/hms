package com.example.hms.payload.dto;

import com.example.hms.enums.SensitivityCategory;

import java.util.UUID;

/**
 * Policy 2026-11-20 (provisional): one test of the catalogue the curated
 * mapping recommends a sensitive category for. A suggestion: nothing is
 * tagged until the lab director confirms it.
 *
 * @param currentCategory     the tag the test carries today; null for none
 * @param recommendedCategory what the mapping recommends
 * @param matchedOn           CODE, NAME or COMPONENT (a panel's component)
 * @param matchedText         the code, name or component that matched
 * @param applied             the test already carries the recommended category
 * @param provisional         the mapping is provisional (awaiting a clinician's sign-off)
 */
public record LabSensitivityRecommendationDTO(
    UUID definitionId,
    String testCode,
    String name,
    boolean panel,
    SensitivityCategory currentCategory,
    SensitivityCategory recommendedCategory,
    String matchedOn,
    String matchedText,
    boolean applied,
    boolean provisional
) {
}
