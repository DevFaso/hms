package com.example.hms.payload.dto;


import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = LicenseRequiredForMedicalRolesValidator.class)
@Documented
public @interface LicenseRequiredForMedicalRoles {
    String message() default "licenseNumber is required for medical staff roles.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
