package com.example.hms.payload.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentRequestDTO {

    @Null(message = "ID must not be provided for creation")
    private UUID id;

    @NotNull(message = "Department ID is required")
    private UUID departmentId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be less than 255 characters")
    private String name;

    @Size(max = 1000, message = "Description must be less than 1000 characters")
    private String description;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Price must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Price must have up to 10 digits before and 2 after decimal")
    private BigDecimal price;

    @PositiveOrZero(message = "Duration must be positive or zero")
    private Integer durationMinutes;

    @Builder.Default
    private boolean active = true;
}

