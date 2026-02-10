package com.example.hms.service;

import com.example.hms.model.OrganizationSecurityRule;
import java.util.List;
import java.util.UUID;

public interface OrganizationSecurityRuleService {
    List<OrganizationSecurityRule> getAllRules();
    OrganizationSecurityRule getRuleById(UUID id);
    OrganizationSecurityRule createRule(OrganizationSecurityRule rule);
    OrganizationSecurityRule updateRule(UUID id, OrganizationSecurityRule rule);
    void deleteRule(UUID id);
}
