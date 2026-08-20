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
public class WardResponseDTO {

    private UUID id;
    private UUID hospitalId;
    private String name;
    private String code;
    private String wardType;
    private Integer floor;
    private String description;
    private UUID departmentId;
    private String departmentName;
    private boolean active;
    private long totalBeds;
    private long availableBeds;
    private long occupiedBeds;
}
