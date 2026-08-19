package com.example.hms.payload.dto;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JwtResponse {
    @Builder.Default private String tokenType = "Bearer";
    private String accessToken;
    private String refreshToken;

    private long issuedAt;
    private long accessTokenExpiresAt;
    private long refreshTokenExpiresAt;

    private UUID id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String phoneNumber;
    private LocalDate dateOfBirth;
    private String gender;
    private List<String> roles;
    private String profileType;
    private String licenseNumber;
    private UUID patientId;
    private UUID staffId;
    private String roleName;
    private boolean active;
    private String profilePictureUrl;

    /** True when the user must change their password before accessing the application. */
    private boolean forcePasswordChange;

    /** True when the user must choose a new username before accessing the application. */
    private boolean forceUsernameChange;

    // ── Hospital assignment context (sourced from active UserRoleHospitalAssignment) ──

    /** The primary hospital this user is assigned to (first active assignment). */
    private UUID primaryHospitalId;
    /** Display name of the primary hospital. */
    private String primaryHospitalName;
    /** All hospital IDs this user is permitted to access (active assignments). */
    private List<UUID> hospitalIds;

    // ── Multi-role selection ──

    /**
     * True when the user holds more than one role and must pick which role to
     * log in as.  When this flag is set the response contains NO tokens — the
     * client must re-submit the login request with {@code selectedRole} set.
     */
    private boolean roleSelectionRequired;

    /**
     * The roles the user may choose from.  Only populated when
     * {@link #roleSelectionRequired} is {@code true}.
     */
    private List<String> availableRoles;

    // ── MFA challenge ──

    /** True when MFA verification is required before tokens are issued. */
    private boolean mfaRequired;

    /** True when the user has already enrolled in MFA (TOTP). */
    private boolean mfaEnrolled;

    /** Short-lived token used to authorize the MFA verification step. */
    private String mfaToken;

}
