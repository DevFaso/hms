package com.example.hms.service.recordaccess;

import com.example.hms.payload.dto.recordaccess.RecordSharingOptOutDTO;

import java.util.Locale;
import java.util.UUID;

/**
 * E8 #52 — a patient's opt-out from cross-hospital reads. Their own hospital
 * keeps its access; only the treatment presumption across hospitals is
 * withdrawn. Every change emits a {@code CONSENT_UPDATE} audit row naming the
 * patient id only.
 */
public interface RecordSharingOptOutService {

    RecordSharingOptOutDTO status(UUID patientId, Locale locale);

    /** Idempotent by refusal: opting out twice is a 409, not a second row. */
    RecordSharingOptOutDTO optOut(UUID patientId, String reason, UUID actorUserId, Locale locale);

    /** Stamps {@code revokedAt}; the row stays for the disclosure report. */
    RecordSharingOptOutDTO revoke(UUID patientId, UUID actorUserId, Locale locale);
}
