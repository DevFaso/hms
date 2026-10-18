package com.example.hms.payload.dto;

import com.example.hms.enums.OrganizationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationRequestDTO {

    @NotBlank(message = "{organization.name.required}")
    @Size(max = 255, message = "{organization.name.size}")
    private String name;

    @NotBlank(message = "{organization.code.required}")
    @Size(max = 100, message = "{organization.code.size}")
    private String code;

    @Size(max = 500, message = "{organization.description.size}")
    private String description;

    @NotNull(message = "{organization.type.required}")
    private OrganizationType type;

    /** Boxed so a form that omits it (the portal's) is "active", not a 400 on a null primitive. */
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    public boolean isActive() {
        return active == null || active;
    }

    /*
     * QA D191: the contact fields the organisation form sends. They were not on this
     * DTO, so Jackson dropped them and the organisation kept no contact. Null leaves
     * the stored value alone on an update.
     */
    @jakarta.validation.constraints.Email
    @Size(max = 255)
    @com.fasterxml.jackson.annotation.JsonAlias("primaryContactEmail")
    private String contactEmail;

    @Size(max = 32)
    @com.fasterxml.jackson.annotation.JsonAlias("primaryContactPhone")
    private String contactPhone;

    @Size(max = 120)
    @com.fasterxml.jackson.annotation.JsonAlias("defaultTimezone")
    private String timezone;

    @Size(max = 1000)
    @com.fasterxml.jackson.annotation.JsonAlias("onboardingNotes")
    private String notes;
}