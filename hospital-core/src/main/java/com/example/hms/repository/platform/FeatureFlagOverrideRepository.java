package com.example.hms.repository.platform;

import com.example.hms.model.platform.FeatureFlagOverride;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureFlagOverrideRepository extends JpaRepository<FeatureFlagOverride, UUID> {

    Optional<FeatureFlagOverride> findByFlagKeyIgnoreCase(String flagKey);

    List<FeatureFlagOverride> findAllByOrderByFlagKeyAsc();
}
