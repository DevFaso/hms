package com.example.hms.repository;

import com.example.hms.model.BpaProtocol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BpaProtocolRepository extends JpaRepository<BpaProtocol, UUID> {

    Optional<BpaProtocol> findByProtocolCodeAndActiveTrue(String protocolCode);
}
