package com.example.hms.repository;

import com.example.hms.model.ProxySensitiveRelease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Policy 2026-11-20: the released results of a minor. Two readers only, both
 * listed in {@code ProxyReadSensitivityGuardTest}: the release service (the
 * clinician's side, scoped by patient and hospital) and the proxy read filter
 * (scoped by the proxy's own grant).
 */
public interface ProxySensitiveReleaseRepository extends JpaRepository<ProxySensitiveRelease, UUID> {

    /** The lab orders released to one grant: what the proxy read filter asks, once per read. */
    @Query("SELECT r.labOrderId FROM ProxySensitiveRelease r WHERE r.proxyGrantId = :grantId")
    Set<UUID> findReleasedLabOrderIds(@Param("grantId") UUID grantId);

    /** A patient's releases made at one hospital, newest first: the clinician's list. */
    List<ProxySensitiveRelease> findByPatientIdAndHospitalIdOrderByReleasedAtDesc(UUID patientId, UUID hospitalId);

    boolean existsByProxyGrantIdAndLabOrderId(UUID proxyGrantId, UUID labOrderId);
}
