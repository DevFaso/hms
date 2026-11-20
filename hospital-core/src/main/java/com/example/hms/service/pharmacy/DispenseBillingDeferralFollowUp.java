package com.example.hms.service.pharmacy;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Policy rule 6 (provisional, V345): the task a deferral leaves for somebody else. An urgency override files one for
 * the review that must follow it; a third-party cover asked for files one for the check of the claim or voucher. The
 * task is closed when the override is reviewed or the request is decided or withdrawn.
 *
 * <p>The task goes to the billing staff of the hospital, the finance-side role that reads the in-basket; accountants
 * and hospital admins work the same items from the deferrals page, which lists every one of them whether or not a
 * task could be filed. Filed after commit, in its own transaction: a failure to file never undoes a hand-over.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DispenseBillingDeferralFollowUp {

    /** The in-basket reference type of the task; its reference id is the deferral's id. */
    public static final String REFERENCE_TYPE = "DISPENSE_BILLING_DEFERRAL";

    /** Who reads the task: the hospital's billing staff. */
    static final Set<String> BILLING_ROLE_CODES = Set.of(SecurityConstants.ROLE_BILLING_SPECIALIST);

    static final String REVIEW_TITLE_KEY = "pharmacy.deferral.followUp.reviewTitle";
    static final String REVIEW_BODY_KEY = "pharmacy.deferral.followUp.reviewBody";
    static final String APPROVAL_TITLE_KEY = "pharmacy.deferral.followUp.approvalTitle";
    static final String APPROVAL_BODY_KEY = "pharmacy.deferral.followUp.approvalBody";

    private final InBasketService inBasketService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /** An urgency override was made: finance reviews it. */
    public void reviewNeeded(DispenseBillingDeferral deferral, String patientName, String medicationName) {
        file(deferral, patientName, medicationName, REVIEW_TITLE_KEY, REVIEW_BODY_KEY, InBasketPriority.NORMAL);
    }

    /** A third-party cover was asked for: billing checks the claim or voucher. */
    public void approvalNeeded(DispenseBillingDeferral deferral, String patientName, String medicationName) {
        if (deferral.getKind() != DispenseBillingDeferralKind.THIRD_PARTY_COVER) {
            return;
        }
        file(deferral, patientName, medicationName, APPROVAL_TITLE_KEY, APPROVAL_BODY_KEY, InBasketPriority.URGENT);
    }

    /** The override was reviewed, or the request decided or withdrawn: nothing is left to do. */
    public void close(DispenseBillingDeferral deferral) {
        if (deferral != null && deferral.getId() != null) {
            inBasketService.removeAllItemsForReference(deferral.getId(), REFERENCE_TYPE);
        }
    }

    private void file(DispenseBillingDeferral deferral, String patientName, String medicationName,
                      String titleKey, String bodyKey, InBasketPriority priority) {
        UUID deferralId = deferral.getId();
        UUID hospitalId = deferral.getHospitalId();
        UUID patientId = deferral.getPatientId();
        UUID actorId = deferral.getRequestedBy();
        if (deferralId == null || hospitalId == null) {
            return;
        }
        TransactionCallbacks.afterCommit(() -> {
            try {
                List<com.example.hms.model.User> billing =
                    assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, BILLING_ROLE_CODES);
                Object[] bodyArgs = {medicationName != null ? medicationName : "", patientName != null ? patientName : ""};
                int filed = 0;
                for (com.example.hms.model.User user : billing) {
                    // Never the same person: the one who asked or overrode does not get their own task.
                    if (user.getId() != null && user.getId().equals(actorId)) {
                        continue;
                    }
                    CreateInBasketItemRequest item = task(deferralId, hospitalId, priority, titleKey, bodyKey, bodyArgs);
                    item.setRecipientUserId(user.getId());
                    item.setPatientId(patientId);
                    item.setPatientName(patientName);
                    inBasketService.createItemInNewTransaction(item);
                    filed++;
                }
                if (filed == 0) {
                    log.warn("Deferral {} at hospital {} has no billing staff to file a task for; it stays listed on "
                        + "the deferrals page", deferralId, hospitalId);
                }
            } catch (RuntimeException ex) {
                log.warn("Follow-up task not filed for deferral {}: {}", deferralId, ex.getMessage());
            }
        });
    }

    /** One task about a deferral: keyed, so each reader gets it in their language. */
    private static CreateInBasketItemRequest task(UUID deferralId, UUID hospitalId, InBasketPriority priority,
                                                  String titleKey, String bodyKey, Object[] bodyArgs) {
        CreateInBasketItemRequest item = new CreateInBasketItemRequest();
        item.setHospitalId(hospitalId);
        item.setItemType(InBasketItemType.TASK);
        item.setPriority(priority);
        item.setReferenceId(deferralId);
        item.setReferenceType(REFERENCE_TYPE);
        item.setTitleKey(titleKey);
        item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, titleKey));
        item.setBodyKey(bodyKey);
        item.setBodyArgs(bodyArgs);
        item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, bodyKey, bodyArgs));
        return item;
    }
}
