package com.example.hms.payload.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class LicenseRequiredForMedicalRolesValidator
    implements ConstraintValidator<LicenseRequiredForMedicalRoles, AdminSignupRequest> {

    private static final Set<String> MEDICAL_ROLE_CODES = Set.of(
        "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_LAB_SCIENTIST", "ROLE_PHARMACIST"
    );

    @Override
    public boolean isValid(AdminSignupRequest req, ConstraintValidatorContext ctx) {
        if (req == null) return true;

        var roles = req.getRoleNames();
        if (roles == null || roles.isEmpty()) return true; // handled by @NotEmpty already

        boolean hasMedical = roles.stream()
            .filter(r -> r != null)
            .map(r -> r.trim().toUpperCase())
            .map(code -> code.startsWith("ROLE_") ? code : "ROLE_" + code)
            .anyMatch(MEDICAL_ROLE_CODES::contains);

        if (!hasMedical) return true; // license not required for non-medical roles

        String lic = req.getLicenseNumber();
        boolean ok = lic != null && !lic.trim().isEmpty();
        if (!ok) {
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(
                    "licenseNumber is required when roles include a medical role.")
                .addPropertyNode("licenseNumber")
                .addConstraintViolation();
        }
        return ok;
    }
}
