package com.example.hms.upload;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.UploadRecord;
import com.example.hms.repository.UploadRecordRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.storage.FileStorage;
import com.example.hms.storage.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The record of every stored upload and the state of its malware check.
 *
 * <p>A service that has put a file in the storage port calls {@link #record}:
 * the file is scanned ({@link UploadScanner}) and its record written, in a
 * transaction of its own so the record of a refused file outlives the request
 * that brought it. A path that serves a stored file calls
 * {@link #requireServable} first: a file whose check is pending, or that the
 * check refused, is kept and answered as not available.
 *
 * <p>A file with no record (stored before V384, or written by the server
 * itself) is served as before.
 */
@Slf4j
@Service
public class UploadLedger {

    private static final int RESCAN_BATCH = 50;

    private final UploadRecordRepository records;
    private final UploadScanner scanner;
    private final FileStorage storage;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    public UploadLedger(UploadRecordRepository records, UploadScanner scanner, FileStorage storage,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.clock = clock;
        this.records = records;
        this.scanner = scanner;
        this.storage = storage;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * A file just written to the storage port.
     *
     * @param uploaderId the sender when the caller knows it; else the request's principal
     * @param hospitalId the hospital the file belongs to when the caller knows it; else the acting one
     */
    public record StoredUpload(String storageKey, UploadKind kind, String contentType, long sizeBytes,
                               String sha256, UUID uploaderId, UUID hospitalId) {
    }

    /**
     * Scan a stored file and write its record.
     *
     * @return the state it is kept in; the caller refuses the upload on
     *         {@link UploadScanStatus#INFECTED} (the file stays, unserved)
     */
    public UploadScanStatus record(StoredUpload upload) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        UUID uploaderId = upload.uploaderId() != null ? upload.uploaderId() : context.getPrincipalUserId();
        UUID hospitalId = upload.hospitalId() != null ? upload.hospitalId() : ActingScopeResolver.pinnedHospitalIdOf(context);
        UploadScanStatus status = statusOf(verdictOn(upload.storageKey(), upload.contentType(), upload.sizeBytes()));
        UploadRecord row = UploadRecord.builder()
            .storageKey(upload.storageKey())
            .uploadKind(upload.kind())
            .contentType(upload.contentType())
            .sizeBytes(upload.sizeBytes())
            .sha256(upload.sha256())
            .uploadedByUserId(uploaderId)
            .hospitalId(hospitalId)
            .scanStatus(status)
            .scanEngine(scanner.engine())
            .scannedAt(status == UploadScanStatus.PENDING ? null : LocalDateTime.now(clock))
            .build();
        ownTransaction.executeWithoutResult(tx -> records.save(row));
        if (status != UploadScanStatus.CLEAN) {
            log.warn("[UPLOAD] a {} upload is kept unserved: scan status {}", upload.kind(), status);
        }
        return status;
    }

    /**
     * Refuse to serve a stored file whose check is not clean.
     *
     * @param storageKey the key in the storage port
     * @throws ResourceNotFoundException for a pending or refused file
     */
    public void requireServable(String storageKey) {
        Optional<UploadRecord> row = records.findByStorageKey(storageKey);
        if (row.isEmpty() || row.get().getScanStatus() == UploadScanStatus.CLEAN) {
            return;
        }
        if (row.get().getScanStatus() == UploadScanStatus.INFECTED) {
            throw new ResourceNotFoundException("file.upload.blocked");
        }
        throw new ResourceNotFoundException("file.upload.pendingCheck");
    }

    /**
     * Check again the files whose scan has not answered, the one tried longest ago first. A file still without
     * an answer is touched, so it goes to the back of the queue and never holds back the newer ones.
     *
     * @return how many left the pending state
     */
    public int rescanPending() {
        List<UploadRecord> pending = records.findByScanStatusLeastRecentlyTried(
            UploadScanStatus.PENDING, PageRequest.of(0, RESCAN_BATCH));
        int settled = 0;
        for (UploadRecord row : pending) {
            if (!isStored(row.getStorageKey())) {
                // Nothing left to serve or to scan: the owning row was removed with its file.
                ownTransaction.executeWithoutResult(tx -> records.deleteById(row.getId()));
                settled++;
                continue;
            }
            UploadScanStatus status = statusOf(verdictOn(row.getStorageKey(), row.getContentType(), row.getSizeBytes()));
            if (status != UploadScanStatus.PENDING) {
                row.setScanStatus(status);
                row.setScanEngine(scanner.engine());
                row.setScannedAt(LocalDateTime.now(clock));
                ownTransaction.executeWithoutResult(tx -> records.save(row));
                settled++;
            } else {
                row.setUpdatedAt(LocalDateTime.now(clock));
                ownTransaction.executeWithoutResult(tx -> records.save(row));
            }
        }
        return settled;
    }

    private boolean isStored(String storageKey) {
        try {
            Optional<StoredObject> stored = storage.get(storageKey);
            if (stored.isPresent()) {
                stored.get().close();
            }
            return stored.isPresent();
        } catch (IOException | RuntimeException unreadable) {
            // Unknown, not gone: the record stays and the next run asks again.
            return true;
        }
    }

    private UploadScanner.Verdict verdictOn(String storageKey, String contentType, long sizeBytes) {
        try {
            UploadScanner.Verdict verdict = scanner.scan(new UploadScanner.Upload(storageKey, contentType, sizeBytes,
                () -> storage.get(storageKey)
                    .orElseThrow(() -> new java.io.FileNotFoundException(storageKey))
                    .content()));
            return verdict != null ? verdict : UploadScanner.Verdict.UNKNOWN;
        } catch (IOException | RuntimeException failed) {
            log.warn("[UPLOAD] the {} scanner could not answer: {}", scanner.engine(), failed.getMessage());
            return UploadScanner.Verdict.UNKNOWN;
        }
    }

    private static UploadScanStatus statusOf(UploadScanner.Verdict verdict) {
        return switch (verdict) {
            case CLEAN -> UploadScanStatus.CLEAN;
            case INFECTED -> UploadScanStatus.INFECTED;
            case UNKNOWN -> UploadScanStatus.PENDING;
        };
    }
}
