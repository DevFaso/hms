package com.example.hms.enums;

import java.util.Set;

/**
 * What kind of access or disclosure an audit row represents, from the
 * patient's point of view (Tier 2 item 39).
 *
 * <p>This enum is the single place that decides <b>what appears on a
 * patient's disclosure report</b>, and it is deliberately a whitelist:
 * {@link #accountableEventTypes()} is what the query filters on, and an
 * {@link AuditEventType} that is not classified here does not reach a
 * patient. That direction is the safe one. The alternative — return every
 * row keyed to the patient and classify what we recognise — means the next
 * person to key an event type by patient publishes it to patients without
 * anyone deciding to, and some of those rows carry staff notes, internal
 * reasons and system detail that were never written for that audience.
 *
 * <p><b>Event type alone is not enough to classify a row.</b>
 * {@code PATIENT_ACCESS} is emitted both by the clinical services, where it
 * means a clinician opened the chart, and by the eligibility service, where
 * it means the patient's identity and coverage were sent to an outside
 * insurance scheme. Those are not the same event to a patient and must not
 * share a label, so {@link #classify} takes the entity type too.
 */
public enum DisclosureCategory {

    /**
     * Emergency override — a clinician read the chart under break-the-glass
     * without a pre-existing consent. The category this whole feature exists
     * for: until V141 these rows keyed on the session id, so they never
     * appeared on the patient's own access list.
     */
    EMERGENCY_ACCESS,

    /** A member of the care team opened the record in the course of treatment. */
    TREATMENT_ACCESS,

    /** The record was released to another hospital under a consent. */
    SHARED_WITH_PROVIDER,

    /**
     * Identity and coverage were sent to an insurance scheme. A disclosure to
     * a third party, not internal treatment access, even though the
     * underlying event type reads {@code PATIENT_ACCESS}.
     */
    INSURANCE,

    /** A copy of the record left the system as a file or export. */
    COPY_RELEASED,

    /** The record was merged with another identity. */
    IDENTITY_CHANGE;

    /** Entity type stamped by {@code EligibilityServiceImpl} on its audit rows. */
    private static final String ELIGIBILITY_ENTITY = "EligibilityCheck";

    /**
     * Classify one audit row, or {@code null} if it is not something a
     * patient is shown.
     *
     * @param eventType  the row's event type
     * @param entityType the row's target entity type; load-bearing for
     *                   {@code PATIENT_ACCESS}, which means two different
     *                   things depending on who emitted it
     */
    public static DisclosureCategory classify(AuditEventType eventType, String entityType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case BREAK_GLASS_ACCESS -> EMERGENCY_ACCESS;
            case RECORD_SHARE -> SHARED_WITH_PROVIDER;
            case PATIENT_EXPORT, DATA_EXPORT -> COPY_RELEASED;
            case PATIENT_MERGE -> IDENTITY_CHANGE;
            case PATIENT_ACCESS -> ELIGIBILITY_ENTITY.equalsIgnoreCase(entityType)
                ? INSURANCE
                : TREATMENT_ACCESS;
            default -> null;
        };
    }

    /**
     * The event types that can reach a patient's disclosure report.
     *
     * <p>Must stay in step with {@link #classify} — {@code
     * DisclosureCategoryTest} pins that. Kept as an explicit set rather than
     * derived by iterating {@link AuditEventType} and calling
     * {@link #classify}, because deriving it would make adding a type to the
     * switch silently widen what patients see; here both edits are visible in
     * one diff.
     */
    public static Set<AuditEventType> accountableEventTypes() {
        return Set.of(
            AuditEventType.BREAK_GLASS_ACCESS,
            AuditEventType.RECORD_SHARE,
            AuditEventType.PATIENT_EXPORT,
            AuditEventType.DATA_EXPORT,
            AuditEventType.PATIENT_MERGE,
            AuditEventType.PATIENT_ACCESS
        );
    }

    /**
     * True for the categories that are disclosures to someone outside the
     * treating team, as opposed to the treating team reading the chart.
     *
     * <p>Used to headline the report. Ordinary treatment access dominates the
     * row count by orders of magnitude, so a list sorted only by date buries
     * the three rows a patient actually came to find under six months of
     * routine chart opens.
     */
    public boolean isExternalDisclosure() {
        return this == SHARED_WITH_PROVIDER
            || this == INSURANCE
            || this == COPY_RELEASED;
    }
}
