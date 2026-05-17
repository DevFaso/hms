package com.example.hms.empi.probabilistic;

import java.util.UUID;

/**
 * Single ranked candidate returned by
 * {@link EmpiProbabilisticMatcher#findCandidates}. Carries the
 * resolved Patient id + the normalized score + the per-field
 * confidence breakdown so the receptionist UI can render "this match
 * is based on name + DOB; sex disagreement was tolerated".
 */
public record EmpiCandidateMatchDTO(
    UUID patientId,
    String displayName,
    double score,
    boolean nameMatched,
    boolean dobMatched,
    boolean sexMatched,
    boolean nationalIdMatched
) {}
