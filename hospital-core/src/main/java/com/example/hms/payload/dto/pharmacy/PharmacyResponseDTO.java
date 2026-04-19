package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacyResponseDTO {

    private UUID id;
    private UUID hospitalId;
    private String name;
    private String pharmacyType;
    private String licenseNumber;
    private String facilityCode;
    private String phoneNumber;
    private String email;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String region;
    private String postalCode;
    private String country;
    private String fulfillmentMode;
    private String npi;
    private String ncpdp;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
