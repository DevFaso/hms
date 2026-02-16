package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.HospitalRequestDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class HospitalMapper {

    /* ----------------------------------------
     * Entity -> Response DTO
     * ---------------------------------------- */
    /**
     * Converts a Hospital entity to a HospitalResponseDTO.
     * Ensures never-null string fields in the response (uses empty strings as safe defaults).
     */
    public HospitalResponseDTO toHospitalDTO(Hospital hospital) {
        if (hospital == null) return null;

        var organization = hospital.getOrganization();

        return HospitalResponseDTO.builder()
            .id(hospital.getId())
            .name(nz(hospital.getName()))
            .code(nz(hospital.getCode()))
            .address(nz(hospital.getAddress()))
            .city(nz(hospital.getCity()))
            .state(nz(hospital.getState()))
            .zipCode(nz(hospital.getZipCode()))
            .country(nz(hospital.getCountry()))
            .province(nz(hospital.getProvince()))
            .region(nz(hospital.getRegion()))
            .sector(nz(hospital.getSector()))
            .poBox(nz(hospital.getPoBox()))
            .phoneNumber(nz(hospital.getPhoneNumber()))
            .email(nz(hospital.getEmail()))
            .website(nz(hospital.getWebsite()))
            .organizationId(organization != null ? organization.getId() : null)
        .organizationName(organization != null ? trim(organization.getName()) : null)
        .organizationCode(organization != null ? trim(organization.getCode()) : null)
            .active(hospital.isActive())
            .createdAt(hospital.getCreatedAt())
            .updatedAt(hospital.getUpdatedAt())
            .build();
    }

    /* ----------------------------------------
     * Request DTO -> Entity
     * ---------------------------------------- */
    /**
     * Converts a HospitalRequestDTO to a new Hospital entity.
     * Applies trimming and basic normalization (email/website).
     */
    public Hospital toHospital(HospitalRequestDTO dto) {
        if (dto == null) return null;

        return Hospital.builder()
            .name(trim(dto.getName()))
            .address(trim(dto.getAddress()))
            .city(trim(dto.getCity()))
            .state(trim(dto.getState()))
            .zipCode(trim(dto.getZipCode()))
            .country(trim(dto.getCountry()))
            .province(trim(dto.getProvince()))
            .region(trim(dto.getRegion()))
            .sector(trim(dto.getSector()))
            .poBox(trim(dto.getPoBox()))
            .phoneNumber(trim(dto.getPhoneNumber()))
            .email(normalizeEmail(dto.getEmail()))
            .website(normalizeUrl(dto.getWebsite()))
            .active(dto.isActive())
            .build();
    }

    /**
     * Converts a HospitalResponseDTO to a Hospital entity.
     * Use carefully (typically for editing workflows); trims and normalizes fields.
     */
    public Hospital toHospital(HospitalResponseDTO dto) {
        if (dto == null) return null;

        Hospital hospital = Hospital.builder()
            .name(trim(dto.getName()))
            .address(trim(dto.getAddress()))
            .city(trim(dto.getCity()))
            .state(trim(dto.getState()))
            .zipCode(trim(dto.getZipCode()))
            .country(trim(dto.getCountry()))
            .province(trim(dto.getProvince()))
            .region(trim(dto.getRegion()))
            .sector(trim(dto.getSector()))
            .poBox(trim(dto.getPoBox()))
            .phoneNumber(trim(dto.getPhoneNumber()))
            .email(normalizeEmail(dto.getEmail()))
            .website(normalizeUrl(dto.getWebsite()))
            .active(dto.isActive())
            .build();

        hospital.setId(dto.getId());
        hospital.setCreatedAt(dto.getCreatedAt());
        hospital.setUpdatedAt(dto.getUpdatedAt());
        return hospital;
    }

    /* ----------------------------------------
     * Partial Update
     * ---------------------------------------- */
    /**
     * Updates a Hospital entity with non-null values from HospitalRequestDTO.
     * Trims and normalizes incoming strings.
     *
     * @param dto      update source
     * @param hospital target entity
     */
    public void updateHospitalFromDto(HospitalRequestDTO dto, Hospital hospital) {
        if (dto == null || hospital == null) return;

        if (dto.getName() != null) hospital.setName(trim(dto.getName()));
        if (dto.getAddress() != null) hospital.setAddress(trim(dto.getAddress()));
        if (dto.getCity() != null) hospital.setCity(trim(dto.getCity()));
        if (dto.getState() != null) hospital.setState(trim(dto.getState()));
        if (dto.getZipCode() != null) hospital.setZipCode(trim(dto.getZipCode()));
        if (dto.getCountry() != null) hospital.setCountry(trim(dto.getCountry()));
        if (dto.getProvince() != null) hospital.setProvince(trim(dto.getProvince()));
        if (dto.getRegion() != null) hospital.setRegion(trim(dto.getRegion()));
        if (dto.getSector() != null) hospital.setSector(trim(dto.getSector()));
        if (dto.getPoBox() != null) hospital.setPoBox(trim(dto.getPoBox()));
        if (dto.getPhoneNumber() != null) hospital.setPhoneNumber(trim(dto.getPhoneNumber()));
        if (dto.getEmail() != null) hospital.setEmail(normalizeEmail(dto.getEmail()));
        if (dto.getWebsite() != null) hospital.setWebsite(normalizeUrl(dto.getWebsite()));

        // Boolean primitive in DTO -> always apply
        hospital.setActive(dto.isActive());
    }

    /* ----------------------------------------
     * Helpers
     * ---------------------------------------- */
    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** Never-zero (for responses) — replace null with empty string. */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String normalizeEmail(String email) {
        if (email == null) return null;
        String e = email.trim();
        return e.isEmpty() ? null : e.toLowerCase();
    }

    /**
     * Very light URL normalization:
     * - trims
     * - returns null if blank
     * - leaves as-is if already has a scheme
     * - otherwise, prefixes with "https://"
     */
    private static String normalizeUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        return "https://" + u;
    }
}
