package com.example.hms.payload.dto.pharmacy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** QA D4191: why a count is closed without an adjustment. */
public record StockCountClosureDTO(
    @NotBlank(message = "{stockCount.closeReasonRequired}")
    @Size(max = 300, message = "{stockCount.closeReasonRequired}") String reason) { }
