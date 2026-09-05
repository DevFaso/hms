package com.example.hms.security.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tunes how {@link WriteAuditInterceptor} records a successful POST / PUT /
 * PATCH / DELETE. Every write handler is recorded by default; this exists
 * for the cases the convention gets wrong, and to opt out with a reason.
 *
 * <p>On a class it applies to every handler in the controller; on a method
 * it overrides the class.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface WriteAudited {

    /**
     * Entity type for the audit row, in the canonical upper-snake form the
     * audit contract uses ({@code TRANSFUSION}, {@code MICRO_CULTURE}). Empty
     * means "derive it from the route's first segment".
     */
    String entity() default "";

    /** Path variable holding the written resource's id. Empty means {@code {id}}, then any UUID path variable. */
    String idVar() default "";

    /** Path variable or query parameter holding the patient the write concerns. Empty means {@code patientId}. */
    String patientIdVar() default "";

    /**
     * Do not record. Only for controllers whose service already emits a more
     * specific event (PATIENT_UPDATE, PRESCRIPTION_CREATED, …) — a second
     * generic row for the same action would double-count it — or for surfaces
     * that are not clinical or administrative writes at all. {@link #reason}
     * is mandatory with it; {@code WriteAuditCoverageTest} enforces that.
     */
    boolean skip() default false;

    /** Why {@link #skip} is justified. */
    String reason() default "";
}
