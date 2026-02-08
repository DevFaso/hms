package com.example.hms.repository.platform;

import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.model.platform.OrganizationPlatformService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationPlatformServiceRepository extends JpaRepository<OrganizationPlatformService, UUID> {

    boolean existsByOrganizationIdAndServiceType(UUID organizationId, PlatformServiceType serviceType);

    Optional<OrganizationPlatformService> findByOrganizationIdAndServiceType(UUID organizationId, PlatformServiceType serviceType);

    List<OrganizationPlatformService> findByOrganizationId(UUID organizationId);

    List<OrganizationPlatformService> findByOrganizationIdAndStatus(UUID organizationId, PlatformServiceStatus status);
}
