package com.example.hms.security.chart;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QA D3863: how a restricted chart (E8 #54) is enforced on one endpoint, when
 * the default does not fit.
 *
 * <p><b>The default, with no annotation, is the chart read.</b> For a caller
 * the restriction applies to ({@link RestrictedChartGate#isSubject()}) and who
 * holds no live break-the-glass session for the patient:
 * <ul>
 *   <li>a GET that names the patient (a {@code patientId} path variable or
 *       request parameter, {@code {id}} under {@code /patients/}) or a record
 *       of theirs ({@link ChartKeys}) is refused before the handler runs
 *       ({@link RestrictedChartGuardInterceptor});</li>
 *   <li>a GET whose answer IS a record of that patient is refused, and every
 *       row of that patient inside any other answer is masked down to who,
 *       where, when and status ({@link RestrictedChartResponseMasking}).</li>
 * </ul>
 *
 * <p>An endpoint that is not a read of the chart says so here, with the
 * reason; {@code RestrictedChartEnforcementGuardTest} lists every one.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RestrictedChartPolicy {

    Mode value();

    /** Why this endpoint is not a read of the chart. Required: a reviewer reads it. */
    String reason();

    enum Mode {
        /**
         * Not refused up front, but the answer is still masked: rows of a
         * restricted chart keep only who, where, when and status, and a
         * patient record keeps its identity without its clinical fields. The
         * patient page header, the desk's appointments and registrations.
         */
        MASK,
        /**
         * Neither refused nor masked: administrative data (billing, insurance,
         * consents to pay), the break-the-glass flow itself, and fulfilment
         * work that is authorised by the signed order rather than by the chart.
         */
        NONE
    }
}
