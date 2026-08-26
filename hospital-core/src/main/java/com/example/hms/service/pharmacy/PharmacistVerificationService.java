package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The pharmacist-verification step between prescriber and nurse (Tier 2
 * item 33).
 *
 * <p><b>Scoped, not universal, and that was a decision rather than a
 * shortcut.</b> The gate blocks administration only for controlled
 * substances and prescriptions already flagged {@code requiresCosign};
 * everything else stays administrable exactly as before and merely carries
 * an advisory marker.
 *
 * <p>The reason is in the schema. A universal gate depends on a hospital
 * dispensary pharmacist being reachable when a dose falls due, and nothing
 * here models dispensary staffing or cover — so it could never fail open,
 * and with one dispensary it would block every night-time dose until
 * somebody came in. A blocking human step in front of every inpatient
 * medication is not affordable in this deployment; in front of morphine it
 * is.
 *
 * <p>Not to be confused with pharmacies <i>de garde</i> — the rotating
 * night and weekend duty roster Burkinabè community pharmacies run. That is
 * a real concept and the hospital knows who is on duty, but those are
 * PARTNER_PHARMACY / COMMUNITY_PHARMACY serving the public; they do not
 * verify an inpatient's MAR, so they do not make a universal gate
 * workable.
 *
 * <p><b>Verification does not survive an edit.</b> {@code
 * updatePrescription} has no status guard, so a SIGNED prescription's
 * medication name, dosage and frequency are all still mutable. A stamp that
 * outlived a change would assert that a pharmacist checked a drug and dose
 * they never saw — a false assurance, which is worse than the absent one we
 * started with. {@link #invalidateOnChange} is therefore called on every
 * update, and re-verifying is cheap.
 *
 * <p>One owner for the rule, in the shape of {@code
 * ControlledSubstanceGuard}: callers ask this class whether a prescription
 * needs verification rather than re-deriving the condition, so widening the
 * scope later is one edit rather than a search.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PharmacistVerificationService {

    private final PrescriptionRepository prescriptionRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final Clock clock;

    /**
     * Whether this prescription may not be administered until a pharmacist
     * has verified it.
     *
     * <p>Reuses the two existing safety flags rather than adding a third
     * way to say "this one is dangerous". Both are set-only and cannot be
     * withdrawn (#463), so a prescription cannot be quietly demoted out of
     * the gate once it is in.
     */
    public boolean requiresVerification(Prescription prescription) {
        return prescription != null
            && (prescription.isControlledSubstance() || prescription.isRequiresCosign());
    }

    /** Whether a pharmacist has verified the CURRENT version of this prescription. */
    public boolean isVerified(Prescription prescription) {
        return prescription != null && prescription.getPharmacistVerifiedAt() != null;
    }

    /**
     * Whether administration must be refused: the prescription is in scope
     * for the gate and nobody has verified it.
     */
    public boolean blocksAdministration(Prescription prescription) {
        return requiresVerification(prescription) && !isVerified(prescription);
    }

    /**
     * Clear any existing verification. Called whenever a prescription is
     * updated — see the class javadoc for why this is not optional.
     *
     * <p>Does not save: the caller is already persisting the prescription it
     * just mutated, and a second write here would be a redundant flush in a
     * hot path.
     */
    public void invalidateOnChange(Prescription prescription) {
        if (prescription == null || prescription.getPharmacistVerifiedAt() == null) {
            return;
        }
        log.info("Pharmacist verification cleared on prescription {} because it was edited "
            + "after verification; it must be re-verified before the next dose.",
            prescription.getId());
        prescription.setPharmacistVerifiedAt(null);
        prescription.setPharmacistVerifiedBy(null);
        prescription.setPharmacistVerificationNote(null);
    }

    /**
     * The verification ceremony. Server identity, server clock — a client
     * cannot assert who verified or when, the same stance the signing and
     * co-signing ceremonies take.
     */
    @Transactional
    public Prescription verify(UUID prescriptionId, String note) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
            .orElseThrow(() -> new ResourceNotFoundException("prescription.notfound"));

        // 404-not-403: another hospital's prescription is indistinguishable
        // from one that does not exist.
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null
                && prescription.getHospital() != null
                && !hospitalId.equals(prescription.getHospital().getId())) {
            throw new ResourceNotFoundException("prescription.notfound");
        }

        // Verifying a draft would be verifying something the prescriber can
        // still freely rewrite, and the invalidation rule would clear it the
        // moment they did.
        PrescriptionStatus status = prescription.getStatus();
        if (status != PrescriptionStatus.SIGNED && status != PrescriptionStatus.TRANSMITTED) {
            throw new BusinessException(
                "Only a signed prescription can be verified; this one is " + status + ".");
        }

        if (isVerified(prescription)) {
            throw new BusinessException("This prescription has already been verified.");
        }

        UUID currentUserId = roleValidator.getCurrentUserId();
        if (currentUserId == null) {
            throw new AccessDeniedException("Unable to determine the verifying pharmacist.");
        }
        User verifier = userRepository.findById(currentUserId)
            .orElseThrow(() -> new AccessDeniedException("Unable to determine the verifying pharmacist."));

        // The prescriber checking their own work is not a second pair of
        // eyes. Same rule the note co-sign ceremony applies.
        if (prescription.getStaff() != null
                && prescription.getStaff().getUser() != null
                && currentUserId.equals(prescription.getStaff().getUser().getId())) {
            throw new BusinessException(
                "A prescription cannot be verified by the clinician who prescribed it.");
        }

        prescription.setPharmacistVerifiedAt(LocalDateTime.now(clock));
        prescription.setPharmacistVerifiedBy(verifier);
        prescription.setPharmacistVerificationNote(trimToNull(note));
        return prescriptionRepository.save(prescription);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
