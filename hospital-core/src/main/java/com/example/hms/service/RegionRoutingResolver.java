package com.example.hms.service;

import com.example.hms.enums.OrganizationRegion;

import java.util.Optional;

/**
 * MVP-9c — resolves a region's configured remote-deployment URL so a
 * new tenant created for that region can be provisioned on the
 * region-specific deployment instead of the local one.
 *
 * <p>This is a thin facade over {@link RegionPolicyService#resolveTargetDeploymentUrl}
 * that the provisioning service consults at create time. Empty / null
 * regions and unset target URLs both resolve to {@link Optional#empty()},
 * meaning "provision locally as today" — the only caller that needs to
 * branch on the result.
 */
public interface RegionRoutingResolver {

    /**
     * @return the configured remote-deployment URL for {@code region},
     *     or {@link Optional#empty()} when {@code region} is null, has
     *     no policy row, or has the column blank/null.
     */
    Optional<String> resolveDeploymentUrl(OrganizationRegion region);
}
