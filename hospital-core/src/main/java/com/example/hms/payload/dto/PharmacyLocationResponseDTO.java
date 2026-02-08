package com.example.hms.payload.dto;

import com.example.hms.enums.PharmacyFulfillmentMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Representation of a patient-facing pharmacy destination.")
public class PharmacyLocationResponseDTO {

    private UUID id;
    private String name;
    private PharmacyFulfillmentMode mode;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String phoneNumber;
    private String faxNumber;
    private Boolean supportsEprescribe;
    private Boolean supportsControlledSubstances;
    private Boolean preferred;
}
