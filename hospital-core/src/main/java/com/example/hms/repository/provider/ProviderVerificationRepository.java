package com.example.hms.repository.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.model.provider.ProviderVerification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider onboarding evidence (V180). A plain {@link JpaRepository}: the
 * table is platform data, written by a verified super-admin only, and read
 * by the provider directory (verified facilities only) and by a provider's
 * own profile page.
 */
public interface ProviderVerificationRepository extends JpaRepository<ProviderVerification, UUID> {

    /**
     * The facility's latest verification in this status: with VERIFIED, the
     * identity the platform verified (V180's partial unique index allows at
     * most one SUBMITTED-or-VERIFIED row per facility).
     */
    Optional<ProviderVerification> findFirstByHospital_IdAndStatusOrderByCreatedAtDesc(
        UUID hospitalId, ProviderVerificationStatus status);

    /** The facility's latest verification, whatever its status. */
    Optional<ProviderVerification> findFirstByHospital_IdOrderByCreatedAtDesc(UUID hospitalId);

    /** Every verification of the facility, newest first: the super-admin's verification history. */
    List<ProviderVerification> findByHospital_IdOrderByCreatedAtDescIdDesc(UUID hospitalId);

    /** Does the facility hold a verification in this status? The lifecycle restore guard (AC-4). */
    boolean existsByHospital_IdAndStatus(UUID hospitalId, ProviderVerificationStatus status);

    /** Has the facility ever held a verification in one of these statuses? The delete guard. */
    boolean existsByHospital_IdAndStatusIn(UUID hospitalId, Collection<ProviderVerificationStatus> statuses);

    /** Another facility already VERIFIED with this (authority, licence) pair (AC-3). */
    @Query("""
        SELECT COUNT(v) > 0 FROM ProviderVerification v
        WHERE v.status = com.example.hms.enums.ProviderVerificationStatus.VERIFIED
          AND v.hospital.id <> :hospitalId
          AND v.licenceAuthority = :authority
          AND v.licenceNumber = :licence
        """)
    boolean existsVerifiedLicenceElsewhere(@Param("hospitalId") UUID hospitalId,
                                           @Param("authority") String authority,
                                           @Param("licence") String licence);

    /** Another facility already VERIFIED with this RCCM or this IFU number (AC-3). */
    @Query("""
        SELECT COUNT(v) > 0 FROM ProviderVerification v
        WHERE v.status = com.example.hms.enums.ProviderVerificationStatus.VERIFIED
          AND v.hospital.id <> :hospitalId
          AND (v.rccmNumber = :rccm OR v.ifuNumber = :ifu)
        """)
    boolean existsVerifiedBusinessElsewhere(@Param("hospitalId") UUID hospitalId,
                                            @Param("rccm") String rccm,
                                            @Param("ifu") String ifu);

    /**
     * The latest verification of every provider facility of these types,
     * narrowed to one status when given. "Latest" is the row no newer row of
     * the same facility supersedes. Ordered newest first, then by id, so
     * pages never repeat or skip a row; callers pass an UNSORTED pageable
     * (a sort from the pageable would be appended after this one).
     */
    @Query(value = """
        SELECT v FROM ProviderVerification v JOIN FETCH v.hospital h
        WHERE h.facilityType IN :types
          AND (:status IS NULL OR v.status = :status)
          AND NOT EXISTS (
              SELECT 1 FROM ProviderVerification newer
              WHERE newer.hospital = v.hospital AND newer.createdAt > v.createdAt)
        ORDER BY v.createdAt DESC, v.id DESC
        """,
        countQuery = """
        SELECT COUNT(v) FROM ProviderVerification v JOIN v.hospital h
        WHERE h.facilityType IN :types
          AND (:status IS NULL OR v.status = :status)
          AND NOT EXISTS (
              SELECT 1 FROM ProviderVerification newer
              WHERE newer.hospital = v.hospital AND newer.createdAt > v.createdAt)
        """)
    Page<ProviderVerification> findLatestByFacilityTypes(@Param("types") Collection<FacilityType> types,
                                                         @Param("status") ProviderVerificationStatus status,
                                                         Pageable pageable);

    /**
     * The provider directory (plan §6.5, AC-14): facilities of these types
     * whose CURRENT verification is VERIFIED and that are active and ACTIVE in
     * their lifecycle, optionally narrowed to a name pattern (already lower-case,
     * LIKE-escaped with a backslash, wrapped in {@code %}). V180's partial
     * unique index keeps at most one SUBMITTED-or-VERIFIED row per facility,
     * so a VERIFIED row is the current one. Ordered by name, then id.
     */
    @Query("""
        SELECT v FROM ProviderVerification v JOIN FETCH v.hospital h
        WHERE v.status = com.example.hms.enums.ProviderVerificationStatus.VERIFIED
          AND h.facilityType IN :types
          AND h.active = TRUE
          AND h.lifecycleState = com.example.hms.enums.HospitalLifecycleState.ACTIVE
          AND (:namePattern IS NULL OR LOWER(CAST(h.name AS string)) LIKE :namePattern ESCAPE '\\')
        ORDER BY LOWER(CAST(h.name AS string)), h.id
        """)
    List<ProviderVerification> findDirectory(@Param("types") Collection<FacilityType> types,
                                             @Param("namePattern") String namePattern,
                                             Pageable pageable);
}
