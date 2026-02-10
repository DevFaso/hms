package com.example.hms.repository;

import com.example.hms.enums.SignatureStatus;
import com.example.hms.enums.SignatureType;
import com.example.hms.model.signature.DigitalSignature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for DigitalSignature entities.
 * Story #17: Generic Report Signing API
 */
@Repository
public interface DigitalSignatureRepository extends JpaRepository<DigitalSignature, UUID> {

    /**
     * Find signature by report ID and report type
     */
    Optional<DigitalSignature> findByReportIdAndReportType(UUID reportId, SignatureType reportType);

    /**
     * Find all signatures for a specific report (multiple signatures possible)
     */
    List<DigitalSignature> findByReportIdAndReportTypeOrderBySignatureDateTimeDesc(
        UUID reportId, SignatureType reportType);

    /**
     * Find active (SIGNED) signatures for a specific report
     */
    @Query("SELECT ds FROM DigitalSignature ds WHERE ds.reportId = :reportId " +
           "AND ds.reportType = :reportType AND ds.status = 'SIGNED' " +
           "ORDER BY ds.signatureDateTime DESC")
    List<DigitalSignature> findActiveSignaturesByReportId(
        @Param("reportId") UUID reportId, 
        @Param("reportType") SignatureType reportType);

    /**
     * Find all signatures by a specific staff member
     */
    List<DigitalSignature> findBySignedBy_IdOrderBySignatureDateTimeDesc(UUID staffId);

    /**
     * Find signatures by staff member and status
     */
    List<DigitalSignature> findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(
        UUID staffId, SignatureStatus status);

    /**
     * Find signatures by status and report type
     */
    List<DigitalSignature> findByStatusAndReportTypeOrderBySignatureDateTimeDesc(
        SignatureStatus status, SignatureType reportType);

    /**
     * Find signatures within a date range
     */
    @Query("SELECT ds FROM DigitalSignature ds WHERE ds.signatureDateTime BETWEEN :startDate AND :endDate " +
           "ORDER BY ds.signatureDateTime DESC")
    List<DigitalSignature> findBySignatureDateTimeBetween(
        @Param("startDate") LocalDateTime startDate, 
        @Param("endDate") LocalDateTime endDate);

    /**
     * Find signatures by hospital
     */
    List<DigitalSignature> findByHospital_IdOrderBySignatureDateTimeDesc(UUID hospitalId);

    /**
     * Find signatures by hospital and status
     */
    List<DigitalSignature> findByHospital_IdAndStatusOrderBySignatureDateTimeDesc(
        UUID hospitalId, SignatureStatus status);

    /**
     * Find signatures by hospital, report type, and date range
     */
    @Query("SELECT ds FROM DigitalSignature ds WHERE ds.hospital.id = :hospitalId " +
           "AND ds.reportType = :reportType " +
           "AND ds.signatureDateTime BETWEEN :startDate AND :endDate " +
           "ORDER BY ds.signatureDateTime DESC")
    List<DigitalSignature> findByHospitalReportTypeAndDateRange(
        @Param("hospitalId") UUID hospitalId,
        @Param("reportType") SignatureType reportType,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate);

    /**
     * Find signatures by hash (for verification)
     */
    Optional<DigitalSignature> findBySignatureHash(String signatureHash);

    /**
     * Check if a report has been signed by specific staff member
     */
    @Query("SELECT CASE WHEN COUNT(ds) > 0 THEN true ELSE false END " +
           "FROM DigitalSignature ds WHERE ds.reportId = :reportId " +
           "AND ds.reportType = :reportType AND ds.signedBy.id = :staffId " +
           "AND ds.status = 'SIGNED'")
    boolean existsSignedByStaff(
        @Param("reportId") UUID reportId,
        @Param("reportType") SignatureType reportType,
        @Param("staffId") UUID staffId);

    /**
     * Check if a report has any valid signatures
     */
    @Query("SELECT CASE WHEN COUNT(ds) > 0 THEN true ELSE false END " +
           "FROM DigitalSignature ds WHERE ds.reportId = :reportId " +
           "AND ds.reportType = :reportType AND ds.status = 'SIGNED' " +
           "AND (ds.expiresAt IS NULL OR ds.expiresAt > CURRENT_TIMESTAMP)")
    boolean hasValidSignature(
        @Param("reportId") UUID reportId,
        @Param("reportType") SignatureType reportType);

    /**
     * Find expired signatures that need status update
     */
    @Query("SELECT ds FROM DigitalSignature ds WHERE ds.status = 'SIGNED' " +
           "AND ds.expiresAt IS NOT NULL AND ds.expiresAt < CURRENT_TIMESTAMP")
    List<DigitalSignature> findExpiredSignatures();

    /**
     * Count signatures by staff member and status
     */
    long countBySignedBy_IdAndStatus(UUID staffId, SignatureStatus status);

    /**
     * Count signatures by hospital and date range
     */
    @Query("SELECT COUNT(ds) FROM DigitalSignature ds WHERE ds.hospital.id = :hospitalId " +
           "AND ds.signatureDateTime BETWEEN :startDate AND :endDate")
    long countByHospitalAndDateRange(
        @Param("hospitalId") UUID hospitalId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate);

    /**
     * Find most recent signature for a report
     */
    @Query("SELECT ds FROM DigitalSignature ds WHERE ds.reportId = :reportId " +
           "AND ds.reportType = :reportType " +
           "ORDER BY ds.signatureDateTime DESC LIMIT 1")
    Optional<DigitalSignature> findMostRecentSignature(
        @Param("reportId") UUID reportId,
        @Param("reportType") SignatureType reportType);
}
