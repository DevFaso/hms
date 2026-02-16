package com.example.hms.patient.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AddressDto {
    @Size(max = 180)
    private String line1;

    @Size(max = 180)
    private String line2;

    @Size(max = 120)
    private String city;

    @Size(max = 120)
    private String state;

    @Size(max = 40)
    private String postalCode;

    @Size(max = 120)
    private String country;
}
