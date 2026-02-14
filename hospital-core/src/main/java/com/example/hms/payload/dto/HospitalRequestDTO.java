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

    @NotBlank(message = "Hospital name cannot be blank")
    @Size(min = 2, max = 100, message = "Hospital name must be between 2 and 100 characters")
    private String name;

    private String address; // Optional for foreign countries if poBox is provided

    @NotBlank(message = "City cannot be blank")
    private String city;

    @Size(max = 100, message = "State name must be less than 100 characters")
    private String state; // Required only for US addresses

    private String zipCode; // Required only for US addresses

    @NotBlank(message = "Country cannot be blank")
    private String country;

    private String province;
    private String region;
    private String sector;
    private String poBox; // For B.P. style addresses

    @NotBlank(message = "Phone number cannot be blank")
    @Size(min = 10, max = 15, message = "Phone number must be between 10 and 15 characters")
    private String phoneNumber;

    @Email(message = "Email should be valid")
    private String email;

    private String website;

    private UUID organizationId;

    @Builder.Default
    private boolean active = true;
}

