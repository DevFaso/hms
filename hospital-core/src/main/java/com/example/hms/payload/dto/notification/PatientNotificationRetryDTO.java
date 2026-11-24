package com.example.hms.payload.dto.notification;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** The generation the operator actually saw; a replay cannot spend another retry budget. */
public record PatientNotificationRetryDTO(@NotNull @PositiveOrZero Integer expectedManualRetries) {}
