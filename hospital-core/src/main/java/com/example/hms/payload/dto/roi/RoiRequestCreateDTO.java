package com.example.hms.payload.dto.roi;

import com.example.hms.enums.RoiRequesterType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Log a release-of-information request received at the desk (Tier 2 item
 * 39b) — the STAFF intake shape. The patient's own /me submission uses
 * {@link RoiSelfRequestCreateDTO}, which carries no requester identity.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoiRequestCreateDTO {

    @NotNull(message = "requesterType is required")
    private RoiRequesterType requesterType;

    /**
     * The requester's name. Required when {@code requesterType} is
     * THIRD_PARTY (enforced in the service — a release to an unnamed
     * outside party cannot be accounted for); optional when the patient
     * asks in person.
     */
    @Size(max = 200)
    private String requesterName;

    /** Where the copy goes / how to reach the requester. */
    @Size(max = 500)
    private String requesterContact;

    @NotBlank(message = "A purpose is required — it is what the decision weighs.")
    @Size(max = 500)
    private String purpose;

    @NotBlank(message = "The requested scope is required (e.g. \"full record\", \"labs 2025\").")
    @Size(max = 500)
    private String scopeDescription;

    /** Optional — defaults to today; may predate the row for paper requests. */
    private LocalDate requestedOn;
}
