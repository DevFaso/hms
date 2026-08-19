package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.r4.model.IdType;

import java.util.UUID;

/** Internal helpers for resolving FHIR ids/references to UUIDs. */
final class FhirIds {

    private FhirIds() {}

    static UUID parseOrThrow(IdType id) {
        if (id == null || id.getIdPart() == null) {
            throw new ResourceNotFoundException(id);
        }
        try {
            return UUID.fromString(id.getIdPart());
        } catch (IllegalArgumentException ex) {
            throw new ResourceNotFoundException(id);
        }
    }

    static UUID tryParse(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Resolves a {@code patient=Patient/{uuid}} or bare-id reference to a UUID.
     * Returns {@code null} for missing or malformed input.
     */
    static UUID fromReference(ReferenceParam ref) {
        if (ref == null) return null;
        String idPart = ref.getIdPart();
        if (idPart != null) return tryParse(idPart);
        return tryParse(ref.getValue());
    }
}
