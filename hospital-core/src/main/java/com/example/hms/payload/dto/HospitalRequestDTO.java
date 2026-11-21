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

    private String address; // Required for every hospital (QA D747); enforced in HospitalServiceImpl

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
    // QA D833: the limit is on what is typed, spaces included; the service
    // stores the number without them (PhoneNumbers.normalizeFacilityPhone).
    @Size(max = 30, message = "{hospital.phoneNumber.size}")
    @com.example.hms.validation.PhoneNumber
    private String phoneNumber;

    @Email(message = "{hospital.email.invalid}")
    private String email;

    private String website;

    private UUID organizationId;

    /**
     * QA r65 D2500: the hospital's code, chosen by the super-admin (letters,
     * digits and hyphens, unique). Blank on create means "generate one";
     * blank on update means "leave it". The rules and the refusals are
     * {@code HospitalLocaleRules} and {@code HospitalServiceImpl}.
     */
    @Size(max = 20, message = "{hospital.code.invalid}")
    private String code;

    /** QA r65 D2501: ISO 4217 code of the hospital's currency; blank keeps XOF (create) or the stored one (update). */
    @Size(max = 3, message = "{hospital.currency.invalid}")
    private String currencyCode;

    /** QA r65 D2501: IANA zone id of the hospital; blank means the organisation's default time zone. */
    @Size(max = 64, message = "{hospital.timeZone.invalid}")
    private String timeZone;

    @Builder.Default
    private boolean active = true;
}

