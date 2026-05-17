package com.example.hms.empi.probabilistic;

import java.time.LocalDate;

/**
 * Inbound payload for {@code POST /api/empi/candidates} — the draft
 * patient identity the receptionist wants to score against existing
 * Patients (roadmap row 25, v1.1 / Patient Identity).
 *
 * <p>Every field is optional; the scorer weighs whatever is present.
 * The deliverable's matching axis is name + DOB + sex + national-ID;
 * additional discriminators (phone, address) are deferred to the
 * row-25 follow-on.
 */
public record EmpiCandidateQueryDTO(
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    String sex,
    String nationalId
) {}
