package com.example.hms.payload.dto.bed;

import com.example.hms.enums.WardType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Payload for creating or updating a ward. Hospital comes from the active tenant scope. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WardRequestDTO {

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 20)
    private String code;

    @NotNull
    private WardType wardType;

    private Integer floor;

    @Size(max = 500)
    private String description;

    private UUID departmentId;

    /** Null on create (defaults true); set false to retire a ward without deleting it. */
    private Boolean active;
}
