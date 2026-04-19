package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.PharmacyFulfillmentMode;
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
    private String name;
    private String licenseNumber;
    private String phone;
    private String email;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String region;
    private String country;
    private Double latitude;
    private Double longitude;
    private PharmacyFulfillmentMode fulfillmentMode;
    private int tier;
    private UUID hospitalId;
    private String hospitalName;
    private boolean partnerAgreement;
    private String partnerContact;
    private String exchangeMethod;
    private boolean active;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
