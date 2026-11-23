package com.example.hms.service.clinicalrules;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that enforces one or more PROVISIONAL rule sets: rules built from a published source or an owner
 * decision that stay « provisoire » at a hospital until its named approver signs them off (V356).
 *
 * <p>Every value must have an entry in {@link ClinicalRuleRegistry}, and every class an entry names must carry the
 * annotation with that key: {@code ClinicalRuleRegistryGuardTest} holds both directions, and fails on a file that
 * says PROVISIONAL without either carrying this annotation or naming the class that does.
 *
 * <p>The annotation changes no behaviour: a rule enforces the same way whether a hospital signed it off or not.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProvisionalRule {

    /** The rule sets this class enforces (part of). */
    ClinicalRuleSetKey[] value();
}
