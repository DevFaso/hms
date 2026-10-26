package com.example.hms.service.platform;

import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigRequestDTO;
import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigResponseDTO;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-API surface for the per-hospital ADT auto-create configuration
 * (roadmap row 24 admin-UI follow-on). Backs the Angular
 * "ADT intake config" page under {@code /admin/adt-intake-configs}.
 *
 * <p>The table is exactly one row per hospital — {@link #upsert} is
 * idempotent and replaces an existing row in-place rather than
 * inserting a duplicate. {@link #findByHospital} is the read-only
 * pre-fill the UI uses to populate the form when an operator opens
 * the page.
 */
public interface AdtIntakeProviderConfigService {

    /**
     * QA D852: the roles that may admit a patient, in both spellings the role
     * table holds. The same set gates the config write and the A01/A04
     * auto-create that reads the config, so a config saved before the rule
     * existed cannot admit under a non-physician either.
     */
    Set<String> ADMITTING_ROLE_CODES = Set.of(
        "ROLE_DOCTOR", "DOCTOR", "ROLE_PHYSICIAN", "PHYSICIAN", "ROLE_SURGEON", "SURGEON");

    AdtIntakeProviderConfigResponseDTO upsert(
        AdtIntakeProviderConfigRequestDTO request, Locale locale);

    AdtIntakeProviderConfigResponseDTO getById(UUID id, Locale locale);

    Optional<AdtIntakeProviderConfigResponseDTO> findByHospital(UUID hospitalId);

    List<AdtIntakeProviderConfigResponseDTO> findAll();

    void delete(UUID id, Locale locale);
}
