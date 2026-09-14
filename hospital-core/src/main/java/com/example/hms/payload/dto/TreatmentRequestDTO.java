package com.example.hms.payload.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentRequestDTO {

    @Null(message = "{treatment.id.null}")
    private UUID id;

    @NotNull(message = "{treatment.departmentId.required}")
    private UUID departmentId;

    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;

    @NotBlank(message = "{treatment.name.required}")
    @Size(max = 255, message = "{treatment.name.size}")
    private String name;

    @Size(max = 1000, message = "{treatment.description.size}")
    private String description;

    @NotNull(message = "{treatment.price.required}")
    @DecimalMin(value = "0.0", inclusive = false, message = "{treatment.price.min}")
    @Digits(integer = 10, fraction = 2, message = "{treatment.price.digits}")
    private BigDecimal price;

    @PositiveOrZero(message = "{treatment.durationMinutes.positive}")
    private Integer durationMinutes;

    @Builder.Default
    private boolean active = true;
}

