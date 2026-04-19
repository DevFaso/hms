package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.PharmacyFulfillmentMode;
import jakarta.validation.constraints.NotBlank;
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
public class PharmacyRequestDTO {

    @NotBlank(message = "Pharmacy name is required")
    @Size(max = 255)
    private String name;

    @Size(max = 100)
    private String licenseNumber;

    @Size(max = 30)
    private String phone;

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

    @Size(max = 100)
    private String country;

    private Double latitude;

    private Double longitude;

    private PharmacyFulfillmentMode fulfillmentMode;

    @Builder.Default
    private int tier = 1;

    private UUID hospitalId;

    private boolean partnerAgreement;

    @Size(max = 255)
    private String partnerContact;

    @Size(max = 50)
    private String exchangeMethod;

    @Builder.Default
    private boolean active = true;

    @Size(max = 1000)
    private String notes;
}
