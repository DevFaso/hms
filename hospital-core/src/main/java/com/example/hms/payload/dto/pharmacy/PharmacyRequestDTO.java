package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.PharmacyFulfillmentMode;
import com.example.hms.enums.PharmacyType;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class PharmacyRequestDTO {

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    @NotBlank(message = "Pharmacy name is required")
    @Size(max = 255)
    private String name;

    private PharmacyType pharmacyType;

    @Size(max = 50)
    private String licenseNumber;

    @Size(max = 50)
    private String facilityCode;

    @Size(max = 30)
    private String phoneNumber;

    @Size(max = 255)
    private String email;

    @Size(max = 255)
    private String addressLine1;

    @Size(max = 255)
    private String addressLine2;

    @Size(max = 100)
    private String city;

    @Size(max = 100)
    private String region;

    @Size(max = 20)
    private String postalCode;

    @Size(max = 60)
    private String country;

    private PharmacyFulfillmentMode fulfillmentMode;

    @Size(max = 50)
    private String npi;

    @Size(max = 20)
    private String ncpdp;

    private Boolean active;
}
