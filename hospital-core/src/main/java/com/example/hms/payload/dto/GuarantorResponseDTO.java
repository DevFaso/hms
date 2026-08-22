package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** One guarantor row (P3 #21). Field names are the wire contract. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuarantorResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private String fullName;
    private String relationship;
    private String phone;
    private String email;
    private String address;
    private boolean primary;
    private boolean active;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
