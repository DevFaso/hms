package com.example.hms.service.maternity;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.WardType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Bed;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.labor.DeliveryNewborn;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.LaborEpisode;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.BedRepository;
import com.example.hms.repository.DeliveryNewbornRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.BedAssignmentService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Policy rule 2 (owner 2026-11-20, PROVISIONAL until a named sage-femme or
 * obstetrician signs it off): what a midwife does for the newborn and in the
 * maternity wards. Source: the 2010 national reproductive-health norms
 * (sage-femme authorised for the healthy newborn at every level).
 *
 * <ul>
 *   <li>She admits a newborn registered from a delivery she attended at this
 *       hospital (she delivered or documented it, or opened or documented its
 *       labour episode), as its admitting provider.</li>
 *   <li>She assigns and releases beds and cots of MATERNITY wards (the model
 *       has no separate postnatal ward type). A NICU cot, for a newborn stay
 *       only, is assigned too and a physician is asked to review: a task,
 *       never a refusal. Every other ward stays with nurses and physicians.</li>
 * </ul>
 * A caller who is also a nurse or a physician at the hospital keeps that
 * role's reach; a nurse gains nothing here. The midwife role counts at the
 * hospital it is held at: held elsewhere only, it assigns no bed here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MidwifeNewbornCare {

    /** The in-basket reference type of the physician's task; its reference id is the admission's id. */
    public static final String REFERENCE_TYPE = "NEWBORN_NEONATOLOGY";

    /** Who reviews a newborn a midwife placed in neonatology: the hospital's physicians. */
    static final Set<String> PHYSICIAN_ROLE_CODES = Set.of(SecurityConstants.ROLE_DOCTOR);

    static final String TITLE_KEY = "newborn.neonatology.doctorTask.title";
    static final String BODY_KEY = "newborn.neonatology.doctorTask.body";
    static final String NOT_HER_NEWBORN_KEY = "admission.midwife.newbornNotHers";
    static final String WARD_KEY = "admission.bed.midwifeWards";
    /** {@code AdmissionController#assignBed} and {@code #unassignBed} before policy rule 2. */
    private static final Set<String> BED_ROLES_BEFORE_THIS_RULE = Set.of(
        SecurityConstants.ROLE_DOCTOR, SecurityConstants.ROLE_NURSE, SecurityConstants.ROLE_SUPER_ADMIN);

    private final DeliveryNewbornRepository deliveryNewbornRepository;
    private final BedRepository bedRepository;
    private final RoleValidator roleValidator;
    private final InBasketService inBasketService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /**
     * The baby was registered from a delivery at this hospital that the midwife attended; any
     * other patient is refused on {@code patientId} (the admission is a physician's).
     */
    public void requireNewbornOfHerDelivery(Patient baby, UUID hospitalId, UUID midwifeUserId) {
        DeliveryNewborn link = baby == null || baby.getId() == null ? null
            : deliveryNewbornRepository.findByNewbornPatient_Id(baby.getId()).orElse(null);
        boolean here = link != null && link.getHospital() != null && hospitalId != null
            && hospitalId.equals(link.getHospital().getId());
        if (!here || !attended(link.getDeliveryRecord(), midwifeUserId)) {
            throw new FieldBusinessException("patientId", NOT_HER_NEWBORN_KEY);
        }
    }

    /** She delivered or documented the delivery, or opened or documented its labour episode. */
    static boolean attended(DeliveryRecord delivery, UUID userId) {
        if (delivery == null || userId == null) {
            return false;
        }
        LaborEpisode episode = delivery.getEpisode();
        return java.util.stream.Stream.of(
                userIdOf(delivery.getDeliveredByStaff()),
                idOf(delivery.getDocumentedBy()),
                episode == null ? null : userIdOf(episode.getAdmittedByStaff()),
                episode == null ? null : idOf(episode.getDocumentedBy()))
            .anyMatch(userId::equals);
    }

    /**
     * The bed a caller who is only a midwife at the stay's hospital may assign: one of a
     * MATERNITY ward, or a NICU cot for a newborn stay. Anyone else passes untouched.
     *
     * @return true when a physician must be asked to review (a NICU cot assigned by a midwife)
     */
    public boolean requireBedAMidwifeMayAssign(Admission admission, UUID bedId) {
        UUID hospitalId = hospitalIdOf(admission);
        if (hospitalId == null || bedId == null
                || !narrowedToHerWards(hospitalId, BedAssignmentService.FIELD_BED_ID)) {
            return false;
        }
        // Assigning a bed frees the one the stay holds (BedAssignmentService): that bed must be
        // hers to free too, or a move into maternity would release a bed of another ward.
        Bed held = admission.getBed();
        if (held != null && !bedId.equals(held.getId())) {
            requireHerWard(held, admission, BedAssignmentService.FIELD_BED_ID);
        }
        // A bed of another hospital answers as not found in the assignment itself.
        Bed bed = bedRepository.findByIdAndWard_Hospital_Id(bedId, hospitalId).orElse(null);
        return bed != null && requireHerWard(bed, admission, BedAssignmentService.FIELD_BED_ID);
    }

    /** The same wards, for the bed a caller who is only a midwife releases. */
    public void requireBedAMidwifeMayRelease(Admission admission) {
        UUID hospitalId = hospitalIdOf(admission);
        if (hospitalId == null || admission.getBed() == null || !narrowedToHerWards(hospitalId, null)) {
            return;
        }
        requireHerWard(admission.getBed(), admission, null);
    }

    /**
     * Whether the ward rule applies to the caller at this hospital.
     * <ul>
     *   <li>A nurse or a physician THERE keeps every ward: false.</li>
     *   <li>A midwife there (and nothing wider there) is narrowed: true, whatever role she holds
     *       at another hospital.</li>
     *   <li>Neither: a caller the endpoint admitted before this rule (a token carrying NURSE,
     *       DOCTOR or SUPER_ADMIN) is left as before: false. A caller it admits only as a midwife,
     *       on a role held at another hospital, is refused: that role counts for nothing here.</li>
     * </ul>
     */
    private boolean narrowedToHerWards(UUID hospitalId, String field) {
        UUID caller = roleValidator.getCurrentUserId();
        if (caller != null && nurseOrPhysicianAt(caller, hospitalId)) {
            return false;
        }
        if (caller != null && roleValidator.isMidwife(caller, hospitalId)) {
            return true;
        }
        if (admittedBeforeThisRule()) {
            return false;
        }
        throw field == null ? new BusinessException(WARD_KEY) : new FieldBusinessException(field, WARD_KEY);
    }

    private boolean nurseOrPhysicianAt(UUID caller, UUID hospitalId) {
        return roleValidator.isNurse(caller, hospitalId) || roleValidator.isDoctor(caller, hospitalId)
            || roleValidator.isPhysician(caller, hospitalId) || roleValidator.isSurgeon(caller, hospitalId);
    }

    /** The token carries a role the bed endpoints admitted before the midwife joined them. */
    private static boolean admittedBeforeThisRule() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .anyMatch(BED_ROLES_BEFORE_THIS_RULE::contains);
    }

    private static boolean requireHerWard(Bed bed, Admission admission, String field) {
        WardType type = bed.getWard() == null ? null : bed.getWard().getWardType();
        if (type == WardType.MATERNITY) {
            return false;
        }
        if (type == WardType.NICU && admission.getAdmissionType() == AdmissionType.NEWBORN) {
            return true;
        }
        throw field == null ? new BusinessException(WARD_KEY) : new FieldBusinessException(field, WARD_KEY);
    }

    /**
     * Asks the hospital's physicians to review a newborn a midwife placed in a NICU cot. Filed
     * after the commit, each item in its own transaction: a failure to file never undoes the bed.
     */
    public void askPhysicianAfterCommit(Admission admission) {
        UUID admissionId = admission == null ? null : admission.getId();
        UUID hospitalId = hospitalIdOf(admission);
        if (admissionId == null || hospitalId == null) {
            return;
        }
        Patient baby = admission.getPatient();
        UUID patientId = baby == null ? null : baby.getId();
        String patientName = baby == null ? null : baby.getFullName();
        String bed = admission.getBed() == null ? "" : BedAssignmentService.bedLabel(admission.getBed());
        TransactionCallbacks.afterCommit(() -> {
            try {
                List<User> physicians =
                    assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, PHYSICIAN_ROLE_CODES);
                if (physicians.isEmpty()) {
                    log.warn("Newborn admission {} is in a NICU cot and hospital {} has no physician to ask",
                        admissionId, hospitalId);
                    return;
                }
                Object[] bodyArgs = {patientName, bed};
                for (User physician : physicians) {
                    CreateInBasketItemRequest item = new CreateInBasketItemRequest();
                    item.setRecipientUserId(physician.getId());
                    item.setHospitalId(hospitalId);
                    item.setItemType(InBasketItemType.TASK);
                    item.setPriority(InBasketPriority.URGENT);
                    item.setReferenceId(admissionId);
                    item.setReferenceType(REFERENCE_TYPE);
                    item.setPatientId(patientId);
                    item.setPatientName(patientName);
                    item.setTitleKey(TITLE_KEY);
                    item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, TITLE_KEY));
                    item.setBodyKey(BODY_KEY);
                    item.setBodyArgs(bodyArgs);
                    item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, BODY_KEY, bodyArgs));
                    inBasketService.createItemInNewTransaction(item);
                }
            } catch (RuntimeException ex) {
                log.warn("Physician review not filed for newborn admission {}: {}", admissionId,
                    ex.getClass().getSimpleName());
            }
        });
    }

    private static UUID hospitalIdOf(Admission admission) {
        return admission == null || admission.getHospital() == null ? null : admission.getHospital().getId();
    }

    private static UUID userIdOf(Staff staff) {
        return staff == null ? null : idOf(staff.getUser());
    }

    private static UUID idOf(User user) {
        return user == null ? null : user.getId();
    }
}
