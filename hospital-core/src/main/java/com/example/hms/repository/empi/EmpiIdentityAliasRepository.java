package com.example.hms.repository.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.model.empi.EmpiIdentityAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmpiIdentityAliasRepository extends JpaRepository<EmpiIdentityAlias, UUID> {

    @org.springframework.data.jpa.repository.Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END "
        + "FROM EmpiIdentityAlias a WHERE a.aliasType = :aliasType AND LOWER(a.aliasValue) = LOWER(CAST(:aliasValue AS string)) "
        + "AND a.nationalUniqueIdPatientId IS NULL")
    boolean existsByAliasTypeAndAliasValueIgnoreCase(@org.springframework.data.repository.query.Param("aliasType") EmpiAliasType aliasType,
        @org.springframework.data.repository.query.Param("aliasValue") String aliasValue);

    @org.springframework.data.jpa.repository.Query("SELECT a FROM EmpiIdentityAlias a "
        + "WHERE a.aliasType = :aliasType AND LOWER(a.aliasValue) = LOWER(CAST(:aliasValue AS string)) "
        + "AND a.nationalUniqueIdPatientId IS NULL")
    Optional<EmpiIdentityAlias> findByAliasTypeAndAliasValueIgnoreCase(@org.springframework.data.repository.query.Param("aliasType") EmpiAliasType aliasType,
        @org.springframework.data.repository.query.Param("aliasValue") String aliasValue);

    /** Internal Patient-authorized IU maintenance; never a public identifier lookup. */
    Optional<EmpiIdentityAlias> findByNationalUniqueIdPatientId(UUID patientId);

    java.util.List<EmpiIdentityAlias> findByMasterIdentity_Id(UUID masterIdentityId);
}
