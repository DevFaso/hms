package com.example.hms.payload.dto.scheduling;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VisitTypeRequestDTO {

    /** Null = hospital-wide visit type available to every department. */
    private UUID departmentId;

    @NotBlank(message = "{visitType.code.required}")
    @Size(max = 40)
    private String code;

    @NotBlank(message = "{visitType.name.required}")
    @Size(max = 150)
    private String name;

    @Size(max = 500)
    private String description;

    @NotNull(message = "{visitType.durationMinutes.required}")
    @Min(value = 1, message = "{visitType.durationMinutes.min}")
    private Integer durationMinutes;

    /**
     * Whether a patient may book this themselves. Consumed by the deferred
     * self-scheduling follow-up (#22 waits on booking-from-slot); configurable
     * now so the catalog is ready when that ships.
     */
    private Boolean patientBookable;
}
