package com.example.hms.repository;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.RhFactor;
import com.example.hms.model.BloodUnit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BloodUnitRepository extends JpaRepository<BloodUnit, UUID> {

    /** The label on the bag is unique per facility — enforced by uq_blood_unit_number. */
    Optional<BloodUnit> findByHospital_IdAndUnitNumber(UUID hospitalId, String unitNumber);

    List<BloodUnit> findByHospital_IdOrderByExpiresOnAsc(UUID hospitalId);

    List<BloodUnit> findByHospital_IdAndStatusOrderByExpiresOnAsc(UUID hospitalId, BloodUnitStatus status);

    List<BloodUnit> findByRequest_IdOrderByUnitNumberAsc(UUID requestId);

    /**
     * Units that can still be committed to a patient: on hand, in date, and not
     * already reserved. Ordered by expiry so the shortest-dated unit is offered
     * first — the FEFO rule the pharmacy stock ledger already applies, and the
     * reason unusable blood gets discarded when it is not applied.
     */
    @Query("SELECT u FROM BloodUnit u WHERE u.hospital.id = :hospitalId "
        + "AND u.status IN (com.example.hms.enums.BloodUnitStatus.AVAILABLE, "
        + "com.example.hms.enums.BloodUnitStatus.RETURNED) "
        + "AND u.expiresOn > :today ORDER BY u.expiresOn ASC")
    List<BloodUnit> findAssignable(@Param("hospitalId") UUID hospitalId, @Param("today") LocalDate today);

    /**
     * Row lock on the bag, so a release, a crossmatch result and a hang of one bag take turns and
     * each sees what the one before wrote. Taken before the bag is read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM BloodUnit u WHERE u.id = :id")
    Optional<BloodUnit> lockById(@Param("id") UUID id);

    /** An AVAILABLE, unexpired bag of this kind at this hospital; a bag's last day counts as expired, as everywhere here. */
    @Query("SELECT (COUNT(u) > 0) FROM BloodUnit u WHERE u.hospital.id = :hospitalId "
        + "AND u.productType = :product AND u.aboGroup = :abo AND u.rhFactor = :rh "
        + "AND u.status = com.example.hms.enums.BloodUnitStatus.AVAILABLE AND u.expiresOn > :today")
    boolean existsAvailable(@Param("hospitalId") UUID hospitalId,
                            @Param("product") BloodProductType product,
                            @Param("abo") AboGroup abo,
                            @Param("rh") RhFactor rh,
                            @Param("today") LocalDate today);
}
