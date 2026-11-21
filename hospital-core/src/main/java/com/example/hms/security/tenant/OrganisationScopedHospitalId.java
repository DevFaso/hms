package com.example.hms.security.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA D3058 (review of #1264): a handler whose {@code hospitalId} narrows a
 * LISTING that its service already bounds by the caller's organisation scope
 * (the organisation admin's « Inscrire » and « Affectations » hospitals).
 *
 * <p>An organisation admin holds no assignment at most of its organisation's
 * hospitals, so {@link HospitalIdNarrowingInterceptor} refused such a
 * {@code ?hospitalId=} with 403. On a handler marked with this, a hospital the
 * interceptor would refuse is let through, un-narrowed, when it lies inside the
 * caller's organisation scope ({@code UserAccountAccess.organisationHospitalIds});
 * anything else is still refused. Never wider than the organisation, and only
 * where the service itself bounds the listing by that scope (say how in
 * {@link #reason()}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OrganisationScopedHospitalId {

    /** How the handler's service bounds the hospital it lists by the caller's scope. */
    String reason();
}
