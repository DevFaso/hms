package com.example.hms.fhir;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature-flag holder for the FHIR R4 write API (roadmap row 20).
 *
 * <p>Default is {@code enabled = false}. When disabled, FHIR
 * {@code @Create} and {@code @Update} handlers reject inbound writes
 * with {@code 405 Method Not Allowed} + a FHIR {@code OperationOutcome}
 * issue, and the {@code CapabilityStatement} omits the corresponding
 * interaction flags.
 *
 * <p>When enabled, the supported semantics are intentionally narrow:
 * <ul>
 *   <li>{@code PUT /Patient/{id}} updates a known patient's mutable
 *       contact + address fields. Identity columns
 *       (name / DOB / gender / email / phone) are intentionally
 *       <strong>not</strong> overwritten on this path — those changes
 *       must go through the registration admin flow with the audit
 *       trail it carries.</li>
 *   <li>{@code POST /Patient} requires {@code If-None-Exist} matching
 *       an active MRN identifier. Zero matches return {@code 404}
 *       (per the {@code empi-identity} skill: "Never auto-create a
 *       Patient from an unknown EMPI alias"). Multiple matches return
 *       {@code 412 Precondition Failed} — kept unreachable in
 *       practice by the partial unique index added in
 *       {@code V101__fhir_write_patient_idempotency.sql}.</li>
 * </ul>
 *
 * <p>{@code Encounter} and {@code Observation} write paths are
 * deferred to the row-20 follow-on (separate PR).
 */
@ConfigurationProperties(prefix = "app.fhir.write")
public class FhirWriteProperties {

    /**
     * Master switch for the FHIR write API. Default {@code false} so
     * deployments must opt in explicitly. Per-env value is delivered
     * by {@code FHIR_WRITE_ENABLED} in {@code application.properties}.
     */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
