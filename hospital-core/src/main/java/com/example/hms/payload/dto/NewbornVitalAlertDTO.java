package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * QA D1564: one newborn warning raised on a vital set recorded outside the maternity
 * newborn assessment (nurse station, triage, the vitals API). The message is rendered
 * in the recorder's language; the code is the newborn assessment's own alert code.
 * The bands behind it are PROVISIONAL, pending the neonatal lead's approval.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewbornVitalAlertDTO {

    /** The NewbornAlertRules finding code, e.g. {@code newborn-heart-rate}. */
    private String code;
    /** URGENT, CAUTION or INFO. */
    private String severity;
    /** The NewbornAlertType, e.g. CARDIOVASCULAR. */
    private String type;
    /** The warning, in the recorder's language. */
    private String message;
}
