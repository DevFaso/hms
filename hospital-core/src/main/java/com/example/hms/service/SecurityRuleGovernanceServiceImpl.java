package com.example.hms.service;

import com.example.hms.enums.SecurityRuleType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.security.SecurityRuleSet;
import com.example.hms.payload.dto.superadmin.SecurityRuleDefinitionDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSetRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSetResponseDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSimulationRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSimulationResultDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateImportRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateImportResponseDTO;
import com.example.hms.repository.SecurityRuleSetRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityRuleGovernanceServiceImpl implements SecurityRuleGovernanceService {

    private static final String DEFAULT_PUBLISHER = "super-admin@system";
    private static final String ROLE_CONTROLLER = "RoleController";
    private static final String PERMISSION_CONTROLLER = "PermissionController";
    private static final String ORG_RULE_CONTROLLER = "OrganizationSecurityRuleController";
    private static final String AUTH_CONTROLLER = "AuthController";
    private static final String PASSWORD_RESET_CONTROLLER = "PasswordResetController";

    private final SecurityRuleSetRepository ruleSetRepository;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    /**
     * Locale a template is rendered in when its title and summary become the
     * NAME and DESCRIPTION of a stored rule set. The catalogue shown to the
     * caller follows the request locale; the row must not, or one admin's
     * language would be baked into a record every other admin reads.
     */
    private static final Locale CANONICAL_LOCALE = Locale.ENGLISH;

    @Override
    @Transactional
    public SecurityRuleSetResponseDTO createRuleSet(SecurityRuleSetRequestDTO request) {
        List<SecurityRuleDefinitionDTO> rules = sanitizeRules(request.getRules());
        SecurityRuleSet ruleSet = SecurityRuleSet.builder()
            .name(resolveValue(request.getName(), "Adaptive rule set"))
            .code(generateCode(request.getName()))
            .description(request.getDescription())
            .enforcementScope(resolveValue(request.getEnforcementScope(), "GLOBAL"))
            .ruleCount(rules.size())
            .metadataJson(serializeMetadata(Map.of(
                "request", request,
                "rules", rules
            )))
            .publishedBy(resolvePublisher(request.getPublishedBy()))
            .publishedAt(java.time.LocalDateTime.now())
            .build();

        SecurityRuleSet saved = ruleSetRepository.save(ruleSet);
        log.info("Created security rule set {}", saved.getCode());
        return toResponse(saved, rules);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityRuleTemplateDTO> listTemplates() {
        return buildTemplates(LocaleContextHolder.getLocale());
    }

    @Override
    @Transactional
    public SecurityRuleTemplateImportResponseDTO importTemplate(SecurityRuleTemplateImportRequestDTO request) {
        SecurityRuleTemplateDTO template = findTemplate(request.getTemplateCode(), CANONICAL_LOCALE);
        List<SecurityRuleDefinitionDTO> rules = cloneDefinitions(template.getDefaultRules());

        SecurityRuleSet ruleSet = SecurityRuleSet.builder()
            .name(template.getTitle())
            .code(generateCode(template.getTitle()))
            .description(template.getSummary())
            .enforcementScope("GLOBAL")
            .ruleCount(rules.size())
            .metadataJson(serializeMetadata(Map.of(
                "templateCode", template.getCode(),
                "importedBy", request.getRequestedBy(),
                "controllers", template.getControllers(),
                "rules", rules
            )))
            .publishedBy(resolvePublisher(request.getRequestedBy()))
            .publishedAt(java.time.LocalDateTime.now())
            .build();

        SecurityRuleSet saved = ruleSetRepository.save(ruleSet);
        log.info("Imported security rule template {} into rule set {}", template.getCode(), saved.getCode());

        return SecurityRuleTemplateImportResponseDTO.builder()
            .templateCode(template.getCode())
            .templateTitle(findTemplate(request.getTemplateCode(), LocaleContextHolder.getLocale()).getTitle())
            .importedRuleCount(rules.size())
            .ruleSet(toResponse(saved, rules))
            .importedRules(rules)
            .importedAt(OffsetDateTime.now(ZoneOffset.UTC))
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public SecurityRuleSimulationResultDTO simulatePolicyImpact(SecurityRuleSimulationRequestDTO request) {
        List<SecurityRuleDefinitionDTO> rules = sanitizeRules(request.getRules());
        if (rules.isEmpty()) {
            throw new ResourceNotFoundException("security.rules.simulation.rules-missing");
        }

        double impactScore = calculateImpactScore(rules);
        List<String> impactedControllers = rules.stream()
            .flatMap(rule -> rule.getControllers() != null ? rule.getControllers().stream() : List.<String>of().stream())
            .distinct()
            .sorted()
            .toList();

        List<String> recommendations = buildRecommendations(rules, impactScore, LocaleContextHolder.getLocale());

        return SecurityRuleSimulationResultDTO.builder()
            .scenario(request.getScenario())
            .evaluatedRuleCount(rules.size())
            .impactScore(impactScore)
            .impactedControllers(impactedControllers)
            .recommendedActions(recommendations)
            .evaluatedAt(OffsetDateTime.now(ZoneOffset.UTC))
            .build();
    }

    private List<SecurityRuleDefinitionDTO> sanitizeRules(List<SecurityRuleDefinitionDTO> rules) {
        if (rules == null) {
            return List.of();
        }
        List<SecurityRuleDefinitionDTO> sanitized = new ArrayList<>(rules.size());
        int prioritySeed = 0;
        for (SecurityRuleDefinitionDTO incoming : rules) {
            SecurityRuleDefinitionDTO copy = cloneDefinition(incoming);
            if (copy.getPriority() == null) {
                copy.setPriority(++prioritySeed);
            }
            sanitized.add(copy);
        }
        sanitized.sort(Comparator.comparingInt(rule -> rule.getPriority() != null ? rule.getPriority() : Integer.MAX_VALUE));
        return sanitized;
    }

    private String resolveValue(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }

    private String resolvePublisher(String requested) {
        String value = resolveValue(requested, DEFAULT_PUBLISHER);
        return value.toLowerCase(Locale.ROOT);
    }

    private String generateCode(String name) {
        String base = StringUtils.hasText(name) ? name : "rule-set";
        String normalized = base.toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "-")
            .replaceAll("-+", "-")
            .replaceAll("(^-)|(-$)", "");
        if (normalized.isBlank()) {
            normalized = "RULE-SET";
        }
        String finalCode = normalized;
        int attempt = 0;
        while (ruleSetRepository.findByCodeIgnoreCase(finalCode).isPresent()) {
            attempt++;
            finalCode = normalized + "-" + attempt;
        }
        return finalCode;
    }

    private String serializeMetadata(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            log.warn("Failed to serialize security rule metadata", e);
            return "{}";
        }
    }

    private SecurityRuleSetResponseDTO toResponse(SecurityRuleSet ruleSet, List<SecurityRuleDefinitionDTO> rules) {
        return SecurityRuleSetResponseDTO.builder()
            .id(ruleSet.getId() != null ? ruleSet.getId().toString() : null)
            .code(ruleSet.getCode())
            .name(ruleSet.getName())
            .description(ruleSet.getDescription())
            .enforcementScope(ruleSet.getEnforcementScope())
            .ruleCount(ruleSet.getRuleCount())
            .metadataJson(ruleSet.getMetadataJson())
            .publishedBy(ruleSet.getPublishedBy())
            .publishedAt(ruleSet.getPublishedAt() != null ? ruleSet.getPublishedAt().atOffset(ZoneOffset.UTC) : null)
            .createdAt(ruleSet.getCreatedAt() != null ? ruleSet.getCreatedAt().atOffset(ZoneOffset.UTC) : null)
            .updatedAt(ruleSet.getUpdatedAt() != null ? ruleSet.getUpdatedAt().atOffset(ZoneOffset.UTC) : null)
            .rules(rules)
            .build();
    }

    private SecurityRuleTemplateDTO findTemplate(String code, Locale locale) {
        return buildTemplates(locale).stream()
            .filter(template -> template.getCode().equalsIgnoreCase(code))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("security.rules.template.not-found"));
    }

    private String templateText(String code, String field, Locale locale) {
        return messageSource.getMessage("security.template." + code + "." + field, null, locale);
    }

    /**
     * The catalogue's title/category/summary are rendered in {@code locale}.
     * The rule definitions underneath are left as written: they are copied
     * into the stored rule set on import, so they are data, not messages.
     */
    private static final String CODE_RBAC_GLOBAL = "RBAC_GLOBAL";
    private static final String CODE_NETWORK_SAFEGUARDS = "NETWORK_SAFEGUARDS";
    private static final String CODE_DEVICE_HYGIENE = "DEVICE_HYGIENE";
    private static final String CODE_SESSION_HARDENING = "SESSION_HARDENING";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_CATEGORY = "category";
    private static final String FIELD_SUMMARY = "summary";

    private List<SecurityRuleTemplateDTO> buildTemplates(Locale locale) {
        Map<String, SecurityRuleTemplateDTO> templates = new LinkedHashMap<>();
        templates.put(CODE_RBAC_GLOBAL, SecurityRuleTemplateDTO.builder()
            .code(CODE_RBAC_GLOBAL)
            .title(templateText(CODE_RBAC_GLOBAL, FIELD_TITLE, locale))
            .category(templateText(CODE_RBAC_GLOBAL, FIELD_CATEGORY, locale))
            .summary(templateText(CODE_RBAC_GLOBAL, FIELD_SUMMARY, locale))
            .controllers(List.of(ROLE_CONTROLLER, PERMISSION_CONTROLLER))
            .defaultRules(List.of(
                createDefinition(
                    "Role segregation",
                    "RBAC-SEGREGATION",
                    SecurityRuleType.ROLE_PERMISSION,
                    "Enforce mutually exclusive critical duties across finance and clinical roles.",
                    Map.of("mutuallyExclusive", List.of("BILLING_ADMIN", "CLINICAL_DIRECTOR")),
                    1,
                    List.of(ROLE_CONTROLLER, PERMISSION_CONTROLLER)
                ),
                createDefinition(
                    "Operational least privilege",
                    "RBAC-LEAST-PRIVILEGE",
                    SecurityRuleType.ROLE_PERMISSION,
                    "Reduce default operational roles to minimum required permissions.",
                    Map.of("minimumPermissions", List.of("orders:view", "inventory:view")),
                    2,
                    List.of(ROLE_CONTROLLER)
                )
            ))
            .build());

        templates.put(CODE_NETWORK_SAFEGUARDS, SecurityRuleTemplateDTO.builder()
            .code(CODE_NETWORK_SAFEGUARDS)
            .title(templateText(CODE_NETWORK_SAFEGUARDS, FIELD_TITLE, locale))
            .category(templateText(CODE_NETWORK_SAFEGUARDS, FIELD_CATEGORY, locale))
            .summary(templateText(CODE_NETWORK_SAFEGUARDS, FIELD_SUMMARY, locale))
            .controllers(List.of(ORG_RULE_CONTROLLER))
            .defaultRules(List.of(
                createDefinition(
                    "VPN enforced access",
                    "NET-VPN-ENFORCE",
                    SecurityRuleType.IP_WHITELIST,
                    "Require VPN tunnel for administrative endpoints.",
                    Map.of("requiredVpnGroups", List.of("admin-vpn", "ops-vpn")),
                    1,
                    List.of(ORG_RULE_CONTROLLER)
                ),
                createDefinition(
                    "Geo-fenced sessions",
                    "NET-GEOFENCE",
                    SecurityRuleType.SESSION_TIMEOUT,
                    "Terminate sessions when outside approved geographic bounds for more than 5 minutes.",
                    Map.of("allowedCountries", List.of("US", "CA"), "maxDriftMinutes", 5),
                    2,
                    List.of(ORG_RULE_CONTROLLER)
                )
            ))
            .build());

        templates.put(CODE_DEVICE_HYGIENE, SecurityRuleTemplateDTO.builder()
            .code(CODE_DEVICE_HYGIENE)
            .title(templateText(CODE_DEVICE_HYGIENE, FIELD_TITLE, locale))
            .category(templateText(CODE_DEVICE_HYGIENE, FIELD_CATEGORY, locale))
            .summary(templateText(CODE_DEVICE_HYGIENE, FIELD_SUMMARY, locale))
            .controllers(List.of(ORG_RULE_CONTROLLER))
            .defaultRules(List.of(
                createDefinition(
                    "Device patch compliance",
                    "DEVICE-PATCH",
                    SecurityRuleType.COMPLIANCE_CHECK,
                    "Block access if last patch date exceeds 30 days.",
                    Map.of("maxPatchAgeDays", 30),
                    1,
                    List.of(ORG_RULE_CONTROLLER)
                ),
                createDefinition(
                    "Quarantine non-compliant",
                    "DEVICE-QUARANTINE",
                    SecurityRuleType.DATA_FILTER,
                    "Redirect non-compliant devices to remediation workflow.",
                    Map.of("redirectUrl", "https://security.example.com/remediate"),
                    2,
                    List.of(ORG_RULE_CONTROLLER)
                )
            ))
            .build());

        templates.put(CODE_SESSION_HARDENING, SecurityRuleTemplateDTO.builder()
            .code(CODE_SESSION_HARDENING)
            .title(templateText(CODE_SESSION_HARDENING, FIELD_TITLE, locale))
            .category(templateText(CODE_SESSION_HARDENING, FIELD_CATEGORY, locale))
            .summary(templateText(CODE_SESSION_HARDENING, FIELD_SUMMARY, locale))
            .controllers(List.of(AUTH_CONTROLLER, PASSWORD_RESET_CONTROLLER))
            .defaultRules(List.of(
                createDefinition(
                    "Adaptive MFA",
                    "SESSION-MFA",
                    SecurityRuleType.TWO_FACTOR_AUTH,
                    "Trigger MFA when accessing sensitive data or from high-risk networks.",
                    Map.of("riskSignals", List.of("location_anomaly", "device_unknown")),
                    1,
                    List.of(AUTH_CONTROLLER)
                ),
                createDefinition(
                    "Credential rotation",
                    "SESSION-ROTATE",
                    SecurityRuleType.PASSWORD_STRENGTH,
                    "Force credential rotation every 60 days with breach checks.",
                    Map.of("maxAgeDays", 60, "breachCheck", true),
                    2,
                    List.of(AUTH_CONTROLLER, PASSWORD_RESET_CONTROLLER)
                )
            ))
            .build());

        return List.copyOf(templates.values());
    }

    private SecurityRuleDefinitionDTO createDefinition(
        String name,
        String code,
        SecurityRuleType type,
        String description,
        Map<String, Object> value,
        int priority,
        List<String> controllers
    ) {
        SecurityRuleDefinitionDTO dto = new SecurityRuleDefinitionDTO();
        dto.setName(name);
        dto.setCode(code);
        dto.setRuleType(type);
        dto.setDescription(description);
        dto.setRuleValue(serializeMetadata(value));
        dto.setPriority(priority);
        dto.setControllers(controllers);
        return dto;
    }

    private SecurityRuleDefinitionDTO cloneDefinition(SecurityRuleDefinitionDTO source) {
        SecurityRuleDefinitionDTO copy = new SecurityRuleDefinitionDTO();
        copy.setName(source.getName());
        copy.setCode(source.getCode());
        copy.setDescription(source.getDescription());
        copy.setRuleType(source.getRuleType());
        copy.setRuleValue(source.getRuleValue());
        copy.setPriority(source.getPriority());
        copy.setControllers(source.getControllers() != null ? List.copyOf(source.getControllers()) : null);
        return copy;
    }

    private List<SecurityRuleDefinitionDTO> cloneDefinitions(List<SecurityRuleDefinitionDTO> definitions) {
    return definitions == null ? List.of() : definitions.stream().map(this::cloneDefinition).toList();
    }

    private double calculateImpactScore(List<SecurityRuleDefinitionDTO> rules) {
        if (rules.isEmpty()) {
            return 0.0;
        }
        double priorityWeight = rules.stream()
            .mapToDouble(rule -> {
                int priority = rule.getPriority() != null ? rule.getPriority() : 5;
                return 1.0 / Math.max(priority, 1);
            })
            .sum();
        double controllerBonus = rules.stream()
            .flatMap(rule -> rule.getControllers() != null ? rule.getControllers().stream() : List.<String>of().stream())
            .distinct()
            .count() * 0.15;
        return Math.min(5.0, Math.round((priorityWeight + controllerBonus) * 100.0) / 100.0);
    }

    private List<String> buildRecommendations(List<SecurityRuleDefinitionDTO> rules, double impactScore, Locale locale) {
        List<String> recommendations = new ArrayList<>();
        if (impactScore >= 3.5) {
            recommendations.add(messageSource.getMessage("security.recommendation.stagedRollout", null, locale));
        } else {
            recommendations.add(messageSource.getMessage("security.recommendation.immediateRollout", null, locale));
        }
        boolean requiresMfa = rules.stream().anyMatch(rule -> rule.getRuleType() == SecurityRuleType.TWO_FACTOR_AUTH);
        if (requiresMfa) {
            recommendations.add(messageSource.getMessage("security.recommendation.mfaCampaign", null, locale));
        }
        boolean hasNetworkRules = rules.stream().anyMatch(rule -> rule.getRuleType() == SecurityRuleType.IP_WHITELIST);
        if (hasNetworkRules) {
            recommendations.add(messageSource.getMessage("security.recommendation.cidrValidation", null, locale));
        }
        return recommendations;
    }
}
