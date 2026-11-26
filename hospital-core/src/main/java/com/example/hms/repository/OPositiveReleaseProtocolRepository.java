package com.example.hms.repository;

import com.example.hms.model.OPositiveReleaseProtocol;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OPositiveReleaseProtocolRepository extends JpaRepository<OPositiveReleaseProtocol, UUID> {

    Optional<OPositiveReleaseProtocol> findByHospital_Id(UUID hospitalId);
}
