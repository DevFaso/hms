package com.example.hms.payload.dto.bed;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BedResponseDTO {

    private UUID id;
    private UUID wardId;
    private String wardName;
    private String wardCode;
    private String bedNumber;
    /** Display label, e.g. "MAT01/B03" — mirrors the roomBed format on admissions. */
    private String label;
    private String status;
    private String bedType;
    private Integer floor;
    private String roomNumber;
    private String notes;
    private boolean active;
}
