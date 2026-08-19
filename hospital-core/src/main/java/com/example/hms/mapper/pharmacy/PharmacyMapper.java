package com.example.hms.mapper.pharmacy;

import com.example.hms.enums.PharmacyFulfillmentMode;
import com.example.hms.enums.PharmacyType;
import com.example.hms.model.Hospital;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.pharmacy.PharmacyRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PharmacyMapper {

    public PharmacyResponseDTO toResponseDTO(Pharmacy entity) {
        if (entity == null) {
            return null;
        }

        return PharmacyResponseDTO.builder()
            .id(entity.getId())
            .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
            .name(entity.getName())
            .pharmacyType(entity.getPharmacyType() != null ? entity.getPharmacyType().name() : null)
            .licenseNumber(entity.getLicenseNumber())
            .facilityCode(entity.getFacilityCode())
            .phoneNumber(entity.getPhoneNumber())
            .email(entity.getEmail())
            .addressLine1(entity.getAddressLine1())
            .addressLine2(entity.getAddressLine2())
            .city(entity.getCity())
            .region(entity.getRegion())
            .postalCode(entity.getPostalCode())
            .country(entity.getCountry())
            .fulfillmentMode(entity.getFulfillmentMode() != null ? entity.getFulfillmentMode().name() : null)
            .npi(entity.getNpi())
            .ncpdp(entity.getNcpdp())
            .active(entity.isActive())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public Pharmacy toEntity(PharmacyRequestDTO dto, Hospital hospital) {
        if (dto == null) {
            return null;
        }

        return Pharmacy.builder()
            .hospital(hospital)
            .name(dto.getName())
            .pharmacyType(dto.getPharmacyType() != null
                ? dto.getPharmacyType() : PharmacyType.HOSPITAL_DISPENSARY)
            .licenseNumber(dto.getLicenseNumber())
            .facilityCode(dto.getFacilityCode())
            .phoneNumber(dto.getPhoneNumber())
            .email(dto.getEmail())
            .addressLine1(dto.getAddressLine1())
            .addressLine2(dto.getAddressLine2())
            .city(dto.getCity())
            .region(dto.getRegion())
            .postalCode(dto.getPostalCode())
            .country(dto.getCountry() != null ? dto.getCountry() : "Burkina Faso")
            .fulfillmentMode(dto.getFulfillmentMode() != null
                ? dto.getFulfillmentMode() : PharmacyFulfillmentMode.OUTPATIENT_HOSPITAL)
            .npi(dto.getNpi())
            .ncpdp(dto.getNcpdp())
            .active(dto.getActive() == null || dto.getActive())
            .build();
    }

    public void updateEntity(Pharmacy entity, PharmacyRequestDTO dto) {
        if (dto.getName() != null) {
            entity.setName(dto.getName());
        }
        if (dto.getPharmacyType() != null) {
            entity.setPharmacyType(dto.getPharmacyType());
        }
        if (dto.getLicenseNumber() != null) {
            entity.setLicenseNumber(dto.getLicenseNumber());
        }
        if (dto.getFacilityCode() != null) {
            entity.setFacilityCode(dto.getFacilityCode());
        }
        if (dto.getPhoneNumber() != null) {
            entity.setPhoneNumber(dto.getPhoneNumber());
        }
        if (dto.getEmail() != null) {
            entity.setEmail(dto.getEmail());
        }
        if (dto.getFulfillmentMode() != null) {
            entity.setFulfillmentMode(dto.getFulfillmentMode());
        }
        if (dto.getNpi() != null) {
            entity.setNpi(dto.getNpi());
        }
        if (dto.getNcpdp() != null) {
            entity.setNcpdp(dto.getNcpdp());
        }
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
        updateAddress(entity, dto);
    }

    private void updateAddress(Pharmacy entity, PharmacyRequestDTO dto) {
        if (dto.getAddressLine1() != null) {
            entity.setAddressLine1(dto.getAddressLine1());
        }
        if (dto.getAddressLine2() != null) {
            entity.setAddressLine2(dto.getAddressLine2());
        }
        if (dto.getCity() != null) {
            entity.setCity(dto.getCity());
        }
        if (dto.getRegion() != null) {
            entity.setRegion(dto.getRegion());
        }
        if (dto.getPostalCode() != null) {
            entity.setPostalCode(dto.getPostalCode());
        }
        if (dto.getCountry() != null) {
            entity.setCountry(dto.getCountry());
        }
    }
}
