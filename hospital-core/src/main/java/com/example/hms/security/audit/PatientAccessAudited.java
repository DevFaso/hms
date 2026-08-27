package com.example.hms.security.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides how {@link PatientAccessAuditInterceptor} treats one endpoint.
 *
 * <p>The interceptor finds the patient by convention — a {@code {patientId}}
 * path variable, or {@code {id}} under {@code /patients/**}. That covers most
 * of the patient-scoped reads in this codebase, and covering them by default
 * is the point: the gap this closes formed precisely because every audit event
 * had to be remembered and written by hand, so the endpoints added later were
 * the ones that emitted nothing.
 *
 * <p>This annotation exists for the two cases convention cannot reach:
 *
 * <ul>
 *   <li>the patient id is under a different name, or arrives as a request
 *       parameter — give that name as {@link #value()};</li>
 *   <li>the endpoint should not be recorded at all — set {@link #skip()}.</li>
 * </ul>
 *
 * <p>Note what the absence of this annotation means: nothing. An endpoint with
 * no annotation and no recognisable patient id is simply not audited, because
 * the interceptor cannot tell which patient it concerned. That is a silent
 * gap by construction, which is why {@code PatientAccessCoverageTest} walks the
 * live handler mappings and fails on any patient-scoped read that resolves to
 * no patient and carries no opt-out — the convention has to be enforced, not
 * merely offered.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PatientAccessAudited {

    /**
     * Name of the path variable, or failing that the request parameter, that
     * holds the patient id.
     *
     * <p>Empty means "use the convention".
     */
    String value() default "";

    /**
     * Record nothing for this endpoint.
     *
     * <p>Use sparingly and say why at the call site. Three reasons are
     * legitimate:
     *
     * <ol>
     *   <li>the read is not a disclosure — a patient reading their own record,
     *       which the interceptor already skips by role;</li>
     *   <li>recording it would be circular, the disclosure log itself being
     *       the standing example, since reading your own access history would
     *       otherwise append to it;</li>
     *   <li><b>the request does not carry a patient id at all.</b> A handful
     *       of endpoints identify the patient by username, e-mail, phone or
     *       MRN, and resolving one to an id means a database lookup this
     *       interceptor has no business doing on every request. Those reads
     *       are real disclosures and they are genuinely not covered here —
     *       marking them is how that stays visible instead of looking like an
     *       oversight. The fix is an explicit emission in the service once it
     *       has the patient in hand.</li>
     * </ol>
     */
    boolean skip() default false;
}
