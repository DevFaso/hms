package com.example.hms.service;

import com.example.hms.enums.SecurityPolicyApprovalStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.security.SecurityPolicyApproval;
import com.example.hms.model.security.SecurityPolicyBaseline;
import com.example.hms.payload.dto.superadmin.SecurityPolicyApprovalSummaryDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineExportDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineResponseDTO;
import com.example.hms.repository.SecurityPolicyApprovalRepository;
import com.example.hms.repository.SecurityPolicyBaselineRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityPolicyGovernanceServiceImpl implements SecurityPolicyGovernanceService {

    private static final String DEFAULT_CREATOR = "super-admin@system";
    private static final DateTimeFormatter VERSION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SecurityPolicyBaselineRepository baselineRepository;
    private final SecurityPolicyApprovalRepository approvalRepository;
    private final ObjectMapper objectMapper;

    private final AtomicLong baselineSeed = new AtomicLong();

    @Override
    @Transactional
    public SecurityPolicyBaselineResponseDTO createBaseline(SecurityPolicyBaselineRequestDTO request) {
        SecurityPolicyBaseline baseline = SecurityPolicyBaseline.builder()
            .baselineVersion(generateVersion())
            .title(resolveValue(request.getTitle(), "Security baseline"))
            .summary(request.getSummary())
            .enforcementLevel(resolveValue(request.getEnforcementLevel(), "GLOBAL"))
            .policyCount(request.getPolicyCount() != null ? request.getPolicyCount() : 0)
            .controlObjectivesJson(request.getControlObjectivesJson())
            .createdBy(resolveCreatedBy(request.getCreatedBy()))
            .build();

        SecurityPolicyBaseline saved = baselineRepository.save(baseline);
        log.info("Created security policy baseline {}", saved.getBaselineVersion());
        return toBaselineResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityPolicyApprovalSummaryDTO> listPendingApprovals() {
        return approvalRepository.findByStatusOrderByRequiredByAsc(SecurityPolicyApprovalStatus.PENDING).stream()
            .map(this::toApprovalSummary)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SecurityPolicyBaselineExportDTO exportLatestBaseline() {
        SecurityPolicyBaseline baseline = baselineRepository.findFirstByOrderByCreatedAtDesc()
            .orElseThrow(() -> new ResourceNotFoundException("security.policy.baseline.not-found"));

        Map<String, Object> exportPayload = new HashMap<>();
        exportPayload.put("baselineVersion", baseline.getBaselineVersion());
        exportPayload.put("title", baseline.getTitle());
        exportPayload.put("summary", baseline.getSummary());
        exportPayload.put("enforcementLevel", baseline.getEnforcementLevel());
        exportPayload.put("policyCount", baseline.getPolicyCount());
        exportPayload.put("controlObjectives", baseline.getControlObjectivesJson());
        exportPayload.put("createdBy", baseline.getCreatedBy());
        exportPayload.put("createdAt", baseline.getCreatedAt());

        String json;
        try {
            json = objectMapper.writeValueAsString(exportPayload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize security baseline {}", baseline.getId(), e);
            throw new IllegalStateException("Unable to export baseline", e);
        }

        return SecurityPolicyBaselineExportDTO.builder()
            .baselineVersion(baseline.getBaselineVersion())
            .fileName("security-policy-baseline-" + baseline.getBaselineVersion() + ".json")
            .contentType("application/json")
            .base64Content(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)))
            .generatedAt(OffsetDateTime.now(ZoneOffset.UTC))
            .build();
    }

    private String generateVersion() {
        if (baselineSeed.get() == 0L) {
            long existing = baselineRepository.count();
            if (existing > 0) {
                baselineSeed.compareAndSet(0L, existing);
            }
        }
        long seed = baselineSeed.incrementAndGet();
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC).format(VERSION_FORMATTER);
        if (seed > 1) {
            return "baseline-" + timestamp + "-" + seed;
        }
        return "baseline-" + timestamp;
    }

    private String resolveValue(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }

    private String resolveCreatedBy(String candidate) {
        String value = resolveValue(candidate, DEFAULT_CREATOR);
        return value.toLowerCase(Locale.ROOT);
    }

    private SecurityPolicyBaselineResponseDTO toBaselineResponse(SecurityPolicyBaseline baseline) {
        return SecurityPolicyBaselineResponseDTO.builder()
            .id(baseline.getId())
            .baselineVersion(baseline.getBaselineVersion())
            .title(baseline.getTitle())
            .summary(baseline.getSummary())
            .enforcementLevel(baseline.getEnforcementLevel())
            .policyCount(baseline.getPolicyCount())
            .controlObjectivesJson(baseline.getControlObjectivesJson())
            .createdBy(baseline.getCreatedBy())
            .createdAt(baseline.getCreatedAt())
            .updatedAt(baseline.getUpdatedAt())
            .build();
    }

    private SecurityPolicyApprovalSummaryDTO toApprovalSummary(SecurityPolicyApproval approval) {
        return SecurityPolicyApprovalSummaryDTO.builder()
            .id(approval.getId())
            .policyName(approval.getPolicyName())
            .changeType(approval.getChangeType())
            .requestedBy(approval.getRequestedBy())
            .status(approval.getStatus())
            .submittedAt(approval.getSubmittedAt())
            .requiredBy(approval.getRequiredBy())
            .summary(approval.getSummary())
            .baselineVersion(approval.getBaselineVersion())
            .severity(approval.getSeverity())
            .build();
    }
}
