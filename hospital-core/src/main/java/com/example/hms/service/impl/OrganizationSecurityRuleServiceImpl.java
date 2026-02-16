package com.example.hms.service.impl;

import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.repository.OrganizationSecurityRuleRepository;
import com.example.hms.service.OrganizationSecurityRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationSecurityRuleServiceImpl implements OrganizationSecurityRuleService {
    private final OrganizationSecurityRuleRepository ruleRepository;

    @Override
    public List<OrganizationSecurityRule> getAllRules() {
        return ruleRepository.findAll();
    }

    @Override
    public OrganizationSecurityRule getRuleById(UUID id) {
        return ruleRepository.findById(id).orElse(null);
    }

    @Override
    public OrganizationSecurityRule createRule(OrganizationSecurityRule rule) {
        return ruleRepository.save(rule);
    }

    @Override
    public OrganizationSecurityRule updateRule(UUID id, OrganizationSecurityRule rule) {
        rule.setId(id);
        return ruleRepository.save(rule);
    }

    @Override
    public void deleteRule(UUID id) {
        ruleRepository.deleteById(id);
    }
}
