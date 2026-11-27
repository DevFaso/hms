package com.example.hms.repository;

import com.example.hms.model.UploadRecord;
import com.example.hms.upload.UploadScanStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadRecordRepository extends JpaRepository<UploadRecord, UUID> {

    Optional<UploadRecord> findByStorageKey(String storageKey);

    @Query("select count(r) from UploadRecord r where r.uploadedByUserId = :userId and r.createdAt > :since")
    long countByUserSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    @Query("select coalesce(sum(r.sizeBytes), 0) from UploadRecord r "
        + "where r.uploadedByUserId = :userId and r.createdAt > :since")
    long bytesByUserSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    @Query("select count(r) from UploadRecord r where r.hospitalId = :hospitalId and r.createdAt > :since")
    long countByHospitalSince(@Param("hospitalId") UUID hospitalId, @Param("since") LocalDateTime since);

    @Query("select coalesce(sum(r.sizeBytes), 0) from UploadRecord r "
        + "where r.hospitalId = :hospitalId and r.createdAt > :since")
    long bytesByHospitalSince(@Param("hospitalId") UUID hospitalId, @Param("since") LocalDateTime since);

    /** The rows of a status, the one tried longest ago first (a pending row is touched on each try). */
    @Query("select r from UploadRecord r where r.scanStatus = :status order by r.updatedAt asc, r.createdAt asc")
    List<UploadRecord> findByScanStatusLeastRecentlyTried(@Param("status") UploadScanStatus status, Pageable page);
}
