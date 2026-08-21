package com.example.hms.service;

import com.example.hms.enums.InteractionSeverity;
import com.example.hms.payload.dto.medication.DrugInteractionDTO;

import java.util.List;
import java.util.UUID;

/**
 * Curation of the drug-interaction knowledge base (P2 #14).
 *
 * <p>This is the durable half of the KB work. Seeding rows through a migration
 * gets the table populated once; it does not let the people qualified to
 * maintain clinical content maintain it. A knowledge base that can only be
 * changed by a developer writing SQL is a knowledge base that stops being
 * updated.
 */
public interface DrugInteractionAdminService {

    List<DrugInteractionDTO> list(InteractionSeverity severity, boolean activeOnly);

    List<DrugInteractionDTO> findForDrug(String drugCode);

    DrugInteractionDTO create(DrugInteractionDTO request);

    DrugInteractionDTO update(UUID id, DrugInteractionDTO request);

    /**
     * Deactivate rather than delete: an interaction that once fired is part of
     * why a prescriber saw the alert they saw, and deleting the row rewrites
     * that history.
     */
    DrugInteractionDTO deactivate(UUID id);
}
