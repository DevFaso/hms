package com.example.hms.repository.platform;

import com.example.hms.model.platform.FeatureFlagOverride;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeatureFlagOverrideRepository extends JpaRepository<FeatureFlagOverride, UUID> {

    /**
     * MVP-pre-7b global lookup. With per-tenant overrides (MVP-7b)
     * landing, callers should prefer {@link #findGlobalByFlagKey} or
     * {@link #findByFlagKeyAndOrganizationId} so a global lookup does
     * not accidentally match a per-tenant row. Kept for legacy
     * call-sites that always operated on the (single) global row.
     */
    Optional<FeatureFlagOverride> findByFlagKeyIgnoreCase(String flagKey);

    List<FeatureFlagOverride> findAllByOrderByFlagKeyAsc();

    /**
     * MVP-7b: return the global row for {@code flagKey} (the row whose
     * {@code organization_id} is NULL). At most one global row per key
     * is enforced by the V83 composite UNIQUE.
     */
    @Query("""
        select o
          from FeatureFlagOverride o
         where lower(o.flagKey) = lower(:flagKey)
           and o.organizationId is null
        """)
    Optional<FeatureFlagOverride> findGlobalByFlagKey(@Param("flagKey") String flagKey);

    /**
     * MVP-7b: return the per-tenant row for {@code flagKey} narrowed to
     * {@code organizationId}. When {@code organizationId} is null this
     * resolves to the global row (mirrors {@link #findGlobalByFlagKey}).
     */
    @Query("""
        select o
          from FeatureFlagOverride o
         where lower(o.flagKey) = lower(:flagKey)
           and ((:organizationId is null and o.organizationId is null)
                or o.organizationId = :organizationId)
        """)
    Optional<FeatureFlagOverride> findByFlagKeyAndOrganizationId(
        @Param("flagKey") String flagKey,
        @Param("organizationId") UUID organizationId);

    /**
     * MVP-7b: every per-tenant override row for {@code organizationId},
     * sorted alphabetically by flag key. Used by the resolver to layer
     * tenant overrides on top of the global merge.
     */
    List<FeatureFlagOverride> findByOrganizationIdOrderByFlagKeyAsc(UUID organizationId);
}
