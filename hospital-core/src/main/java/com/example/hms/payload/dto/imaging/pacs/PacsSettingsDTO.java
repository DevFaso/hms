package com.example.hms.payload.dto.imaging.pacs;

import java.util.UUID;

/**
 * A hospital's PACS settings as the admin reads them. The password is never
 * here: {@code passwordSet} says only whether one is stored.
 *
 * @param state                       READY, PROXY_DISABLED or NOT_CONFIGURED
 * @param effectiveSource             HOSPITAL, PLATFORM, or null when nothing is reached
 * @param platformProxyEnabled        the platform master switch (app.imaging.dicom-proxy.enabled)
 * @param platformFallbackConfigured  whether a platform-wide PACS exists to fall back on
 * @param privateHostsAllowed         local-dev relaxation of the URL policy
 */
public record PacsSettingsDTO(
    UUID hospitalId,
    boolean enabled,
    String dicomwebBaseUrl,
    String viewerUrlTemplate,
    String authUsername,
    boolean passwordSet,
    String state,
    String effectiveSource,
    boolean platformProxyEnabled,
    boolean platformFallbackConfigured,
    boolean privateHostsAllowed
) {
}
