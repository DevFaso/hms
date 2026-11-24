package com.example.hms.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A request body that names every property it does not take (QA D1672, r44).
 *
 * <p>Jackson skips a property the request class does not declare, so an edit
 * dialog that sent the admission's chief complaint, type, time and clinician
 * to an update that takes none of them answered 200, kept the old values, and
 * the portal said "Admission updated". On a class carrying this annotation,
 * {@code RequestBodyDefaultsModule} records such a property while the body is
 * read and the request is refused with a 400 naming it ("cannot be changed
 * here"), together with every other refusal of the body. It never reaches the
 * controller. Outside a request body read the class keeps Jackson's default.
 *
 * <p>For an UPDATE request, where a property the server does not take is a
 * change that would be dropped ({@code AdmissionEditDialogGuardTest}), and for
 * the criteria of a SEARCH, where a misspelt criterion would otherwise widen
 * the search to every row (QA D3753): the refusal then names the criteria the
 * search accepts.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RejectsUnknownFields {

    /** What the body is, which words the refusal. */
    Kind value() default Kind.UPDATE;

    /**
     * An update ("cannot be changed here"), the criteria of a search ("not a criterion of this
     * search"), or the fields of an action such as a send ("not a field of this request").
     */
    enum Kind { UPDATE, SEARCH, ACTION }
}
