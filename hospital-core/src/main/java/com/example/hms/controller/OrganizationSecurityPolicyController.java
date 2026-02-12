package com.example.hms.controller;

import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.service.OrganizationSecurityPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/security-policies")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN')")
public class OrganizationSecurityPolicyController {
    private final OrganizationSecurityPolicyService policyService;

    @GetMapping
    public ResponseEntity<List<OrganizationSecurityPolicy>> getAllPolicies() {
        return ResponseEntity.ok(policyService.getAllPolicies());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationSecurityPolicy> getPolicyById(@PathVariable UUID id) {
        OrganizationSecurityPolicy policy = policyService.getPolicyById(id);
        return policy != null ? ResponseEntity.ok(policy) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<OrganizationSecurityPolicy> createPolicy(@RequestBody OrganizationSecurityPolicy policy) {
        return ResponseEntity.ok(policyService.createPolicy(policy));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrganizationSecurityPolicy> updatePolicy(@PathVariable UUID id, @RequestBody OrganizationSecurityPolicy policy) {
        return ResponseEntity.ok(policyService.updatePolicy(id, policy));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePolicy(@PathVariable UUID id) {
        policyService.deletePolicy(id);
        return ResponseEntity.noContent().build();
    }
}
