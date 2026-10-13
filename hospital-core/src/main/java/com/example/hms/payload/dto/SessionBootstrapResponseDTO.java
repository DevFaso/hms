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

    /**
     * The caller is confined to a provider facility (a pharmacy or a
     * laboratory; a verified super-admin never is), by the same rule the
     * confinement filter applies. The portal skips what such a user may not
     * reach, e.g. the emergency-broadcast socket.
     */
    private boolean providerUser;

    /**
     * Provider plan AC-13: a {@link #providerUser} whose token carries no
     * second factor (no {@code otp} in {@code amr}, on either auth path). Every
     * request outside sign-in and MFA enrolment then answers 403
     * {@code mfa.enrollment.required}, so the portal sends the user to MFA
     * enrolment or the challenge (legacy) or back through Keycloak with OTP.
     * Always false for a hospital user and a verified super-admin.
     */
    private boolean mfaEnrollmentRequired;

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
