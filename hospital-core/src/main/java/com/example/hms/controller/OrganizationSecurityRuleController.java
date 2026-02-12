package com.example.hms.controller;

import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.service.OrganizationSecurityRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/security-rules")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN')")
public class OrganizationSecurityRuleController {
    private final OrganizationSecurityRuleService ruleService;

    @GetMapping
    public ResponseEntity<List<OrganizationSecurityRule>> getAllRules() {
        return ResponseEntity.ok(ruleService.getAllRules());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationSecurityRule> getRuleById(@PathVariable UUID id) {
        OrganizationSecurityRule rule = ruleService.getRuleById(id);
        return rule != null ? ResponseEntity.ok(rule) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<OrganizationSecurityRule> createRule(@RequestBody OrganizationSecurityRule rule) {
        return ResponseEntity.ok(ruleService.createRule(rule));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrganizationSecurityRule> updateRule(@PathVariable UUID id, @RequestBody OrganizationSecurityRule rule) {
        return ResponseEntity.ok(ruleService.updateRule(id, rule));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        ruleService.deleteRule(id);
        return ResponseEntity.noContent().build();
    }
}
