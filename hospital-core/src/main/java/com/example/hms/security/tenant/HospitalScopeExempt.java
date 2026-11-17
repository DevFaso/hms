package com.example.hms.security.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler whose {@code hospitalId} path variable or request parameter
 * is NOT the hospital the request acts at, so {@link HospitalIdNarrowingInterceptor}
 * leaves the request's scope alone — for example a patient choosing which
 * hospital to book at, whose own scope holds no hospital at all.
 *
 * <p>Every use carries a reason, and {@code HospitalIdParameterCoverageTest}
 * freezes the set: a new exemption is a decision, not a default.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface HospitalScopeExempt {

    /** Why the hospital id this handler receives is not its acting scope. */
    String reason();
}
