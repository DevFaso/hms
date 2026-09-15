package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.OrganizationRegion;
import com.example.hms.enums.OrganizationType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuperAdminCreateOrganizationRequestDTO {

    @NotBlank(message = "{superAdmin.organization.name.required}")
    @Size(max = 255, message = "{superAdmin.organization.name.size}")
    private String name;

    @NotBlank(message = "{superAdmin.organization.code.required}")
    @Size(max = 100, message = "{superAdmin.organization.code.size}")
    private String code;

    @NotBlank(message = "{superAdmin.organization.timezone.required}")
    @Size(max = 120, message = "{superAdmin.organization.timezone.size}")
    private String timezone;

    @NotBlank(message = "{superAdmin.organization.contactEmail.required}")
    @Email(message = "{superAdmin.organization.contactEmail.invalid}")
    @Size(max = 255, message = "{superAdmin.organization.contactEmail.size}")
    private String contactEmail;

    @Size(max = 32, message = "{superAdmin.organization.contactPhone.size}")
    private String contactPhone;

    @Size(max = 1000, message = "{superAdmin.organization.notes.size}")
    private String notes;

    private OrganizationType type;

    /**
     * Data-residency region for the new tenant (MVP-9). Optional — when
     * null the provisioning service falls back to the platform default
     * (BF) so legacy callers keep working.
     */
    private OrganizationRegion region;
}
