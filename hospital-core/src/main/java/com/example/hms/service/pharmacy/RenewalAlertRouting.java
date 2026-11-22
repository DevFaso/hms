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

import java.util.Objects;
import java.util.Optional;
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
 *   <li>when the prescriber is no longer active (the staff record or the account is switched off), to the
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
        Optional<Staff> prescriber = activePrescriberOf(prescription);
        return prescriber.isPresent() ? prescriber : coverOf(prescription);
    }

    /** The prescriber, while their staff record and their account are active: the rule without a cover. */
    public static Optional<Staff> activePrescriberOf(Prescription prescription) {
        Staff prescriber = prescription == null ? null : prescription.getStaff();
        return isActive(prescriber) ? Optional.of(prescriber) : Optional.empty();
    }

    /**
     * Whether {@code staffId} answers this prescription's renewal requests as the designated cover: the
     * prescriber is no longer active and this is the cover {@link #answererOf} names.
     */
    public boolean isCoverOf(UUID staffId, Prescription prescription) {
        if (staffId == null || activePrescriberOf(prescription).isPresent()) {
            return false;
        }
        return coverOf(prescription).map(Staff::getId).filter(staffId::equals).isPresent();
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
        UUID hospitalId = prescription == null || prescription.getHospital() == null ? null
            : prescription.getHospital().getId();
        if (head == null || hospitalId == null || Objects.equals(head.getId(), prescriber.getId()) || !isActive(head)) {
            return Optional.empty();
        }
        boolean sameHospital = head.getHospital() != null && hospitalId.equals(head.getHospital().getId());
        // "Not controlled" here: a controlled medicine is never renewed, whoever answers (the renewal refuses it).
        boolean mayRenew = roleValidator.canApproveRenewal(head.getUser().getId(), hospitalId, false);
        return sameHospital && mayRenew ? Optional.of(head) : Optional.empty();
    }

    private static boolean isActive(Staff staff) {
        User user = staff == null ? null : staff.getUser();
        return staff != null && staff.isActive() && user != null && user.isActive() && !user.isDeleted();
    }
}
