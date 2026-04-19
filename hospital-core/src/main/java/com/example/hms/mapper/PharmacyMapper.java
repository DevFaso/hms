package com.example.hms.mapper;

import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.pharmacy.PharmacyRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PharmacyMapper {

    public PharmacyResponseDTO toResponseDTO(Pharmacy entity) {
        if (entity == null) return null;
        return PharmacyResponseDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .licenseNumber(entity.getLicenseNumber())
                .phone(entity.getPhone())
                .email(entity.getEmail())
                .addressLine1(entity.getAddressLine1())
                .addressLine2(entity.getAddressLine2())
                .city(entity.getCity())
                .region(entity.getRegion())
                .country(entity.getCountry())
                .latitude(entity.getLatitude())
                .longitude(entity.getLongitude())
                .fulfillmentMode(entity.getFulfillmentMode())
                .tier(entity.getTier())
                .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
                .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : null)
                .partnerAgreement(entity.isPartnerAgreement())
                .partnerContact(entity.getPartnerContact())
                .exchangeMethod(entity.getExchangeMethod())
                .active(entity.isActive())
                .notes(entity.getNotes())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public Pharmacy toEntity(PharmacyRequestDTO dto) {
        if (dto == null) return null;
        return Pharmacy.builder()
                .name(dto.getName())
                .licenseNumber(dto.getLicenseNumber())
                .phone(dto.getPhone())
                .email(dto.getEmail())
                .addressLine1(dto.getAddressLine1())
                .addressLine2(dto.getAddressLine2())
                .city(dto.getCity())
                .region(dto.getRegion())
                .country(dto.getCountry())
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .fulfillmentMode(dto.getFulfillmentMode())
                .tier(dto.getTier())
                .partnerAgreement(dto.isPartnerAgreement())
                .partnerContact(dto.getPartnerContact())
                .exchangeMethod(dto.getExchangeMethod())
                .active(dto.isActive())
                .notes(dto.getNotes())
                .build();
    }
}
