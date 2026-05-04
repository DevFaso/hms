package com.example.hms.service.provisioning;

import com.example.hms.exception.RemoteProvisioningNotConfiguredException;
import com.example.hms.payload.dto.OrganizationResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminCreateOrganizationRequestDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default {@link TenantProvisioningClient} bean — fails loud (HTTP
 * 501) when invoked. Registered only when the application context
 * has no other implementation, so wiring a real HTTP / gRPC /
 * message-bus client is a one-line drop-in.
 *
 * <p>Strict-mode rationale: a region with {@code target_deployment_url}
 * set is signalling "tenants for this region must be created on that
 * deployment". Silently falling back to local provisioning would be
 * a data-residency violation for a GDPR-tagged region. Failing the
 * request surfaces the misconfiguration to the operator, who can
 * either unset the URL (accept local) or wire a real client.
 */
@Component
@ConditionalOnMissingBean(value = TenantProvisioningClient.class, ignored = StubTenantProvisioningClient.class)
@Slf4j
public class StubTenantProvisioningClient implements TenantProvisioningClient {

    @Override
    public OrganizationResponseDTO provisionRemote(
        SuperAdminCreateOrganizationRequestDTO request,
        String targetDeploymentUrl
    ) {
        // Logged at warn so an operator scanning logs sees the configured-
        // but-unwired state. The 501 is the operator-facing surface.
        log.warn("[REGION-ROUTING] Remote provisioning requested for {} at {} but no real "
            + "TenantProvisioningClient is wired — rejecting with 501. Either unset the "
            + "region's target_deployment_url to accept local provisioning, or register a "
            + "real TenantProvisioningClient bean.",
            request != null ? request.getCode() : "<null-request>",
            targetDeploymentUrl);
        throw new RemoteProvisioningNotConfiguredException(
            "Region routing is configured (target_deployment_url=" + targetDeploymentUrl
                + ") but no remote TenantProvisioningClient implementation is wired. "
                + "Register a real client bean or clear the region's target_deployment_url.");
    }
}
