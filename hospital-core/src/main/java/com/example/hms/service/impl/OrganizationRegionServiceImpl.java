package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationRegion;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.OrganizationRegionResponseDTO;
import com.example.hms.payload.dto.superadmin.OrganizationRegionUpdateRequestDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.OrganizationRegionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OrganizationRegionServiceImpl implements OrganizationRegionService {

    private static final String ENTITY_TYPE_ORGANIZATION = "ORGANIZATION";

    private final OrganizationRepository organizationRepository;
    private final AuditEventLogService auditEventLogService;

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationRegion> listAvailableRegions() {
        return List.of(OrganizationRegion.values());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationRegionResponseDTO> listOrganizationRegions() {
        return organizationRepository.findAll().stream()
            .sorted(Comparator.comparing(Organization::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
            .map(this::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationRegionResponseDTO getOrganizationRegion(UUID organizationId) {
        return toResponse(loadOrThrow(organizationId));
    }

    @Override
    public OrganizationRegionResponseDTO updateOrganizationRegion(
        UUID organizationId, OrganizationRegionUpdateRequestDTO request
    ) {
        Organization org = loadOrThrow(organizationId);
        OrganizationRegion previous = org.getRegion();
        OrganizationRegion next = request.getRegion();

        // Idempotent — a noop update still emits an audit event so the
        // operator's intent is recorded but does not bump updated_at.
        if (previous != next) {
            org.setRegion(next);
            organizationRepository.save(org);
        }

        recordAudit(org, previous, next, request.getReason());
        return toResponse(org);
    }

    private Organization loadOrThrow(UUID organizationId) {
        return organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Organization not found: " + organizationId));
    }

    private OrganizationRegionResponseDTO toResponse(Organization org) {
        return OrganizationRegionResponseDTO.builder()
            .organizationId(org.getId())
            .organizationName(org.getName())
            .organizationCode(org.getCode())
            .region(org.getRegion())
            .build();
    }

    private void recordAudit(Organization org, OrganizationRegion previous,
                             OrganizationRegion next, String reason) {
        try {
            HospitalContext context = HospitalContextHolder.getContextOrEmpty();
            String description = "Organization region "
                + (previous == next ? "reaffirmed at " : "changed from " + previous + " to ") + next
                + (reason != null && !reason.isBlank() ? ": " + reason.trim() : "");
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(context.getPrincipalUserId())
                .userName(context.getPrincipalUsername())
                .eventType(AuditEventType.ORGANIZATION_REGION_UPDATED)
                .eventDescription(description)
                .resourceId(org.getId().toString())
                .resourceName(org.getName())
                .entityType(ENTITY_TYPE_ORGANIZATION)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            // Audit failure must not roll back the region update — match
            // the pattern in OrganizationLifecycleServiceImpl.recordAudit.
            log.error("[ORG-REGION] Failed to record audit event for org {}",
                org.getId(), ex);
        }
    }
}
