package com.example.hms.repository.empi;

import com.example.hms.model.empi.EmpiMasterIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmpiMasterIdentityRepository extends JpaRepository<EmpiMasterIdentity, UUID> {

    Optional<EmpiMasterIdentity> findByEmpiNumberIgnoreCase(String empiNumber);

    Optional<EmpiMasterIdentity> findByPatientId(UUID patientId);

    boolean existsByEmpiNumberIgnoreCase(String empiNumber);

    Optional<EmpiMasterIdentity> findByAliasesAliasTypeAndAliasesAliasValue(
        com.example.hms.enums.empi.EmpiAliasType aliasType,
        String aliasValue
    );
}
