package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.platform.MllpAllowedSender;
import com.example.hms.payload.dto.platform.MllpAllowedSenderRequestDTO;
import com.example.hms.payload.dto.platform.MllpAllowedSenderResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class MllpAllowedSenderMapper {

    public MllpAllowedSenderResponseDTO toResponseDTO(MllpAllowedSender entity) {
        if (entity == null) {
            return null;
        }
        Hospital hospital = entity.getHospital();
        return new MllpAllowedSenderResponseDTO(
            entity.getId(),
            hospital != null ? hospital.getId() : null,
            hospital != null ? hospital.getName() : null,
            entity.getSendingApplication(),
            entity.getSendingFacility(),
            entity.getDescription(),
            entity.isActive(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    public MllpAllowedSender toEntity(MllpAllowedSenderRequestDTO dto, Hospital hospital) {
        if (dto == null) {
            return null;
        }
        return MllpAllowedSender.builder()
            .hospital(hospital)
            .sendingApplication(dto.sendingApplication() == null ? null : dto.sendingApplication().trim())
            .sendingFacility(dto.sendingFacility() == null ? null : dto.sendingFacility().trim())
            .description(dto.description() == null ? null : dto.description().trim())
            .active(dto.active() == null || dto.active())
            .build();
    }

    public void applyToEntity(MllpAllowedSenderRequestDTO dto, Hospital hospital, MllpAllowedSender target) {
        if (dto == null || target == null) {
            return;
        }
        target.setHospital(hospital);
        target.setSendingApplication(dto.sendingApplication() == null ? null : dto.sendingApplication().trim());
        target.setSendingFacility(dto.sendingFacility() == null ? null : dto.sendingFacility().trim());
        target.setDescription(dto.description() == null ? null : dto.description().trim());
        if (dto.active() != null) {
            target.setActive(dto.active());
        }
    }
}
