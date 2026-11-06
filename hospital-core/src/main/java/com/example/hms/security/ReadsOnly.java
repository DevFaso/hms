package com.example.hms.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA D1404: this POST reads. A search with a filter body, a calculation, a
 * verification: nothing of the record changes, so it keeps working in downtime
 * read-only mode ({@link ReadOnlyModeFilter}). The declaration lives on the
 * endpoint, so a route's name proves nothing either way ({@code /eligibility/check}
 * writes, {@code /prenatal/schedule} reads). An access-audit row is not a write of
 * the record: the audit log keeps working in downtime on purpose.
 *
 * <p>The one source of truth: {@code WriteAuditInterceptor} reads it too, so a declared read
 * leaves no {@code DATA_CREATE} row and anything else under POST is a recorded write.
 *
 * <p>{@code ReadOnlyPostGuardTest} fails when a POST named as a read is neither
 * declared here nor listed there as a write despite its name.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadsOnly {

    /** Why this POST changes nothing. */
    String value();
}
