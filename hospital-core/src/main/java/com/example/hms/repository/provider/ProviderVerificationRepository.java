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
import java.util.Optional;
import java.util.UUID;

/**
 * Provider onboarding evidence (V180). A plain {@link JpaRepository}: the
 * table is platform data, read and written by a verified super-admin only.
 */
public interface ProviderVerificationRepository extends JpaRepository<ProviderVerification, UUID> {

    /** The facility's latest verification, whatever its status. */
    Optional<ProviderVerification> findFirstByHospital_IdOrderByCreatedAtDesc(UUID hospitalId);

    /** Does the facility hold a verification in this status? The lifecycle restore guard (AC-4). */
    boolean existsByHospital_IdAndStatus(UUID hospitalId, ProviderVerificationStatus status);

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
     * the same facility supersedes.
     */
    @Query(value = """
        SELECT v FROM ProviderVerification v JOIN FETCH v.hospital h
        WHERE h.facilityType IN :types
          AND (:status IS NULL OR v.status = :status)
          AND NOT EXISTS (
              SELECT 1 FROM ProviderVerification newer
              WHERE newer.hospital = v.hospital AND newer.createdAt > v.createdAt)
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
}
