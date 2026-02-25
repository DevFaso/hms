package com.example.hms.enums;

/**
 * Describes the organisational distance across which a patient record is being shared.
 *
 * <p>Resolution priority (fastest → widest):
 * <ol>
 *   <li>{@link #SAME_HOSPITAL}  – requesting hospital <em>is</em> the source hospital.</li>
 *   <li>{@link #INTRA_ORG}     – both hospitals belong to the same {@link com.example.hms.model.Organization}.</li>
 *   <li>{@link #CROSS_ORG}     – hospitals belong to different organisations; an explicit bilateral consent is required.</li>
 * </ol>
 */
public enum ShareScope {

    /**
     * The requesting hospital is the same as the source hospital.
     * No consent negotiation is needed — the hospital already owns the data.
     */
    SAME_HOSPITAL("Same-hospital access"),

    /**
     * The source hospital and the requesting hospital belong to the same organisation.
     * An intra-org consent (which may be auto-granted by policy) is sufficient.
     */
    INTRA_ORG("Intra-organisation share"),

    /**
     * The hospitals belong to different organisations.
     * An explicit, time-bound cross-org consent is required.
     */
    CROSS_ORG("Cross-organisation share");

    private final String label;

    ShareScope(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
