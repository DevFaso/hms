package com.example.hms.repository.platform;

import com.example.hms.model.platform.DepartmentPlatformServiceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DepartmentPlatformServiceLinkRepository extends JpaRepository<DepartmentPlatformServiceLink, UUID> {

    boolean existsByDepartmentIdAndOrganizationServiceId(UUID departmentId, UUID organizationServiceId);

    Optional<DepartmentPlatformServiceLink> findByDepartmentIdAndOrganizationServiceId(UUID departmentId, UUID organizationServiceId);

    List<DepartmentPlatformServiceLink> findByDepartmentId(UUID departmentId);

    List<DepartmentPlatformServiceLink> findByOrganizationServiceId(UUID organizationServiceId);
        long countByEnabledFalse();
}
