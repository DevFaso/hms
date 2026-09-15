package com.example.hms.payload.dto;

import jakarta.validation.constraints.Email;
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
public class HospitalRequestDTO {

    private UUID id;

    @NotBlank(message = "{hospital.name.required}")
    @Size(min = 2, max = 100, message = "{hospital.name.size}")
    private String name;

    private String address; // Optional for foreign countries if poBox is provided

    @NotBlank(message = "{hospital.city.required}")
    private String city;

    @Size(max = 100, message = "{hospital.state.size}")
    private String state; // Required only for US addresses

    private String zipCode; // Required only for US addresses

    @NotBlank(message = "{hospital.country.required}")
    private String country;

    private String province;
    private String region;
    private String sector;
    private String poBox; // For B.P. style addresses

    @NotBlank(message = "{hospital.phoneNumber.required}")
    @Size(min = 10, max = 15, message = "{hospital.phoneNumber.size}")
    private String phoneNumber;

    @Email(message = "{hospital.email.invalid}")
    private String email;

    private String website;

    private UUID organizationId;

    @Builder.Default
    private boolean active = true;
}

