package com.example.hms.service.scheduled;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.tenant.TenantArchiveEncryptionService;
import com.example.hms.service.tenant.TenantExportPackager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Per-organization purge step extracted from {@link TenantPurgeJob} so each
 * call runs in its own transaction (Propagation.REQUIRES_NEW).
 *
 * <p>This split is load-bearing: the previous design wrapped the entire
 * sweep in a single {@code @Transactional} method on the job, so a single
 * org's save() failure could mark the whole transaction rollback-only and
 * silently revert the successful transitions earlier in the loop. With each
 * purge isolated in its own transaction, "one org failure does not break
 * the rest" actually holds.
 */
@Component
@Slf4j
public class TenantPurgeExecutor {

    private final OrganizationRepository organizationRepository;
    private final AuditEventLogService auditEventLogService;
    private final TenantExportPackager exportPackager;
    private final TenantArchiveEncryptionService archiveEncryption;

    @Value("${hms.tenant-archive.output-dir:#{systemProperties['java.io.tmpdir']}/hms-tenant-archives}")
    private String outputDir;

    public TenantPurgeExecutor(OrganizationRepository organizationRepository,
                               AuditEventLogService auditEventLogService,
                               TenantExportPackager exportPackager,
                               TenantArchiveEncryptionService archiveEncryption) {
        this.organizationRepository = organizationRepository;
        this.auditEventLogService = auditEventLogService;
        this.exportPackager = exportPackager;
        this.archiveEncryption = archiveEncryption;
    }

    /**
     * Transition a single organization to {@code PURGED} in an isolated
     * transaction. MVP-c batch wires in the GDPR packager + encrypted
     * archive: package → encrypt → upload (today: configured filesystem
     * dir) BEFORE the state flip, so a packaging failure aborts the
     * transition with a {@code TENANT_PURGE_PACKAGING_FAILED} audit and
     * the org stays in {@code PENDING_PURGE} for the operator to retry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executePurge(Organization org, boolean executeDeletion) {
        TenantArchiveEncryptionService.EncryptionResult encryptedArchive;
        try {
            encryptedArchive = packageAndEncrypt(org);
        } catch (IOException ex) {
            log.error("[TENANT-PURGE] Packaging failed for org {}: {}", org.getId(), ex.getMessage(), ex);
            recordPackagingFailure(org, ex);
            // Abort — caller (TenantPurgeJob) will see this org again on
            // the next sweep with the original purgeScheduledFor still set.
            return;
        }

        if (executeDeletion) {
            log.warn("[TENANT-PURGE] execute-deletion=true was set but cascade data deletion is not implemented "
                + "in this batch for org {}. Archive at {} captures org+hospital metadata; per-table dumps "
                + "ship in a follow-up. Transition to PURGED will proceed; row-level deletion deferred.",
                org.getId(), encryptedArchive.outputPath());
        }

        org.setLifecycleState(OrganizationLifecycleState.PURGED);
        org.setActive(false);
        org.setPurgedAt(Instant.now());
        organizationRepository.save(org);

        recordAuditPackaged(org, encryptedArchive);
        recordAuditPurged(org, encryptedArchive);
        log.info("[TENANT-PURGE] Org {} ({}) transitioned to PURGED — archive {}",
            org.getId(), org.getCode(), encryptedArchive.outputPath());
    }

    private TenantArchiveEncryptionService.EncryptionResult packageAndEncrypt(Organization org) throws IOException {
        String stem = "org-" + org.getId() + "-" + Instant.now().toEpochMilli();
        Path plaintext = Paths.get(outputDir, stem + ".zip");
        Path encrypted = Paths.get(outputDir, stem + ".zip.enc");

        TenantExportPackager.PackageResult packaged = exportPackager.packageOrganization(org, plaintext);
        log.info("[TENANT-PURGE] Packaged {} for org {}", packaged.describe(), org.getId());

        TenantArchiveEncryptionService.EncryptionResult encryptedResult =
            archiveEncryption.encryptArchive(plaintext, encrypted);
        // Best-effort wipe of the plaintext so the archive at rest is the
        // encrypted form. The envelope manifest stays alongside the .enc.
        try {
            java.nio.file.Files.deleteIfExists(plaintext);
        } catch (IOException cleanup) {
            log.warn("[TENANT-PURGE] Could not delete plaintext archive {} after encryption: {}",
                plaintext, cleanup.getMessage());
        }
        return encryptedResult;
    }

    private void recordAuditPackaged(Organization org,
                                     TenantArchiveEncryptionService.EncryptionResult result) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userName("system:tenant-purge-job")
                .eventType(AuditEventType.TENANT_PURGE_PACKAGED)
                .eventDescription("Tenant archive packaged + encrypted for organization "
                    + org.getCode() + " at " + result.outputPath()
                    + " (mode=" + result.mode() + ", cipher=" + result.cipher() + ")")
                .resourceId(org.getId().toString())
                .resourceName(org.getName())
                .entityType("ORGANIZATION")
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[TENANT-PURGE] Audit emission failed for packaged org {}", org.getId(), ex);
        }
    }

    private void recordAuditPurged(Organization org,
                                   TenantArchiveEncryptionService.EncryptionResult result) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userName("system:tenant-purge-job")
                .eventType(AuditEventType.TENANT_PURGED)
                .eventDescription("Scheduled purge executed for organization " + org.getCode()
                    + "; archive at " + result.outputPath())
                .resourceId(org.getId().toString())
                .resourceName(org.getName())
                .entityType("ORGANIZATION")
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[TENANT-PURGE] Audit emission failed for purged org {}", org.getId(), ex);
        }
    }

    private void recordPackagingFailure(Organization org, IOException ex) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userName("system:tenant-purge-job")
                .eventType(AuditEventType.TENANT_PURGE_PACKAGING_FAILED)
                .eventDescription("Packaging failed for organization " + org.getCode()
                    + ": " + ex.getMessage())
                .resourceId(org.getId().toString())
                .resourceName(org.getName())
                .entityType("ORGANIZATION")
                .status(AuditStatus.FAILURE)
                .build());
        } catch (RuntimeException auditEx) {
            log.error("[TENANT-PURGE] Audit emission failed for packaging failure on org {}",
                org.getId(), auditEx);
        }
    }
}
