package com.example.hms.repository.platform;

import com.example.hms.model.platform.GlobalSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GlobalSettingRepository extends JpaRepository<GlobalSetting, UUID> {

    Optional<GlobalSetting> findBySettingKeyIgnoreCase(String settingKey);

    List<GlobalSetting> findAllByOrderBySettingKeyAsc();

    List<GlobalSetting> findByCategoryIgnoreCaseOrderBySettingKeyAsc(String category);
}
