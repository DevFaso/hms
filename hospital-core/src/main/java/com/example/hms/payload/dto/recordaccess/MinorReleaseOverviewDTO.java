package com.example.hms.payload.dto.recordaccess;

import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.SensitivityCategory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Policy 2026-11-20 (provisional): what a clinician sees before releasing a
 * minor's sensitive result - the parents and guardians who hold a
 * family-access grant, and the results hidden from them.
 *
 * @param minor              the patient is under 18; false means nothing can be released
 * @param adolescentAge      the hospital's adolescent confidentiality age
 * @param adolescentAgeIsDefault the hospital set none: the provisional default applies
 * @param guardians          the active parent and guardian grants
 * @param results            the released lab results a proxy does not read
 */
public record MinorReleaseOverviewDTO(
    UUID patientId,
    boolean minor,
    int adolescentAge,
    boolean adolescentAgeIsDefault,
    List<Guardian> guardians,
    List<HiddenResult> results
) {

    /** One parent's or guardian's grant. {@code verified}: staff checked the relationship. */
    public record Guardian(UUID grantId, String holderName, ProxyRelationship relationship, boolean verified,
                           LocalDateTime verifiedAt) {
    }

    /** One result hidden from the family, with the grants it was released to. */
    public record HiddenResult(UUID labOrderId, String testName, SensitivityCategory category,
                               LocalDateTime resultedAt, List<ProxySensitiveReleaseDTO> releases) {
    }
}
