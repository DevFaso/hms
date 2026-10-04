package com.example.hms.payload.dto;

import com.example.hms.enums.platform.PlatformServiceStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A partial update of an organization's platform service.
 *
 * <p>Text fields: absent or {@code null} leaves the stored value unchanged, a
 * blank string clears it, anything else replaces it. {@code ownership} and
 * {@code metadata} follow the same rule field by field. The API-key reference
 * is write-only, so blank means "keep" there and {@link #clearApiKeyReference}
 * is the only way to remove it. See {@code PlatformServiceMapper}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformServiceUpdateRequestDTO {

    private PlatformServiceStatus status;

    @Size(max = 120)
    private String provider;

    @Size(max = 255)
    private String baseUrl;

    @Size(max = 255)
    private String documentationUrl;

    @Size(max = 500)
    private String apiKeyReference;

    /** True removes the stored API-key reference; refused together with a new value. */
    private Boolean clearApiKeyReference;

    private Boolean managedByPlatform;

    @Valid
    private PlatformOwnershipDTO ownership;

    @Valid
    private PlatformServiceMetadataDTO metadata;
}
