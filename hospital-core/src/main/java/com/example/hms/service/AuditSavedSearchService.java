package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.AuditSavedSearchRequestDTO;
import com.example.hms.payload.dto.superadmin.AuditSavedSearchResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * Server-side persistence for audit saved searches (MVP-c batch —
 * MVP-8c). Replaces MVP-8b's per-operator localStorage with a shared
 * store. The current super admin's username is the owner partition;
 * sharing is opt-in via the {@code shared} flag.
 */
public interface AuditSavedSearchService {

    /** List the caller's own searches plus any other super admin's shared searches. */
    List<AuditSavedSearchResponseDTO> listVisible();

    /** Create a new search for the caller; rejects when name collides with an existing one for this owner. */
    AuditSavedSearchResponseDTO create(AuditSavedSearchRequestDTO request);

    /** Update an existing search. The caller must own the row — sharing is read-only across owners. */
    AuditSavedSearchResponseDTO update(UUID id, AuditSavedSearchRequestDTO request);

    /** Delete a search. The caller must own the row. */
    void delete(UUID id);
}
