package com.example.hms.repository;

import com.example.hms.model.platform.FhirBulkExportFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FhirBulkExportFileRepository extends JpaRepository<FhirBulkExportFile, UUID> {

    List<FhirBulkExportFile> findByJob_IdOrderByResourceTypeAsc(UUID jobId);

    /** Download lookup — the DB row, not client input, names the file on disk. */
    Optional<FhirBulkExportFile> findByJob_IdAndFileName(UUID jobId, String fileName);
}
