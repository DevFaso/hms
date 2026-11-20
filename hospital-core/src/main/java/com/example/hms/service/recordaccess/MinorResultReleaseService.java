package com.example.hms.service.recordaccess;

import com.example.hms.payload.dto.recordaccess.MinorReleaseOverviewDTO;

import java.util.UUID;

/**
 * Policy 2026-11-20 (provisional): a clinician releases a minor's sensitive
 * result to a verified parent or guardian (Loi 030-2008, art. 17 and 18).
 */
public interface MinorResultReleaseService {

    /** The parents' grants and the hidden results of a patient, at the acting hospital. */
    MinorReleaseOverviewDTO overview(UUID patientId, UUID actorUserId);

    /** Staff checked that the holder of this grant is the patient's parent or legal guardian. */
    MinorReleaseOverviewDTO verifyGuardian(UUID patientId, UUID grantId, UUID actorUserId);

    /** Release one result (a lab order) to one verified parent's or guardian's grant, with a reason. */
    MinorReleaseOverviewDTO release(UUID patientId, UUID labOrderId, UUID grantId, String reason,
                                    boolean relationshipVerified, UUID actorUserId);
}
