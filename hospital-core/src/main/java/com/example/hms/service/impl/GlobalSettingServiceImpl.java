package com.example.hms.service.impl;

import com.example.hms.model.platform.GlobalSetting;
import com.example.hms.payload.dto.globalsetting.GlobalSettingResponseDTO;
import com.example.hms.repository.platform.GlobalSettingRepository;
import com.example.hms.service.GlobalSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalSettingServiceImpl implements GlobalSettingService {

    private final GlobalSettingRepository repository;

    @Override
    public List<GlobalSettingResponseDTO> listAll() {
        return repository.findAllByOrderBySettingKeyAsc()
            .stream()
            .map(this::toDto)
            .toList();
    }

    @Override
    public List<GlobalSettingResponseDTO> listByCategory(String category) {
        return repository.findByCategoryIgnoreCaseOrderBySettingKeyAsc(category)
            .stream()
            .map(this::toDto)
            .toList();
    }

    @Override
    public GlobalSettingResponseDTO getByKey(String settingKey) {
        GlobalSetting entity = repository.findBySettingKeyIgnoreCase(settingKey.trim())
            .orElseThrow(() -> new IllegalArgumentException("Setting not found: " + settingKey));
        return toDto(entity);
    }

    @Override
    @Transactional
    public GlobalSettingResponseDTO upsert(String settingKey, String settingValue,
                                            String category, String description, String updatedBy) {
        String normalizedKey = normalizeKey(settingKey);
        GlobalSetting entity = repository.findBySettingKeyIgnoreCase(normalizedKey)
            .orElseGet(() -> GlobalSetting.builder().settingKey(normalizedKey).build());

        entity.setSettingValue(settingValue);
        entity.setCategory(sanitize(category, 60));
        entity.setDescription(sanitize(description, 255));
        entity.setUpdatedBy(updatedBy);
        repository.save(entity);

        log.info("Global setting saved key={} updatedBy={}", normalizedKey, updatedBy);
        return toDto(entity);
    }

    @Override
    @Transactional
    public void delete(UUID id, String deletedBy) {
        repository.findById(id).ifPresent(entity -> {
            repository.delete(entity);
            log.info("Global setting deleted key={} deletedBy={} id={}", entity.getSettingKey(), deletedBy, id);
        });
    }

    private GlobalSettingResponseDTO toDto(GlobalSetting entity) {
        return GlobalSettingResponseDTO.builder()
            .id(entity.getId())
            .settingKey(entity.getSettingKey())
            .settingValue(entity.getSettingValue())
            .category(entity.getCategory())
            .description(entity.getDescription())
            .updatedBy(entity.getUpdatedBy())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("Setting key must not be blank");
        }
        return key.trim();
    }

    private String sanitize(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
