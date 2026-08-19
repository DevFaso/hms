package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload returned by GET /api/auth/session/bootstrap.
 *
 * <p>Provides authoritative session context sourced from the DB, replacing
 * client-side decoding of JWT claims for hospital/permission resolution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionBootstrapResponseDTO {

    // ── Identity ────────────────────────────────────────────────────────────
    private UUID userId;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String profileImageUrl;

    /** Auth source: "internal" | "keycloak" | "saml" */
    private String authSource;

    // ── Tenant / Hospital context ────────────────────────────────────────────
    private UUID primaryHospitalId;
    private String primaryHospitalName;
    private List<UUID> permittedHospitalIds;

    // ── Roles & flags ────────────────────────────────────────────────────────
    private List<String> roles;
    private boolean superAdmin;
    private boolean hospitalAdmin;

    // ── Staff profile (null when the user has no staff record) ───────────────
    private UUID staffId;
    private String staffRoleCode;
    private UUID departmentId;
    private String departmentName;

    // ── Patient profile (null when the user has no patient record) ───────────
    private UUID patientId;

    // ── Timestamps ───────────────────────────────────────────────────────────
    /** Populated when authSource == "keycloak"; null for internal-auth users. */
    private Instant lastOidcLoginAt;
}
