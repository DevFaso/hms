package com.example.hms.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * MVP-9c — thrown when a region's policy has a non-empty
 * {@code target_deployment_url} but no real {@link com.example.hms.service.provisioning.TenantProvisioningClient}
 * is wired in. Surfaces as HTTP 501 so the operator notices the
 * misconfiguration immediately instead of silently provisioning the
 * tenant on the local deployment (which would be a data-residency
 * violation for a GDPR-tagged region).
 */
@ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
public class RemoteProvisioningNotConfiguredException extends RuntimeException {

    public RemoteProvisioningNotConfiguredException(String message) {
        super(message);
    }
}
