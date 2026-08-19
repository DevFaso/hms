package com.example.hms.repository.platform;

import com.example.hms.enums.OrganizationRegion;
import com.example.hms.model.platform.RegionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RegionPolicyRepository extends JpaRepository<RegionPolicy, OrganizationRegion> {
}
