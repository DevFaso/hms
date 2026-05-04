package com.example.hms.service.impl;

import com.example.hms.enums.OrganizationRegion;
import com.example.hms.service.RegionPolicyService;
import com.example.hms.service.RegionRoutingResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RegionRoutingResolverImpl implements RegionRoutingResolver {

    private final RegionPolicyService regionPolicyService;

    @Override
    public Optional<String> resolveDeploymentUrl(OrganizationRegion region) {
        if (region == null) {
            return Optional.empty();
        }
        String url = regionPolicyService.resolveTargetDeploymentUrl(region);
        if (url == null) {
            return Optional.empty();
        }
        String trimmed = url.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }
}
