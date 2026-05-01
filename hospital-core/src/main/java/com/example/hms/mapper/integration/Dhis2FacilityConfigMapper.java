package com.example.hms.mapper.integration;

import com.example.hms.model.Hospital;
import com.example.hms.model.integration.Dhis2FacilityConfig;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigResponseDTO;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapper. <strong>Never carries the secret value into the
 * response DTO</strong> — only the env-var name and a "configured"
 * boolean derived from {@link System#getenv(String)}.
 */
@Component
public class Dhis2FacilityConfigMapper {

    public Dhis2FacilityConfigResponseDTO toResponseDTO(Dhis2FacilityConfig entity) {
        if (entity == null) {
            return null;
        }
        return new Dhis2FacilityConfigResponseDTO(
            entity.getId(),
            entity.getHospital() != null ? entity.getHospital().getId() : null,
            entity.getBaseUrl(),
            entity.getAuthMode(),
            entity.getAuthSecretEnvVar(),
            isEnvVarConfigured(entity.getAuthSecretEnvVar()),
            entity.getDefaultPeriodType(),
            entity.getDefaultDatasetUid(),
            entity.getLastExportAt(),
            entity.isActive(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    public Dhis2FacilityConfig toEntity(Dhis2FacilityConfigRequestDTO dto, Hospital hospital) {
        if (dto == null) {
            return null;
        }
        return Dhis2FacilityConfig.builder()
            .hospital(hospital)
            .baseUrl(trim(dto.baseUrl()))
            .authMode(dto.authMode())
            .authSecretEnvVar(trim(dto.authSecretEnvVar()))
            .defaultPeriodType(dto.defaultPeriodType())
            .defaultDatasetUid(trim(dto.defaultDatasetUid()))
            .active(dto.active() == null || dto.active())
            .build();
    }

    public void applyToEntity(Dhis2FacilityConfigRequestDTO dto, Hospital hospital,
                              Dhis2FacilityConfig target) {
        if (dto == null || target == null) {
            return;
        }
        target.setHospital(hospital);
        target.setBaseUrl(trim(dto.baseUrl()));
        target.setAuthMode(dto.authMode());
        target.setAuthSecretEnvVar(trim(dto.authSecretEnvVar()));
        target.setDefaultPeriodType(dto.defaultPeriodType());
        target.setDefaultDatasetUid(trim(dto.defaultDatasetUid()));
        if (dto.active() != null) {
            target.setActive(dto.active());
        }
    }

    private static boolean isEnvVarConfigured(String envVarName) {
        if (envVarName == null || envVarName.isBlank()) {
            return false;
        }
        final String value = System.getenv(envVarName);
        return value != null && !value.isBlank();
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
