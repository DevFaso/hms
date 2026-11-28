package com.example.hms.payload.dto.medication;

import java.util.UUID;

/**
 * QA D4180 (r81): an entry (or a proposal still waiting) that looks like the one being proposed, shown to the
 * proposer and to the approver so that the same medicine is not entered twice.
 *
 * @param catalogItemId the entry in the catalogue; null for a proposal that is not in it yet
 * @param versionId     the waiting proposal; null for an entry of the catalogue
 * @param label         name, strength and form, as the catalogue writes them
 * @param form          the dosage form's code, for the reader's label
 * @param exact         same name, same form and same strength: the server refuses the duplicate
 * @param state         IN_CATALOGUE, WITHDRAWN (no longer prescribed) or PENDING (a proposal awaiting a decision)
 * @param platform      an entry of the national list, shared by every hospital
 */
public record MedicationCatalogSimilarEntryDTO(UUID catalogItemId, UUID versionId, String label, String form,
    boolean exact, String state, boolean platform) {
}
