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

    /**
     * QA D852/D872: whether {@code assignment} is one the admitting physician
     * holds as a physician. An A04 encounter is recorded under the configured
     * staff AND the default assignment, so the assignment must be the same
     * person's, in a doctor, physician or surgeon role. Before, any active
     * assignment of the hospital was accepted: a receptionist's or another
     * doctor's, which recorded encounters under someone else's role.
     */
    static boolean isAdmittingAssignmentOf(
            com.example.hms.model.UserRoleHospitalAssignment assignment, UUID providerUserId) {
        if (assignment == null || providerUserId == null || assignment.getUser() == null
                || !providerUserId.equals(assignment.getUser().getId())) {
            return false;
        }
        com.example.hms.model.Role role = assignment.getRole();
        if (role == null) {
            return false;
        }
        String code = role.getCode() != null && !role.getCode().isBlank() ? role.getCode() : role.getName();
        return code != null && ADMITTING_ROLE_CODES.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    AdtIntakeProviderConfigResponseDTO upsert(
        AdtIntakeProviderConfigRequestDTO request, Locale locale);

    AdtIntakeProviderConfigResponseDTO getById(UUID id, Locale locale);

    Optional<AdtIntakeProviderConfigResponseDTO> findByHospital(UUID hospitalId);

    List<AdtIntakeProviderConfigResponseDTO> findAll();

    void delete(UUID id, Locale locale);
}
