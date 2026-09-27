package com.example.hms.repository.empi;

import com.example.hms.model.empi.EmpiMasterIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmpiMasterIdentityRepository extends JpaRepository<EmpiMasterIdentity, UUID> {

    Optional<EmpiMasterIdentity> findByEmpiNumberIgnoreCase(String empiNumber);

    Optional<EmpiMasterIdentity> findByPatientId(UUID patientId);

    boolean existsByEmpiNumberIgnoreCase(String empiNumber);

    /**
     * Claim an identity for a merge: mark it MERGED only if it is not already.
     *
     * <p>The row count is the decision. Two merges of the same identity that
     * both read it as ACTIVE (a sender retry, a second connection) both reach
     * this statement; the database serialises them on the row, the second
     * re-evaluates {@code status <> MERGED} against the first's committed
     * write and updates nothing, and its caller answers exactly as it would
     * for an identity that was already merged. A read-then-write in Java
     * cannot give that guarantee without a lock.
     *
     * @return 1 when this call made the transition, 0 when someone else had
     */
    @Modifying
    @Query("UPDATE EmpiMasterIdentity i SET i.status = :merged, i.active = false "
        + "WHERE i.id = :id AND i.status <> :merged")
    int claimForMerge(@Param("id") UUID id,
                      @Param("merged") com.example.hms.enums.empi.EmpiIdentityStatus merged);

    Optional<EmpiMasterIdentity> findByAliasesAliasTypeAndAliasesAliasValue(
        com.example.hms.enums.empi.EmpiAliasType aliasType,
        String aliasValue
    );
}
