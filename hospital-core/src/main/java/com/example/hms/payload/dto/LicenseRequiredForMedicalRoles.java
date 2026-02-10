package com.example.hms.payload.dto;


import jakarta.validation.*;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = LicenseRequiredForMedicalRolesValidator.class)
@Documented
public @interface LicenseRequiredForMedicalRoles {
    String message() default "licenseNumber is required for medical staff roles.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
