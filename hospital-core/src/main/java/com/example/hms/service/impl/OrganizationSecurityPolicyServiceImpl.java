package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Organization;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.payload.dto.OrganizationSecurityPolicyRequestDTO;
import com.example.hms.payload.dto.OrganizationSecurityPolicyResponseDTO;
import com.example.hms.payload.dto.OrganizationSecurityRuleResponseDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.OrganizationSecurityPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationSecurityPolicyServiceImpl implements OrganizationSecurityPolicyService {

    /** Resource name reported by the 404s below. */
    private static final String RESOURCE = "OrganizationSecurityPolicy";
    private static final String FIELD_ID = "id";

    private final OrganizationSecurityPolicyRepository policyRepository;
    private final OrganizationRepository organizationRepository;

    @Override
    public List<OrganizationSecurityPolicy> getAllPolicies() {
        return policyRepository.findAll();
    }

    @Override
    public OrganizationSecurityPolicy getPolicyById(UUID id) {
        return policyRepository.findById(id).orElse(null);
    }

    @Override
    public OrganizationSecurityPolicy createPolicy(OrganizationSecurityPolicy policy) {
        return policyRepository.save(policy);
    }

    @Override
    public OrganizationSecurityPolicy updatePolicy(UUID id, OrganizationSecurityPolicy policy) {
        policy.setId(id);
        return policyRepository.save(policy);
    }

    @Override
    public void deletePolicy(UUID id) {
        OrganizationSecurityPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, FIELD_ID, id));
        requireOrganizationScope(policy, id);
        policyRepository.delete(policy);
    }

    // ---- DTO-based operations ----

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationSecurityPolicyResponseDTO> getAllPoliciesAsDto() {
        // ── Tenant isolation: hospital admins see their own organization's policies;
        //    only super-admin sees all. Fail closed when no organization resolves. ──
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        if (ctx.isSuperAdmin()) {
            return policyRepository.findAll().stream()
                    .map(this::toResponseDto)
                    .toList();
        }
        UUID organizationId = ctx.getActiveOrganizationId();
        if (organizationId == null) {
            log.warn("[policy:tenantGuard] User {} has no organization scope — returning empty policy list",
                    ctx.getPrincipalUsername());
            return List.of();
        }
        return policyRepository.findByOrganizationId(organizationId).stream()
                .map(this::toResponseDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationSecurityPolicyResponseDTO getPolicyByIdAsDto(UUID id) {
        OrganizationSecurityPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, FIELD_ID, id));
        requireOrganizationScope(policy, id);
        return toResponseDto(policy);
    }

    @Override
    @Transactional
    public OrganizationSecurityPolicyResponseDTO createPolicyFromDto(OrganizationSecurityPolicyRequestDTO dto) {
        requireTargetOrganization(dto.getOrganizationId());
        Organization org = organizationRepository.findById(dto.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", dto.getOrganizationId()));

        OrganizationSecurityPolicy policy = OrganizationSecurityPolicy.builder()
                .name(dto.getName())
                .code(dto.getCode())
                .description(dto.getDescription())
                .policyType(dto.getPolicyType())
                .priority(dto.getPriority() != null ? dto.getPriority() : 0)
                .active(dto.isActive())
                .enforceStrict(dto.isEnforceStrict())
                .organization(org)
                .build();

        return toResponseDto(policyRepository.save(policy));
    }

    @Override
    @Transactional
    public OrganizationSecurityPolicyResponseDTO updatePolicyFromDto(UUID id, OrganizationSecurityPolicyRequestDTO dto) {
        OrganizationSecurityPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, FIELD_ID, id));
        requireOrganizationScope(policy, id);
        requireTargetOrganization(dto.getOrganizationId());

        Organization org = organizationRepository.findById(dto.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", dto.getOrganizationId()));

        policy.setName(dto.getName());
        policy.setCode(dto.getCode());
        policy.setDescription(dto.getDescription());
        policy.setPolicyType(dto.getPolicyType());
        policy.setPriority(dto.getPriority() != null ? dto.getPriority() : policy.getPriority());
        policy.setActive(dto.isActive());
        policy.setEnforceStrict(dto.isEnforceStrict());
        policy.setOrganization(org);

        return toResponseDto(policyRepository.save(policy));
    }

    /**
     * Tenant guard: a non-super-admin may only touch policies of their own
     * organization. Cross-organization ids surface as 404 so the endpoint does
     * not leak which policy ids exist elsewhere.
     */
    private void requireOrganizationScope(OrganizationSecurityPolicy policy, UUID id) {
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        if (ctx.isSuperAdmin()) {
            return;
        }
        UUID organizationId = ctx.getActiveOrganizationId();
        UUID policyOrgId = policy.getOrganization() != null ? policy.getOrganization().getId() : null;
        if (organizationId == null || !organizationId.equals(policyOrgId)) {
            log.warn("[policy:tenantGuard] User {} attempted cross-organization access to policy {}",
                    ctx.getPrincipalUsername(), id);
            throw new ResourceNotFoundException(RESOURCE, FIELD_ID, id); // 404, not 403
        }
    }

    /** Tenant guard for writes: the target organization must be the caller's own. */
    private void requireTargetOrganization(UUID targetOrganizationId) {
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        if (ctx.isSuperAdmin()) {
            return;
        }
        UUID organizationId = ctx.getActiveOrganizationId();
        if (organizationId == null || !organizationId.equals(targetOrganizationId)) {
            log.warn("[policy:tenantGuard] User {} attempted to write a policy for organization {}",
                    ctx.getPrincipalUsername(), targetOrganizationId);
            throw new AccessDeniedException("Cannot manage security policies for another organization");
        }
    }

    private OrganizationSecurityPolicyResponseDTO toResponseDto(OrganizationSecurityPolicy p) {
        List<OrganizationSecurityRuleResponseDTO> rules = p.getRules() == null ? List.of() :
                p.getRules().stream().map(r -> OrganizationSecurityRuleResponseDTO.builder()
                        .id(r.getId())
                        .name(r.getName())
                        .code(r.getCode())
                        .description(r.getDescription())
                        .ruleType(r.getRuleType())
                        .ruleValue(r.getRuleValue())
                        .priority(r.getPriority())
                        .active(r.isActive())
                        .createdAt(r.getCreatedAt())
                        .updatedAt(r.getUpdatedAt())
                        .securityPolicyId(p.getId())
                        .securityPolicyName(p.getName())
                        .build()).toList();

        return OrganizationSecurityPolicyResponseDTO.builder()
                .id(p.getId())
                .name(p.getName())
                .code(p.getCode())
                .description(p.getDescription())
                .policyType(p.getPolicyType())
                .priority(p.getPriority())
                .active(p.isActive())
                .enforceStrict(p.isEnforceStrict())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .organizationId(p.getOrganization() != null ? p.getOrganization().getId() : null)
                .organizationName(p.getOrganization() != null ? p.getOrganization().getName() : null)
                .rules(rules)
                .build();
    }
}

