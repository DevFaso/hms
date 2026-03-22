package com.example.hms.service;

import com.example.hms.payload.dto.globalsetting.GlobalSettingResponseDTO;

import java.util.List;
import java.util.UUID;

public interface GlobalSettingService {

    List<GlobalSettingResponseDTO> listAll();

    List<GlobalSettingResponseDTO> listByCategory(String category);

    GlobalSettingResponseDTO getByKey(String settingKey);

    GlobalSettingResponseDTO upsert(String settingKey, String settingValue, String category,
                                     String description, String updatedBy);

    void delete(UUID id, String deletedBy);
}
