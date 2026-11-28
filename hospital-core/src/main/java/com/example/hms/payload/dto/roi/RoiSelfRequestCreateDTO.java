package com.example.hms.payload.dto.roi;

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

    @Builder.Default
    private com.example.hms.enums.RoiRequestKind requestKind = com.example.hms.enums.RoiRequestKind.COPY;

    /**
     * QA D4025: this body carries no bean constraint on purpose. Every rule (the hospital when the patient must
     * choose one, the purpose, the description, each text at most {@link #TEXT_MAX} characters) is checked by
     * {@code PatientPortalServiceImpl#createRoiRequest} in one pass, so an empty request is refused once with
     * every missing field named. Bean validation runs first and could not name the hospital, which depends on
     * the patient's registrations.
     */
    public static final int TEXT_MAX = 500;

    /** Where the copy should go — optional; defaults to the contact on file. */
    private String requesterContact;

    /** Required (checked by the service, see {@link #TEXT_MAX}). */
    private String purpose;

    /** Required: what is asked for, or for a correction, what to correct (checked by the service). */
    private String scopeDescription;

    /** Optional — defaults to today. */
    private LocalDate requestedOn;

    /**
     * Optional: the hospital the request is for, one of the patient's
     * registrations. Omitted, it is filed at their only (or own) hospital.
     */
    private java.util.UUID hospitalId;
}
