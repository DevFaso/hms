package com.example.hms.payload.dto.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.ProviderVerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A provider facility's profile, for its own staff ({@code GET /provider/profile}).
 *
 * <p>Two parts. The operational contact (phone, email, website) is what the
 * facility's PROVIDER_ADMIN may change. The verified identity (names,
 * licence, registered city and region, company phone, verification status)
 * is what the platform verified from the registration documents, read-only
 * here; only a super-admin changes it, through a new verification. The
 * business numbers and the names of the gérant and of the responsible
 * professional are not part of it: the facility's staff do not need them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A provider facility's profile: operational contact (editable) and verified identity (read-only).")
public class ProviderProfileDTO {

    private UUID id;
    private FacilityType facilityType;
    private String code;

    // Operational contact: PUT /provider/profile.
    private String phoneNumber;
    private String email;
    private String website;

    // Verified identity, read-only, from the VERIFIED evidence only; all null
    // while none is verified (a first submission, a rejection, a revocation).
    /** The trade name, else the legal name, as verified. */
    private String name;
    private String address;
    private String city;
    private String region;
    private String legalName;
    private String tradeName;
    private String licenceNumber;
    private String licenceAuthority;
    private String companyPhone;
    private LocalDateTime verifiedAt;

    /** The current state of the evidence (the latest verification row), whatever it is. */
    private ProviderVerificationStatus verificationStatus;

    /** The caller may change the operational contact and manage staff (PROVIDER_ADMIN here). */
    private boolean editable;
}
