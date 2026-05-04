package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationRegion;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.platform.RegionPolicy;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.RegionPolicyResponseDTO;
import com.example.hms.payload.dto.superadmin.RegionPolicyUpdateRequestDTO;
import com.example.hms.repository.platform.RegionPolicyRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.RegionPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RegionPolicyServiceImpl implements RegionPolicyService {

    private static final String ENTITY_TYPE = "REGION_POLICY";
    private static final String SYSTEM_ACTOR = "system";

    private final RegionPolicyRepository regionPolicyRepository;
    private final AuditEventLogService auditEventLogService;

    @Override
    @Transactional(readOnly = true)
    public List<RegionPolicyResponseDTO> listAll() {
        return regionPolicyRepository.findAll().stream()
            .sorted(Comparator.comparing(p -> p.getRegion().name()))
            .map(this::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RegionPolicyResponseDTO get(OrganizationRegion region) {
        return toResponse(loadOrThrow(region));
    }

    @Override
    public RegionPolicyResponseDTO update(OrganizationRegion region, RegionPolicyUpdateRequestDTO request) {
        RegionPolicy policy = loadOrThrow(region);
        Integer prevRetention = policy.getRetentionDays();
        String prevExport = policy.getDefaultExportFormat();
        String prevTarget = policy.getTargetDeploymentUrl();

        // Null fields in the request clear the override; non-null replace it.
        // Both branches are explicit so the caller's intent is unambiguous —
        // we deliberately do NOT treat null as "leave unchanged" here, because
        // that would mean every update needs a full echo of the current state.
        Integer newRetention = request != null ? request.getRetentionDays() : null;
        String newExport = request != null ? trimToNull(request.getDefaultExportFormat()) : null;
        String newTarget = request != null ? trimToNull(request.getTargetDeploymentUrl()) : null;

        // Copilot review fix — compare BEFORE mutating. The previous
        // implementation set updatedAt/updatedBy unconditionally and
        // saved unconditionally, so a no-op PUT still bumped the row's
        // "last modified" stamp. Compute the no-op early and short-circuit.
        boolean unchanged = Objects.equals(prevRetention, newRetention)
            && Objects.equals(prevExport, newExport)
            && Objects.equals(prevTarget, newTarget);
        if (unchanged) {
            return toResponse(policy);
        }

        policy.setRetentionDays(newRetention);
        policy.setDefaultExportFormat(newExport);
        policy.setTargetDeploymentUrl(newTarget);
        policy.setUpdatedAt(Instant.now());
        policy.setUpdatedBy(currentActorUsername());
        regionPolicyRepository.save(policy);

        recordAudit(policy, prevRetention, prevExport, prevTarget);
        return toResponse(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public Integer resolveRetentionDays(OrganizationRegion region) {
        return region == null ? null : regionPolicyRepository.findById(region)
            .map(RegionPolicy::getRetentionDays).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public String resolveDefaultExportFormat(OrganizationRegion region) {
        return region == null ? null : regionPolicyRepository.findById(region)
            .map(RegionPolicy::getDefaultExportFormat).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public String resolveTargetDeploymentUrl(OrganizationRegion region) {
        return region == null ? null : regionPolicyRepository.findById(region)
            .map(RegionPolicy::getTargetDeploymentUrl).orElse(null);
    }

    // ── helpers ───────────────────────────────────────────────────────

    private RegionPolicy loadOrThrow(OrganizationRegion region) {
        if (region == null) {
            throw new ResourceNotFoundException("Region is required");
        }
        return regionPolicyRepository.findById(region)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Region policy not seeded for: " + region));
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String trimmed = v.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String currentActorUsername() {
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        String username = ctx.getPrincipalUsername();
        return username != null && !username.isBlank() ? username : SYSTEM_ACTOR;
    }

    private UUID currentActorId() {
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        return ctx.getPrincipalUserId();
    }

    private void recordAudit(RegionPolicy policy, Integer prevRetention,
                             String prevExport, String prevTarget) {
        try {
            String description = String.format(
                "Region %s policy updated: retentionDays %s -> %s; exportFormat %s -> %s; targetDeployment %s -> %s",
                policy.getRegion(),
                prevRetention, policy.getRetentionDays(),
                prevExport, policy.getDefaultExportFormat(),
                prevTarget, policy.getTargetDeploymentUrl());
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(currentActorId())
                .userName(currentActorUsername())
                .eventType(AuditEventType.REGION_POLICY_UPDATED)
                .eventDescription(description)
                .resourceId(policy.getRegion().name())
                .resourceName(policy.getRegion().name())
                .entityType(ENTITY_TYPE)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            // Audit failures must not roll back the policy update — same
            // posture as OrganizationLifecycleService.recordAudit.
            log.error("[REGION-POLICY] Failed to record audit for {}",
                policy.getRegion(), ex);
        }
    }

    private RegionPolicyResponseDTO toResponse(RegionPolicy policy) {
        return RegionPolicyResponseDTO.builder()
            .region(policy.getRegion())
            .retentionDays(policy.getRetentionDays())
            .defaultExportFormat(policy.getDefaultExportFormat())
            .targetDeploymentUrl(policy.getTargetDeploymentUrl())
            .updatedAt(policy.getUpdatedAt())
            .updatedBy(policy.getUpdatedBy())
            .build();
    }
}
