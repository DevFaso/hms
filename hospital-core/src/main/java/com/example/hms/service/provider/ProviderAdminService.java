package com.example.hms.service.provider;

import com.example.hms.payload.dto.provider.ProviderProfileDTO;
import com.example.hms.payload.dto.provider.ProviderSettingsDTO;
import com.example.hms.payload.dto.provider.ProviderStaffMemberDTO;

import java.util.List;
import java.util.Optional;

/**
 * A provider facility administering itself (provider plan US-2, AC-6, §6.5):
 * its profile, its staff and the shell's settings.
 *
 * <p>Every method answers {@link Optional#empty()} for anything outside the
 * caller's reach: a caller with no seat at a provider facility, a caller who
 * is not its PROVIDER_ADMIN where that is required, and a staff member who is
 * unknown, works elsewhere, is a peer administrator or is the caller. The
 * controller answers all of them alike, exactly as an unmapped path. A staff
 * member's id arrives as the raw path segment and is parsed after the seat
 * check, so a malformed id answers like an unknown one.
 */
public interface ProviderAdminService {

    /** Any staff member of the facility. */
    Optional<ProviderProfileDTO> getProfile();

    /**
     * PROVIDER_ADMIN at the facility: the operational contact only. The body
     * arrives raw and is parsed and validated AFTER the caller's seat is
     * checked, so a caller with no seat gets the unmapped answer whatever the
     * body holds (malformed JSON included).
     *
     * @param rawBody the request body as sent, a {@code ProviderProfileUpdateDTO} in JSON
     * @throws IllegalArgumentException for a body from the admin that is not that JSON
     * @throws jakarta.validation.ConstraintViolationException for an invalid request from the admin
     */
    Optional<ProviderProfileDTO> updateProfile(String rawBody);

    /** PROVIDER_ADMIN at the facility. */
    Optional<List<ProviderStaffMemberDTO>> listStaff();

    /**
     * PROVIDER_ADMIN at the facility: retire every assignment the member holds
     * here (inactive, its invitation code revoked).
     */
    Optional<ProviderStaffMemberDTO> deactivateStaff(String userId);

    /**
     * PROVIDER_ADMIN at the facility: send the member a new invitation code
     * for each assignment here that is not active. The assignment comes on
     * when its holder enters the code, as everywhere else on the platform;
     * only a super-admin switches an assignment on by hand. A disabled account
     * is out of reach (entering the code would switch it back on): only a
     * super-admin re-enables an account.
     */
    Optional<ProviderStaffMemberDTO> activateStaff(String userId);

    /** Any staff member of the facility. */
    Optional<ProviderSettingsDTO> getSettings();
}
