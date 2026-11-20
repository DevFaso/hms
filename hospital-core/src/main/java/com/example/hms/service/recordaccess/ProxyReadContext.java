package com.example.hms.service.recordaccess;

import com.example.hms.enums.ProxyRelationship;

import java.util.Set;
import java.util.UUID;

/**
 * Policy 2026-11-20: who is reading on a proxy read, as the grant check
 * established it. Built in one place ({@code PatientPortalServiceImpl}'s grant
 * check) from the live grant; the read filter consults it and nothing else
 * may open one ({@code ProxyReadSensitivityGuardTest}).
 *
 * @param grantId            the family-access grant the read rests on
 * @param grantorPatientId   the patient whose record is read
 * @param relationship       what the grant declares the holder to be
 * @param relationshipVerified staff checked that relationship
 * @param scopes             the grant's scopes, canonical and upper-cased
 */
public record ProxyReadContext(UUID grantId, UUID grantorPatientId, ProxyRelationship relationship,
                               boolean relationshipVerified, Set<String> scopes) {

    /**
     * The grant scope that opens a RESTRICTED category (tuberculosis). It is
     * explicit: ALL does not include it, because ALL was granted before the
     * scope existed and never meant it.
     */
    public static final String SCOPE_TUBERCULOSIS = "VIEW_TUBERCULOSIS";

    public ProxyReadContext {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** A parent or legal guardian whose relationship staff verified. */
    public boolean verifiedParentOrGuardian() {
        return relationshipVerified
            && (relationship == ProxyRelationship.PARENT || relationship == ProxyRelationship.LEGAL_GUARDIAN);
    }

    public boolean holdsRestrictedScope() {
        return scopes.contains(SCOPE_TUBERCULOSIS);
    }
}
