package com.example.hms.service.pharmacy;

import com.example.hms.model.Department;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D3301 (r70, High, privacy): who is told of a patient's renewal request, and who answers it.
 *
 * <p>Every refill request rang the bell of every doctor and nurse of the prescriber's department, with the
 * medicine's name: an antiretroviral was named to a nurse who was not the prescriber and could not act on it.
 * A medicine's name is the diagnosis, more often than not. The rule (PROVISIONAL, with the renewal rule it
 * belongs to):
 * <ul>
 *   <li>a renewal request is announced to <b>one</b> person: the prescriber of the prescription;</li>
 *   <li>when the prescriber no longer answers there (the staff record or the account is switched off, or they
 *       may no longer approve a renewal at the prescription's hospital: an assignment deactivated), to the
 *       prescriber's <b>designated cover</b>: the head of the prescriber's department, when that person is active
 *       at the same hospital and may approve a renewal there ({@link RoleValidator#canApproveRenewal}); the cover
 *       then lists and decides the request as the prescriber would. With no such cover nobody is rung: the
 *       request stays on the pharmacy's list and on the patient's, and is never broadcast instead;</li>
 *   <li>the announcement never names the medicine: « Demande de renouvellement pour &lt;patient&gt; », and the
 *       reader opens the request to see what is asked.</li>
 * </ul>
 * The class this closes is held by {@code PrescriptionNotificationPrivacyGuardTest}: a notification, an e-mail or
 * an in-basket item about a prescription names its medicine only to its prescriber, to the dispensing pharmacy
 * and to the patient.
 */
@Component
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.PRESCRIPTION_RENEWAL)
public class RenewalAlertRouting {

    private final RoleValidator roleValidator;

    /** Who is told of a renewal request of this prescription, and answers it; empty when nobody can. */
    public Optional<Staff> answererOf(Prescription prescription) {
        Staff prescriber = prescription == null ? null : prescription.getStaff();
        if (answers(prescriber, hospitalIdOf(prescription))) {
            return Optional.of(prescriber);
        }
        return coverOf(prescription);
    }

    /**
     * Whether this prescriber still answers their own requests at {@code hospitalId}: the staff record and the
     * account are active, AND they may still approve a renewal there. Review of #1290: deactivating an
     * assignment leaves the staff row and the account active, so "active" alone still rang a prescriber who
     * could no longer act, and no cover was looked for.
     */
    public boolean answers(Staff prescriber, UUID hospitalId) {
        return isActive(prescriber) && hospitalId != null
            && roleValidator.canApproveRenewal(prescriber.getUser().getId(), hospitalId, false);
    }

    /**
     * Whether {@code staffId} answers this prescription's renewal requests as the designated cover: the
     * prescriber no longer answers and this is the cover {@link #answererOf} names.
     */
    public boolean isCoverOf(UUID staffId, Prescription prescription) {
        Staff prescriber = prescription == null ? null : prescription.getStaff();
        if (staffId == null || answers(prescriber, hospitalIdOf(prescription))) {
            return false;
        }
        return coverOf(prescription).map(Staff::getId).filter(staffId::equals).isPresent();
    }

    /**
     * Of the members of the departments {@code cover} heads at {@code hospitalId}, the prescribers who no longer
     * answer there and whose requests the cover therefore lists: the same rule as {@link #isCoverOf}, asked for
     * a list. Empty when the cover is not active at that hospital or may not approve a renewal there.
     */
    public Set<UUID> noLongerAnswering(Staff cover, Collection<Staff> membersOfHeadedDepartments, UUID hospitalId) {
        if (membersOfHeadedDepartments == null || !coversAt(cover, hospitalId)) {
            return Set.of();
        }
        Set<UUID> covered = new LinkedHashSet<>();
        for (Staff member : membersOfHeadedDepartments) {
            boolean headedByCover = member != null && member.getDepartment() != null
                && member.getDepartment().getHeadOfDepartment() != null
                && Objects.equals(member.getDepartment().getHeadOfDepartment().getId(), cover.getId());
            if (headedByCover && !Objects.equals(member.getId(), cover.getId()) && !answers(member, hospitalId)) {
                covered.add(member.getId());
            }
        }
        return covered;
    }

    /** Whether the reader of a renewal announcement is the prescriber himself (the cover is not). */
    public static boolean isPrescriber(Staff reader, Prescription prescription) {
        Staff prescriber = prescription == null ? null : prescription.getStaff();
        return reader != null && prescriber != null && Objects.equals(reader.getId(), prescriber.getId());
    }

    private Optional<Staff> coverOf(Prescription prescription) {
        Staff prescriber = prescription == null ? null : prescription.getStaff();
        Department department = prescriber == null ? null : prescriber.getDepartment();
        Staff head = department == null ? null : department.getHeadOfDepartment();
        UUID hospitalId = hospitalIdOf(prescription);
        if (head == null || Objects.equals(head.getId(), prescriber.getId()) || !coversAt(head, hospitalId)) {
            return Optional.empty();
        }
        return Optional.of(head);
    }

    /** A cover is active at that very hospital and may approve a renewal there. */
    private boolean coversAt(Staff head, UUID hospitalId) {
        if (!isActive(head) || hospitalId == null) {
            return false;
        }
        boolean sameHospital = head.getHospital() != null && hospitalId.equals(head.getHospital().getId());
        // "Not controlled" here: a controlled medicine is never renewed, whoever answers (the renewal refuses it).
        return sameHospital && roleValidator.canApproveRenewal(head.getUser().getId(), hospitalId, false);
    }

    private static UUID hospitalIdOf(Prescription prescription) {
        return prescription == null || prescription.getHospital() == null ? null
            : prescription.getHospital().getId();
    }

    private static boolean isActive(Staff staff) {
        User user = staff == null ? null : staff.getUser();
        return staff != null && staff.isActive() && user != null && user.isActive() && !user.isDeleted();
    }
}
