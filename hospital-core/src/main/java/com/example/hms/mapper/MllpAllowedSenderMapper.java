package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.platform.MllpAllowedSender;
import com.example.hms.payload.dto.platform.MllpAllowedSenderRequestDTO;
import com.example.hms.payload.dto.platform.MllpAllowedSenderResponseDTO;
import java.util.Locale;
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
            .sendingApplication(normalizeSenderField(dto.sendingApplication()))
            .sendingFacility(normalizeSenderField(dto.sendingFacility()))
            .description(dto.description() == null ? null : dto.description().trim())
            .active(dto.active() == null || dto.active())
            .build();
    }

    public void applyToEntity(MllpAllowedSenderRequestDTO dto, Hospital hospital, MllpAllowedSender target) {
        if (dto == null || target == null) {
            return;
        }
        target.setHospital(hospital);
        target.setSendingApplication(normalizeSenderField(dto.sendingApplication()));
        target.setSendingFacility(normalizeSenderField(dto.sendingFacility()));
        target.setDescription(dto.description() == null ? null : dto.description().trim());
        if (dto.active() != null) {
            target.setActive(dto.active());
        }
    }

    /**
     * Sender app/facility values are stored in upper-case canonical
     * form so that the unique constraint and the runtime lookup index
     * work without {@code UPPER()}/{@code LOWER()} wrappers. The
     * matching DB-level CHECK constraints (V62) reject any direct
     * insert that bypasses this layer.
     */
    static String normalizeSenderField(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
