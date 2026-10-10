package com.example.hms.payload.dto.provider;

import com.example.hms.payload.dto.NotificationDeliveryStatusDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * One person working at the caller's provider facility, for its
 * PROVIDER_ADMIN ({@code GET /provider/staff}). Staff contact data, never a
 * patient's.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A staff member of the caller's provider facility.")
public class ProviderStaffMemberDTO {

    private UUID userId;
    private String username;
    private String firstName;
    private String lastName;
    private String email;

    /** The roles held at this facility, bare (e.g. PHARMACIST), active or not. */
    private List<String> roles;

    /** At least one assignment here is active. */
    private boolean active;

    /** An invitation here waits for its holder's code (inactive, a code issued, never confirmed). */
    private boolean invitationPending;

    /** Holds PROVIDER_ADMIN here: administered by the platform, not by a peer. */
    private boolean providerAdmin;

    /** The caller's own account. */
    private boolean self;

    /** After {@code /activate}: where the new invitation code went. */
    private List<NotificationDeliveryStatusDTO> activationDelivery;
}
