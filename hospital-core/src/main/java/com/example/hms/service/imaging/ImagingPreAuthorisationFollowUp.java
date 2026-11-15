package com.example.hms.service.imaging;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.support.ImagingPreAuthorisationPolicy;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Provisional rule 1 (2026-11-15): the billing follow-up of an urgent study
 * done without its pre-authorisation number. {@link ImagingPreAuthorisationPolicy}
 * decides; this files the task when an order becomes pending and closes it
 * when the number is recorded.
 *
 * <p>The task goes to the billing staff of the order's hospital, who obtain
 * the authorisation from the insurer afterwards. It is filed after commit, in
 * its own transaction: a failure to file never undoes the start of an urgent
 * study, and the flag on the order stays visible either way.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImagingPreAuthorisationFollowUp {

    /** The in-basket reference type of the task; its reference id is the order's id. */
    public static final String REFERENCE_TYPE = "IMAGING_PREAUTH";

    /** Who obtains a pre-authorisation from the insurer: the hospital's billing staff. */
    static final Set<String> BILLING_ROLE_CODES = Set.of(SecurityConstants.ROLE_BILLING_SPECIALIST);

    static final String TITLE_KEY = "imaging.preauth.followUp.title";
    static final String BODY_KEY = "imaging.preauth.followUp.body";

    private final InBasketService inBasketService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /** Files or closes the task, as the policy's reconcile said. */
    public void apply(ImagingOrder order, ImagingPreAuthorisationPolicy.FollowUp followUp) {
        if (order == null || order.getId() == null || followUp == null) {
            return;
        }
        switch (followUp) {
            case OPENED -> fileAfterCommit(order);
            case CLOSED -> inBasketService.removeAllItemsForReference(order.getId(), REFERENCE_TYPE);
            default -> {
                // Nothing to file or close.
            }
        }
    }

    private void fileAfterCommit(ImagingOrder order) {
        UUID orderId = order.getId();
        UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
        Patient patient = order.getPatient();
        UUID patientId = patient != null ? patient.getId() : null;
        String patientName = patient != null ? patient.getFullName() : null;
        String study = order.getStudyType() != null && !order.getStudyType().isBlank()
            ? order.getStudyType()
            : order.getModality() != null ? order.getModality().name() : "";
        UUID encounterId = order.getEncounterId();
        if (hospitalId == null) {
            return;
        }
        TransactionCallbacks.afterCommit(() -> {
            try {
                List<com.example.hms.model.User> billing =
                    assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, BILLING_ROLE_CODES);
                if (billing.isEmpty()) {
                    log.warn("Imaging order {} awaits its pre-authorisation and hospital {} has no billing staff "
                        + "to file the follow-up for; the order keeps its flag", orderId, hospitalId);
                    return;
                }
                Object[] bodyArgs = {study, patientName};
                for (com.example.hms.model.User user : billing) {
                    CreateInBasketItemRequest item = new CreateInBasketItemRequest();
                    item.setRecipientUserId(user.getId());
                    item.setHospitalId(hospitalId);
                    item.setItemType(InBasketItemType.TASK);
                    item.setPriority(InBasketPriority.NORMAL);
                    item.setReferenceId(orderId);
                    item.setReferenceType(REFERENCE_TYPE);
                    item.setPatientId(patientId);
                    item.setPatientName(patientName);
                    item.setEncounterId(encounterId);
                    item.setTitleKey(TITLE_KEY);
                    item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, TITLE_KEY));
                    item.setBodyKey(BODY_KEY);
                    item.setBodyArgs(bodyArgs);
                    item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, BODY_KEY, bodyArgs));
                    inBasketService.createItemInNewTransaction(item);
                }
            } catch (RuntimeException ex) {
                log.warn("Pre-authorisation follow-up not filed for imaging order {}: {}", orderId, ex.getMessage());
            }
        });
    }
}
