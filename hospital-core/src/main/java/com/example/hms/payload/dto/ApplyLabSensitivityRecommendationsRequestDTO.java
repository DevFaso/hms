package com.example.hms.payload.dto;

import com.example.hms.enums.SensitivityCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Policy 2026-11-20: the recommendations the lab director confirmed, one by
 * one. Each names the test and the category shown to them; the server applies
 * it only when it is still what the mapping recommends for that test.
 */
public record ApplyLabSensitivityRecommendationsRequestDTO(
    @NotEmpty @Size(max = 500) @Valid List<Item> items
) {

    public record Item(@NotNull UUID definitionId, @NotNull SensitivityCategory category) {
    }
}
