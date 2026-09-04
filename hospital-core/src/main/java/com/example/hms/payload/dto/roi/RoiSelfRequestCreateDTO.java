package com.example.hms.payload.dto.roi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * The patient's own release-of-information request through the /me surface
 * (Tier 2 item 39b). Deliberately carries NO requester identity: the server
 * forces {@code requesterType=PATIENT} and the patient's own name — a
 * self-service caller cannot file as a third party, so the contract does
 * not ask for fields that would be overwritten.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoiSelfRequestCreateDTO {

    /** Where the copy should go — optional; defaults to the contact on file. */
    @Size(max = 500)
    private String requesterContact;

    @NotBlank(message = "A purpose is required — it is what the decision weighs.")
    @Size(max = 500)
    private String purpose;

    @NotBlank(message = "The requested scope is required (e.g. \"full record\", \"labs 2025\").")
    @Size(max = 500)
    private String scopeDescription;

    /** Optional — defaults to today. */
    private LocalDate requestedOn;
}
