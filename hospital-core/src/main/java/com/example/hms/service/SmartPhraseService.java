package com.example.hms.service;

import com.example.hms.payload.dto.SmartPhraseRequestDTO;
import com.example.hms.payload.dto.SmartPhraseResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SmartPhrase / dot-phrase macro library used by the per-section EncounterNote
 * form. Resolution precedence at autocomplete time is USER > HOSPITAL > GLOBAL
 * — a user macro shadows a hospital one with the same trigger; a hospital
 * macro shadows a global one.
 *
 * <p>Mutations ({@link #create}, {@link #update}, {@link #delete}) read the
 * authenticated principal from the Spring Security context and enforce
 * scope-based authorization internally — a clinician with a known id cannot
 * edit another user's macros or create GLOBAL/HOSPITAL macros for hospitals
 * they do not staff.
 */
public interface SmartPhraseService {

    SmartPhraseResponseDTO create(SmartPhraseRequestDTO request);

    SmartPhraseResponseDTO update(UUID id, SmartPhraseRequestDTO request);

    void delete(UUID id);

    SmartPhraseResponseDTO get(UUID id);

    /** Page through GLOBAL phrases (admin library view). */
    Page<SmartPhraseResponseDTO> listGlobal(Pageable pageable);

    /**
     * Trigger-prefix autocomplete for the calling user at their active hospital.
     * Sorted USER > HOSPITAL > GLOBAL, then alphabetical by trigger.
     */
    List<SmartPhraseResponseDTO> autocomplete(String prefix, UUID hospitalId);

    /** Look up a single SmartPhrase by exact trigger for the caller. */
    Optional<SmartPhraseResponseDTO> findByTrigger(String trigger, UUID hospitalId);

    /** Increment usage_count + last_used_at — fire-and-forget from the FE. */
    void recordUsage(UUID id);
}
