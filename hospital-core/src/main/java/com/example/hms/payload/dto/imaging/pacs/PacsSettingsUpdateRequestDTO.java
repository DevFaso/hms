package com.example.hms.payload.dto.imaging.pacs;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What a hospital admin (or the super-admin) sets for a hospital's PACS.
 *
 * <p>{@code authPassword} is write-only: null or blank keeps the stored one,
 * {@code clearPassword=true} removes it. It never appears in a response, a
 * log line or this record's {@code toString}.
 */
public record PacsSettingsUpdateRequestDTO(
    @NotNull(message = "{imaging.pacs.enabled.required}")
    Boolean enabled,

    @Size(max = 500, message = "{imaging.pacs.url.tooLong}")
    String dicomwebBaseUrl,

    @Size(max = 500, message = "{imaging.pacs.url.tooLong}")
    String viewerUrlTemplate,

    @Size(max = 255, message = "{imaging.pacs.username.tooLong}")
    String authUsername,

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = 500, message = "{imaging.pacs.password.tooLong}")
    String authPassword,

    Boolean clearPassword
) {

    /** A record would print every component, the password included. */
    @Override
    public String toString() {
        return "PacsSettingsUpdateRequestDTO{enabled=" + enabled + ", dicomwebBaseUrl=" + dicomwebBaseUrl
            + ", viewerUrlTemplate=" + viewerUrlTemplate + ", authUsername=" + authUsername
            + ", authPassword=" + (authPassword == null || authPassword.isEmpty() ? "absent" : "***")
            + ", clearPassword=" + clearPassword + "}";
    }
}
