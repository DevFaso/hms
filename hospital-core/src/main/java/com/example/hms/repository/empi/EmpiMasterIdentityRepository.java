package com.example.hms.repository.empi;

import com.example.hms.model.empi.EmpiMasterIdentity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * The identity row under a write lock, for a merge about to decide.
     *
     * <p>{@link #claimForMerge} serialises two merges of the SAME retiring
     * identity, but A&lt;-B and B&lt;-A retire different rows, so each claim
     * wins and both apply. A merge therefore locks BOTH of its identities
     * first, always in ascending id order so two merges of one pair cannot
     * each hold the row the other is waiting for.
     *
     * <p>The entity returned is the instance this persistence context already
     * holds, with the values it was loaded with; decide from
     * {@link #findStatusById} under the lock, not from it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM EmpiMasterIdentity i WHERE i.id = :id")
    Optional<EmpiMasterIdentity> findWithLockById(@Param("id") UUID id);

    /**
     * The status the database holds now, read past this persistence
     * context's snapshot. Null when the row does not exist.
     */
    @Query("SELECT i.status FROM EmpiMasterIdentity i WHERE i.id = :id")
    com.example.hms.enums.empi.EmpiIdentityStatus findStatusById(@Param("id") UUID id);

    Optional<EmpiMasterIdentity> findByAliasesAliasTypeAndAliasesAliasValue(
        com.example.hms.enums.empi.EmpiAliasType aliasType,
        String aliasValue
    );
}
