package com.example.hms.payload.dto.bed;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Payload for creating or updating a bed. Ward comes from the request path. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BedRequestDTO {

    @NotBlank
    @Size(max = 20)
    private String bedNumber;

    @Size(max = 50)
    private String bedType;

    private Integer floor;

    @Size(max = 20)
    private String roomNumber;

    @Size(max = 500)
    private String notes;

    /** Null on create (defaults true); set false to retire a bed without deleting it. */
    private Boolean active;
}
