package com.example.hms.service.imaging;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * QA D4140 review (provisional rule IMAGING_ORDER_PHYSICIAN_AUTHORISATION): a request a nurse or a midwife
 * placed waits for a physician, and the physicians are told, so it never waits unseen.
 *
 * <p>The task goes to the physicians of the requester's own department at the order's hospital; with none
 * there (or no department known), to every physician of the hospital. A STAT or urgent request is filed as
 * urgent. The task is filed after commit in its own transaction, and it is removed when the request is
 * authorised, declined or withdrawn.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ProvisionalRule(ClinicalRuleSetKey.IMAGING_ORDER_PHYSICIAN_AUTHORISATION)
public class ImagingAuthorisationRequestFollowUp {

    /** The in-basket reference type of the task; its reference id is the order's id. */
    public static final String REFERENCE_TYPE = "IMAGING_AWAITING_PHYSICIAN";

    /** The roles whose signature an imaging prescription carries (RoleValidator#isSigningPhysician). */
    static final Set<String> PHYSICIAN_ROLE_CODES = Set.of(SecurityConstants.ROLE_DOCTOR, "ROLE_PHYSICIAN",
        SecurityConstants.ROLE_SURGEON);

    static final String TITLE_KEY = "imaging.order.awaitingPhysician.taskTitle";
    static final String URGENT_TITLE_KEY = "imaging.order.awaitingPhysician.taskTitleUrgent";
    static final String BODY_KEY = "imaging.order.awaitingPhysician.taskBody";

    private final InBasketService inBasketService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final StaffRepository staffRepository;

    /** Files the task for a request that now waits for a physician. */
    public void requestPlaced(ImagingOrder order) {
        if (order == null || order.getId() == null || order.getHospital() == null) {
            return;
        }
        UUID orderId = order.getId();
        UUID hospitalId = order.getHospital().getId();
        UUID requesterId = order.getRequestedByUserId();
        Patient patient = order.getPatient();
        UUID patientId = patient != null ? patient.getId() : null;
        String patientName = patient != null ? patient.getFullName() : null;
        String study = order.getStudyType() != null && !order.getStudyType().isBlank()
            ? order.getStudyType()
            : order.getModality() != null ? order.getModality().name() : "";
        String requester = order.getRequestedByName() != null ? order.getRequestedByName() : "";
        ImagingOrderPriority priority = order.getPriority();
        UUID encounterId = order.getEncounterId();
        boolean urgent = priority == ImagingOrderPriority.STAT || priority == ImagingOrderPriority.URGENT;
        TransactionCallbacks.afterCommit(() -> {
            try {
                Set<UUID> physicians = physiciansFor(hospitalId, requesterId);
                if (physicians.isEmpty()) {
                    log.warn("Imaging request {} waits for a physician and hospital {} has none to tell", orderId,
                        hospitalId);
                    return;
                }
                Object[] bodyArgs = {study, patientName, requester};
                for (UUID physician : physicians) {
                    CreateInBasketItemRequest item = new CreateInBasketItemRequest();
                    item.setRecipientUserId(physician);
                    item.setHospitalId(hospitalId);
                    item.setItemType(InBasketItemType.TASK);
                    item.setPriority(urgent ? InBasketPriority.URGENT : InBasketPriority.NORMAL);
                    item.setReferenceId(orderId);
                    item.setReferenceType(REFERENCE_TYPE);
                    item.setPatientId(patientId);
                    item.setPatientName(patientName);
                    item.setEncounterId(encounterId);
                    item.setTitleKey(urgent ? URGENT_TITLE_KEY : TITLE_KEY);
                    item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, urgent ? URGENT_TITLE_KEY : TITLE_KEY));
                    item.setBodyKey(BODY_KEY);
                    item.setBodyArgs(bodyArgs);
                    item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, BODY_KEY, bodyArgs));
                    inBasketService.createItemInNewTransaction(item);
                }
            } catch (RuntimeException ex) {
                log.warn("Awaiting-physician task not filed for imaging request {}: {}", orderId,
                    ex.getClass().getSimpleName());
            }
        });
    }

    /** The request was authorised, declined or withdrawn: nothing waits any more. */
    public void requestSettled(ImagingOrder order) {
        if (order == null || order.getId() == null) {
            return;
        }
        try {
            inBasketService.removeOpenItemsForReference(order.getId(), REFERENCE_TYPE);
        } catch (RuntimeException ex) {
            log.warn("Awaiting-physician task not closed for imaging request {}: {}", order.getId(),
                ex.getClass().getSimpleName());
        }
    }

    /** The physicians of the requester's department here, else every physician of the hospital. */
    Set<UUID> physiciansFor(UUID hospitalId, UUID requesterId) {
        Set<UUID> found = new LinkedHashSet<>();
        if (requesterId != null) {
            UUID departmentId = staffRepository.findByUserIdAndHospitalId(requesterId, hospitalId)
                .map(Staff::getDepartment).map(department -> department.getId()).orElse(null);
            if (departmentId != null) {
                for (Staff staff : staffRepository.findActiveProvidersByHospitalAndDepartment(hospitalId, departmentId)) {
                    User user = staff.getUser();
                    if (user != null && user.isActive() && staff.getAssignment() != null
                            && staff.getAssignment().getRole() != null
                            && staff.getAssignment().getRole().getCode() != null
                            && PHYSICIAN_ROLE_CODES.contains(
                                staff.getAssignment().getRole().getCode().toUpperCase(Locale.ROOT))) {
                        found.add(user.getId());
                    }
                }
            }
        }
        if (found.isEmpty()) {
            List<User> everyPhysician =
                assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, PHYSICIAN_ROLE_CODES);
            everyPhysician.forEach(user -> found.add(user.getId()));
        }
        found.remove(requesterId);
        return found;
    }
}
