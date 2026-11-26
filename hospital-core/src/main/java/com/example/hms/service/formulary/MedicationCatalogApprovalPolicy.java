package com.example.hms.service.formulary;

import com.example.hms.enums.MedicationCatalogVersionKind;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogVersion;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.ImpersonationContextHolder;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.lab.CriticalResultRecipients;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Go-live D2 (V385): who decides a version of the medication catalogue. PROVISIONAL, to be signed off by each
 * hospital's pharmacist in charge and médecin-chef (rule set CATALOGUE_CHANGE_APPROVAL).
 *
 * <ul>
 *   <li><b>Four eyes, for everyone</b>: the person who approves is never the person who proposed. No role and no
 *       administrator is exempt, and a support session acting as somebody else decides nothing.</li>
 *   <li><b>A formulary entry</b> (a new medicine, its names, ATC, form, strength, route, controlled flag, or
 *       putting a withdrawn entry back): a pharmacist of the hospital.</li>
 *   <li><b>Dose limits</b>: a pharmacist of the hospital, or the doctor the hospital names médecin-chef.</li>
 *   <li><b>A platform entry</b> (the national list, no hospital): a platform administrator, as for a platform
 *       laboratory definition.</li>
 * </ul>
 *
 * <p>The role is held AT the version's hospital: a pharmacist of hospital B approves nothing of hospital A, and a
 * platform administrator who is not a pharmacist there approves nothing of a hospital. There is no dedicated
 * « pharmacien responsable » role code: the pharmacist role is the one the hospital gives its pharmacists.
 *
 * <p>An entry no second person has approved keeps working exactly as before; the approval changes what the
 * catalogue says of it, never whether care can go on.
 */
@Component
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.CATALOGUE_CHANGE_APPROVAL)
public class MedicationCatalogApprovalPolicy {

    private static final Set<String> PHARMACIST_ROLES = Set.of("ROLE_PHARMACIST");
    private static final Set<String> DOCTOR_ROLES = Set.copyOf(CriticalResultRecipients.DOCTOR_ROLES);

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final HospitalRepository hospitalRepository;
    private final RoleValidator roleValidator;

    /** Whether {@code userId} holds a role that decides this version, whoever proposed it. */
    public boolean holdsDecidingRole(MedicationCatalogVersion version, UUID userId) {
        if (userId == null || ImpersonationContextHolder.isImpersonating()) {
            return false;
        }
        UUID hospitalId = version.getHospitalId();
        if (hospitalId == null) {
            return roleValidator.isSuperAdminFromJwtClaim();
        }
        if (assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(userId, hospitalId, PHARMACIST_ROLES)) {
            return true;
        }
        return version.getKind() == MedicationCatalogVersionKind.DOSE_LIMIT && isMedicalLead(hospitalId, userId);
    }

    /** Whether {@code userId} may approve this version: the role, and not their own proposal. */
    public boolean mayApprove(MedicationCatalogVersion version, UUID userId) {
        return holdsDecidingRole(version, userId) && !userId.equals(version.getProposedBy());
    }

    /** Refuses an approval by the wrong role, then by the author. */
    public void requireMayApprove(MedicationCatalogVersion version, UUID userId) {
        requireDecidingRole(version, userId);
        if (userId.equals(version.getProposedBy())) {
            throw new BusinessException("medication.catalog.version.fourEyes");
        }
    }

    /** Refuses a refusal by the wrong role. A refusal puts nothing in force, so the author's own is allowed. */
    public void requireDecidingRole(MedicationCatalogVersion version, UUID userId) {
        if (!holdsDecidingRole(version, userId)) {
            throw new AccessDeniedException(MessageUtil.resolveOrRaw(refusalKey(version)));
        }
    }

    private static String refusalKey(MedicationCatalogVersion version) {
        if (version.getHospitalId() == null) {
            return "medication.catalog.version.approver.platform";
        }
        return version.getKind() == MedicationCatalogVersionKind.DOSE_LIMIT
            ? "medication.catalog.version.approver.doseLimit"
            : "medication.catalog.version.approver.formulary";
    }

    /** The doctor the hospital names médecin-chef, only while still a doctor there. */
    private boolean isMedicalLead(UUID hospitalId, UUID userId) {
        UUID lead = hospitalRepository.findById(hospitalId).map(Hospital::getCriticalMedicalLeadUserId).orElse(null);
        return userId.equals(lead)
            && assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(userId, hospitalId, DOCTOR_ROLES);
    }
}
