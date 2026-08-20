package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Privacy-minimal projection of an existing patient surfaced at registration
 * time when the typed email/phone exactly matches a record from ANY hospital
 * ({@code GET /patients/registration-match}).
 * <p>
 * Deliberately excludes address and every clinical field: the receptionist only
 * needs enough to confirm identity with the patient standing at the desk and
 * then LINK the record via {@code POST /registrations} instead of creating a
 * duplicate. Phone and email are masked; the full values stay undisclosed
 * until the patient is actually linked to the caller's hospital.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistrationMatchDTO {

    private UUID patientId;
    private String fullName;
    /** Birth year only — enough to disambiguate homonyms without disclosing full DOB. */
    private Integer birthYear;
    private String gender;
    private String maskedPhone;
    private String maskedEmail;
    /** Number of hospitals holding an active registration for this patient. */
    private int hospitalCount;
    /** True when the patient already has an active registration at the caller's hospital. */
    private boolean alreadyRegisteredHere;
    /** Which identifier produced the match: PHONE or EMAIL. */
    private String matchedOn;
}
