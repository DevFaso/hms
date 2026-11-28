package com.example.hms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA D4025: a patient's own request that names no hospital, from a patient who must choose one (several
 * registrations, none of them the record's own), is refused on {@code hospitalId} with the body's other
 * refusals: an empty request named the purpose and the description, and the hospital only on the next press.
 * The service keeps its own check (the answer when no request is being read).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SelfRequestHospitalChosenValidator.class)
public @interface SelfRequestHospitalChosen {

    /** {@code patientPortal.request.chooseHospital}; the validator names the exact reason. */
    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
