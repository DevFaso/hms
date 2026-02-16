package com.example.hms.payload.dto.superadmin;

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

    @NotBlank(message = "Organization name is required")
    @Size(max = 255, message = "Organization name must not exceed 255 characters")
    private String name;

    @NotBlank(message = "Organization code is required")
    @Size(max = 100, message = "Organization code must not exceed 100 characters")
    private String code;

    @NotBlank(message = "Timezone is required")
    @Size(max = 120, message = "Timezone must not exceed 120 characters")
    private String timezone;

    @NotBlank(message = "Primary contact email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Contact email must not exceed 255 characters")
    private String contactEmail;

    @Size(max = 32, message = "Contact phone must not exceed 32 characters")
    private String contactPhone;

    @Size(max = 1000, message = "Notes must not exceed 1000 characters")
    private String notes;

    private OrganizationType type;
}
