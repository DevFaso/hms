package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** One superseded address: valid until {@code replacedAt} (Tier 2 item 38). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientAddressHistoryDTO {

    private UUID id;
    private String address;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String zipCode;
    private String country;
    /** When the address stopped being current — the moment it was replaced. */
    private LocalDateTime replacedAt;
}
