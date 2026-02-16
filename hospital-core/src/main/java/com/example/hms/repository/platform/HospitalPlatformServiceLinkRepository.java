package com.example.hms.repository.platform;

import com.example.hms.model.platform.HospitalPlatformServiceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HospitalPlatformServiceLinkRepository extends JpaRepository<HospitalPlatformServiceLink, UUID> {

    boolean existsByHospitalIdAndOrganizationServiceId(UUID hospitalId, UUID organizationServiceId);

    Optional<HospitalPlatformServiceLink> findByHospitalIdAndOrganizationServiceId(UUID hospitalId, UUID organizationServiceId);

    List<HospitalPlatformServiceLink> findByHospitalId(UUID hospitalId);

    List<HospitalPlatformServiceLink> findByOrganizationServiceId(UUID organizationServiceId);

    long countByEnabledFalse();
}
