package com.example.hms.service.provisioning;

import com.example.hms.payload.dto.OrganizationResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminCreateOrganizationRequestDTO;

/**
 * MVP-9c — pluggable client for delegating tenant provisioning to a
 * remote, region-specific deployment.
 *
 * <p>Called from {@link com.example.hms.service.SuperAdminOrganizationProvisioningService}
 * only when the region's policy has a non-empty
 * {@code target_deployment_url}. The default implementation
 * ({@link StubTenantProvisioningClient}) throws so an operator who has
 * configured a remote URL but not wired a real client surface notices
 * the misconfiguration immediately. A real implementation (HTTP /
 * gRPC / message-bus) drops in by registering its own
 * {@link org.springframework.stereotype.Component} bean — Spring's
 * {@code @ConditionalOnMissingBean} on the stub steps aside.
 */
public interface TenantProvisioningClient {

    /**
     * Forward {@code request} to the deployment at {@code targetDeploymentUrl}
     * and return the freshly-created organization as the remote saw it.
     *
     * @throws com.example.hms.exception.RemoteProvisioningNotConfiguredException
     *     if no real client is wired and the stub is the only bean.
     */
    OrganizationResponseDTO provisionRemote(
        SuperAdminCreateOrganizationRequestDTO request,
        String targetDeploymentUrl);
}
