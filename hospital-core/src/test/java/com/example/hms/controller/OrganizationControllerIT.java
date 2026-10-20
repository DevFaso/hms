package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.config.OrganizationSecurityConstants;
import com.example.hms.model.Organization;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.repository.OrganizationSecurityRuleRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(addFilters = false)
@WithMockUser(authorities = "ROLE_SUPER_ADMIN")
class OrganizationControllerIT extends BaseIT {

    private static final String API_CONTEXT = "/api";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationSecurityPolicyRepository securityPolicyRepository;

    @Autowired
    private OrganizationSecurityRuleRepository securityRuleRepository;

    @Autowired
    private UserRoleHospitalAssignmentRepository assignmentRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @BeforeEach
    void cleanDatabase() {
        departmentRepository.deleteAll();
        assignmentRepository.deleteAll();
        securityRuleRepository.deleteAll();
        securityPolicyRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private Map<String, Object> buildOrganizationRequest(String code, String type) {
        return Map.of(
            "name", "Org " + code,
            "code", code,
            "description", "Test organization",
            "type", type,
            "active", true
        );
    }

    @Test
    @DisplayName("Creating a high-security organization applies default password and session policies")
    void createOrganizationAppliesDefaultPolicies() throws Exception {
    mockMvc.perform(post(API_CONTEXT + "/organizations").contextPath(API_CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildOrganizationRequest("GOV001", "GOVERNMENT_AGENCY"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("GOV001"));

        Organization organization = organizationRepository.findByCode("GOV001")
            .orElseThrow(() -> new IllegalStateException("Organization not persisted"));

        List<OrganizationSecurityPolicy> policies = securityPolicyRepository
            .findByOrganizationIdAndActiveTrue(organization.getId());

        assertThat(policies)
            .extracting(OrganizationSecurityPolicy::getCode)
            .contains(OrganizationSecurityConstants.DEFAULT_PASSWORD_POLICY,
                OrganizationSecurityConstants.DEFAULT_SESSION_POLICY);
    }

    private Organization storedTwin(String code) {
        // Saved straight through the repository: duplicate names stored before the D435 guard.
        Organization org = new Organization();
        org.setName("Twin Health");
        org.setCode(code);
        org.setType(com.example.hms.enums.OrganizationType.valueOf("GOVERNMENT_AGENCY"));
        org.setActive(true);
        return organizationRepository.save(org);
    }

    @Test
    @DisplayName("QA D435: two stored organisations with one name stay editable, and the name is refused to a third")
    void duplicateNamesStoredBeforeTheGuardDoNotBreakCreateOrUpdate() throws Exception {
        Organization first = storedTwin("TWIN01");
        storedTwin("TWIN02");

        mockMvc.perform(put(API_CONTEXT + "/organizations/{id}", first.getId()).contextPath(API_CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "twin health ",
                    "code", "TWIN01",
                    "description", "Edited",
                    "type", "GOVERNMENT_AGENCY",
                    "active", true))))
            .andExpect(status().isOk());

        mockMvc.perform(post(API_CONTEXT + "/organizations").contextPath(API_CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "TWIN HEALTH",
                    "code", "TWIN03",
                    "type", "GOVERNMENT_AGENCY",
                    "active", true))))
            .andExpect(status().isBadRequest());

        Organization other = storedTwin("TWIN04");
        other.setName("Other Health");
        organizationRepository.save(other);
        mockMvc.perform(put(API_CONTEXT + "/organizations/{id}", other.getId()).contextPath(API_CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Twin Health",
                    "code", "TWIN04",
                    "type", "GOVERNMENT_AGENCY",
                    "active", true))))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Updating an organization does not create duplicate policies")
    void updateOrganizationDoesNotDuplicatePolicies() throws Exception {
    mockMvc.perform(post(API_CONTEXT + "/organizations").contextPath(API_CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildOrganizationRequest("GOV002", "GOVERNMENT_AGENCY"))))
            .andExpect(status().isCreated());

        Organization organization = organizationRepository.findByCode("GOV002")
            .orElseThrow(() -> new IllegalStateException("Organization not persisted"));

        List<OrganizationSecurityPolicy> initialPolicies = securityPolicyRepository
            .findByOrganizationIdAndActiveTrue(organization.getId());

    mockMvc.perform(put(API_CONTEXT + "/organizations/{id}", organization.getId()).contextPath(API_CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Updated Org",
                    "code", "GOV002",
                    "description", "Updated description",
                    "type", "GOVERNMENT_AGENCY",
                    "active", true
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Updated Org"));

        List<OrganizationSecurityPolicy> policiesAfterUpdate = securityPolicyRepository
            .findByOrganizationIdAndActiveTrue(organization.getId());

        assertThat(policiesAfterUpdate).hasSameSizeAs(initialPolicies);
    }

    @Test
    @DisplayName("Compliance endpoint returns policy violations and settings")
    void complianceEndpointReturnsViolations() throws Exception {
    mockMvc.perform(post(API_CONTEXT + "/organizations").contextPath(API_CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildOrganizationRequest("NET001", "HEALTHCARE_NETWORK"))))
            .andExpect(status().isCreated());

        Organization organization = organizationRepository.findByCode("NET001")
            .orElseThrow(() -> new IllegalStateException("Organization not persisted"));

    mockMvc.perform(get(API_CONTEXT + "/organizations/{id}/security/compliance", organization.getId()).contextPath(API_CONTEXT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.organizationId").value(organization.getId().toString()))
            .andExpect(jsonPath("$.passwordMinLength").value(8))
            .andExpect(jsonPath("$.sessionTimeoutMinutes").value(480))
            .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    @DisplayName("Creating a security rule persists rule under target policy")
    void createSecurityRulePersistsRule() throws Exception {
    mockMvc.perform(post(API_CONTEXT + "/organizations").contextPath(API_CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildOrganizationRequest("PRV001", "PRIVATE_PRACTICE"))))
            .andExpect(status().isCreated());

        Organization organization = organizationRepository.findByCode("PRV001")
            .orElseThrow(() -> new IllegalStateException("Organization not persisted"));

        OrganizationSecurityPolicy passwordPolicy = securityPolicyRepository
            .findByOrganizationIdAndCode(organization.getId(), OrganizationSecurityConstants.DEFAULT_PASSWORD_POLICY)
            .orElseThrow(() -> new IllegalStateException("Password policy missing"));

        Map<String, Object> ruleRequest = Map.of(
            "name", "Custom Password Rule",
            "code", "CUSTOM_PASSWORD_RULE",
            "description", "Require minimum length",
            "ruleType", "PASSWORD_STRENGTH",
            "ruleValue", "10",
            "securityPolicyId", passwordPolicy.getId().toString(),
            "priority", 5,
            "active", true
        );

    mockMvc.perform(post(API_CONTEXT + "/organizations/{orgId}/security/policies/{policyId}/rules",
        organization.getId(), passwordPolicy.getId()).contextPath(API_CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ruleRequest)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("CUSTOM_PASSWORD_RULE"));

        List<OrganizationSecurityRule> rules = securityRuleRepository
            .findBySecurityPolicyIdAndActiveTrue(passwordPolicy.getId());

        assertThat(rules)
            .extracting(OrganizationSecurityRule::getCode)
            .contains("CUSTOM_PASSWORD_RULE");
    }
}
