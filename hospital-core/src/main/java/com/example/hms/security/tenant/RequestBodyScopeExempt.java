package com.example.hms.security.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler whose request body names a patient or a staff member that
 * {@link RequestBodyScopeAdvice} must not measure against the acting hospital,
 * because reaching a row outside it is what the handler is for (the desk
 * linking a patient registered at another hospital of the organisation, an
 * emergency access declaration). The handler's service applies its own rule.
 *
 * <p>Every use names the fields it lifts and why, and
 * {@code RequestBodyScopeCoverageTest} freezes the set: a new exemption is a
 * decision, not a default.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequestBodyScopeExempt {

    /** The body fields left to the handler: {@code patientId}, or a staff field by its name. */
    String[] fields();

    /** The rule the handler's service applies instead. */
    String reason();
}
