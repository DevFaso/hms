package com.example.hms.enums;

/** Go-live D2 (V385): what a version of a medication catalogue entry asks for. */
public enum MedicationCatalogChangeType {
    /** A new entry: it exists in the catalogue only once approved. */
    CREATE,
    /** New values for an entry already in the catalogue. */
    UPDATE,
    /** The values already in force, unchanged, submitted so that an entry nobody approved yet gets its approval. */
    RATIFY,
    /**
     * QA D4190 (V426): the entry taken out of the catalogue. It acts at once (a recall does not wait for a second
     * person); the row is the history line that says who, when and why.
     */
    WITHDRAWAL
}
