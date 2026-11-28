package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.EmergencyReleaseType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A documented emergency release. Who issues it is the caller; who answers for it is named here. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmergencyReleaseRequestDTO {

    @NotNull
    private UUID bloodUnitId;

    /** O_POSITIVE_DECISION or SEROLOGICALLY_INCOMPATIBLE. An uncrossmatched release documents nothing. */
    @NotNull
    private EmergencyReleaseType releaseType;

    /** Required, named at once with the other fields; the service also bounds its length. */
    @jakarta.validation.constraints.NotBlank(message = "{transfusion.emergency.reasonRequired}")
    private String reason;

    /**
     * The person making the decision, picked from the options the server offers. Required, except for O positive
     * with {@link #physicianUnreachable}: the service names the field when it is missing.
     */
    private UUID authoriserUserId;

    /**
     * The issuer states that no physician can be reached. O positive outside the low-risk tier: the bag goes out
     * with the authority recorded as owed and the laboratory director is alerted at once. A serologically
     * incompatible release (V381): accepted only where the hospital switched the fallback on, with
     * {@link #unreachableReason}; the least incompatible bag goes out and a physician countersigns.
     */
    private Boolean physicianUnreachable;

    /** V381, the fallback of an incompatible release: who was called and when. Required then. */
    private String unreachableReason;

    /** V381, every incompatible release: why this bag was chosen. */
    private String unitChoiceReason;

    /** V381, every incompatible release: whether the patient or the family was told of the risk. */
    private com.example.hms.enums.PatientInformedStatus patientInformation;

    /**
     * O positive on the order of a physician: the order was given verbally (the default) or is written on the
     * request. The physician countersigns either way.
     */
    private Boolean verbalOrder;

    /** O positive for a patient with a known antibody: the biologist deciding with the physician, when named. */
    private UUID biologistUserId;

    /**
     * A serologically incompatible release of an O positive bag: the physician who ordered O positive for this
     * patient, when that is not the person authorising the incompatible release ({@link #authoriserUserId}).
     * Two roles, two names: each is recorded where it belongs.
     */
    private UUID orderingPhysicianUserId;

    /**
     * For O positive when the system shows O negative in stock: the person states it cannot be
     * obtained in time. Required then, on its field; ignored otherwise.
     */
    private Boolean negativeUnobtainable;

    /**
     * QA D3906: the deliberate « ajouter une unité » step, for a bag beyond the units the request asked for.
     * Without it such a bag is refused on this field; a bag within the request never needs it.
     */
    private Boolean additionalUnit;

    /**
     * A serologically incompatible release names who authorises it, or states that no physician can be reached
     * (V381: the decision service then says whether the hospital allows it). An O positive decision may name
     * nobody (V380: the issuer under the protocol, or no physician reachable).
     */
    @jakarta.validation.constraints.AssertTrue(message = "{transfusion.emergency.authoriserRequired}")
    @com.example.hms.validation.ReportedOn("authoriserUserId")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isAuthoriserNamedWhenIncompatible() {
        return releaseType != EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE || authoriserUserId != null
            || Boolean.TRUE.equals(physicianUnreachable);
    }

    /** V381: an incompatible release says why this bag: named at the edge with the other fields. */
    @jakarta.validation.constraints.AssertTrue(message = "{transfusion.incompatible.unitChoiceReasonRequired}")
    @com.example.hms.validation.ReportedOn("unitChoiceReason")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isUnitChoiceReasonGivenWhenIncompatible() {
        return releaseType != EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE
            || (unitChoiceReason != null && !unitChoiceReason.isBlank());
    }

    /** V381: an incompatible release says whether the patient was told: named at the edge with the other fields. */
    @jakarta.validation.constraints.AssertTrue(message = "{transfusion.incompatible.patientInformedRequired}")
    @com.example.hms.validation.ReportedOn("patientInformation")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPatientInformationStatedWhenIncompatible() {
        return releaseType != EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE || patientInformation != null;
    }

    /** An uncrossmatched release documents nothing: named at the edge with the other fields. */
    @jakarta.validation.constraints.AssertTrue(message = "{transfusion.emergency.typeNotDocumented}")
    @com.example.hms.validation.ReportedOn("releaseType")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isReleaseTypeDocumented() {
        return releaseType != EmergencyReleaseType.UNCROSSMATCHED;
    }
}
