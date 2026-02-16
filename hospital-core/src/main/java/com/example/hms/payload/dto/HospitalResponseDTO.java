package com.example.hms.payload.dto;

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
public class HospitalResponseDTO {

    private UUID id;

    private String name;
    private String code;
    private String address;
    private String city;
    private String state;
    private String zipCode;
    private String country;
    private String province;
    private String region;
    private String sector;
    private String poBox;
    private String phoneNumber;
    private String email;
    private String website;

    private UUID organizationId;
    private String organizationName;
    private String organizationCode;

    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
