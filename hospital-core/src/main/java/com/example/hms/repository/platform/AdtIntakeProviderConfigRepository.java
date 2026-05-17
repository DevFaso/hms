package com.example.hms.repository.platform;

import com.example.hms.model.platform.AdtIntakeProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the per-hospital ADT auto-create configuration (roadmap row 24
 * follow-on, v1.1 / Interop HL7). The MLLP worker has no
 * {@code HospitalContext}, so lookups bypass tenant scoping and key
 * directly off the receiving hospital's UUID — the same shape used by
 * {@link MllpAllowedSenderRepository}.
 *
 * <p>The auto-create path uses the {@code AndEnabledTrue} variant; the
 * un-filtered finder is exposed for admin-surface CRUD that may need
 * to read a disabled config to display its state.
 */
public interface AdtIntakeProviderConfigRepository
    extends JpaRepository<AdtIntakeProviderConfig, UUID> {

    /**
     * Hot path for the projection service: returns the config only
     * when the per-hospital opt-in is true. A missing row OR a row
     * with {@code enabled=false} short-circuits auto-create back to
     * the no-match log-and-skip flow.
     */
    Optional<AdtIntakeProviderConfig> findByHospital_IdAndEnabledTrue(UUID hospitalId);

    /** Admin-surface read; tolerates the disabled state. */
    Optional<AdtIntakeProviderConfig> findByHospital_Id(UUID hospitalId);
}
