package com.example.hms.service.pharmacy;

import com.example.hms.config.SecurityConstants;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Review of #1380 (F3), coordinator decision, provisional ({@code UNPRICED_FILL_RELEASE}): a missing selling price
 * never blocks care. A fill whose item has no price and no documented free-of-charge reason is refused at the
 * counter ({@link FillPriceGate}) except in two cases, where it leaves with the payment status {@code NOT_PRICED}:
 * <ul>
 *   <li>the pharmacist logs an urgency override with a reason (the same override as an unpaid fill, reviewed by
 *       finance afterwards), or</li>
 *   <li>the order is charged to the patient's open stay.</li>
 * </ul>
 * Either way the release is audited and the hospital's store managers and hospital administrators, who set the
 * selling price, are told (a notification: neither role reads the in-basket). The notification is marked read when
 * the item is priced or documented free. An ordinary outpatient fill of an unpriced item stays refused.
 *
 * <p>The notification names the medicine and the pharmacy, never the patient (QA D3301).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ProvisionalRule(ClinicalRuleSetKey.UNPRICED_FILL_RELEASE)
public class UnpricedFillRelease {

    /** The notification type; its subject is {@link #referenceOf}. */
    public static final String NOTIFICATION_TYPE = "PHARMACY_PRICE_NEEDED";

    /** Who sets a pharmacy's selling price: the store manager and the hospital administrators. */
    static final Set<String> PRICE_ROLE_CODES =
        Set.of(SecurityConstants.ROLE_STORE_MANAGER, SecurityConstants.ROLE_HOSPITAL_ADMIN);

    static final String MESSAGE_KEY = "pharmacy.unpricedFill.task.body";

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;

    /**
     * Whether an unpriced fill may leave: a logged urgency override was asked for, or the order is charged to an
     * open stay. Nothing else releases it.
     */
    public static boolean releasable(String urgencyReason, boolean chargedToOpenStay) {
        return chargedToOpenStay || (urgencyReason != null && !urgencyReason.isBlank());
    }

    /** The subject of the notifications about one medicine at one pharmacy, whatever the number of fills. */
    public static UUID referenceOf(UUID pharmacyId, UUID medicationCatalogItemId) {
        return UUID.nameUUIDFromBytes(("pharmacy-item-price:" + pharmacyId + ":" + medicationCatalogItemId)
            .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Tells, after the fill commits, each store manager and hospital administrator of the hospital (never the
     * person who handed over) that {@code medication} left {@code pharmacy} with no price. Each notification is
     * written in its own transaction: a failure never undoes the fill, and the item stays listed as unpriced in the
     * inventory.
     */
    public void priceNeeded(Pharmacy pharmacy, MedicationCatalogItem medication, String medicationName,
                            UUID actorId) {
        UUID pharmacyId = pharmacy != null ? pharmacy.getId() : null;
        UUID hospitalId = pharmacy != null && pharmacy.getHospital() != null ? pharmacy.getHospital().getId() : null;
        if (pharmacyId == null || hospitalId == null) {
            return;
        }
        UUID subject = referenceOf(pharmacyId, medication != null ? medication.getId() : null);
        Object[] args = {Objects.toString(medicationName, "—"), Objects.toString(pharmacy.getName(), "—")};
        TransactionCallbacks.afterCommit(() -> {
            try {
                assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, PRICE_ROLE_CODES).stream()
                    .filter(user -> !Objects.equals(user.getId(), actorId))
                    .map(com.example.hms.model.User::getUsername)
                    .filter(username -> username != null && !username.isBlank())
                    .distinct()
                    .forEach(username -> notifyInOwnTransaction(username, args, subject));
            } catch (RuntimeException ex) {
                log.warn("Price roles not told of an unpriced fill at pharmacy {}: {}", pharmacyId, ex.getMessage());
            }
        });
    }

    private void notifyInOwnTransaction(String username, Object[] args, UUID subject) {
        try {
            TransactionTemplate own = new TransactionTemplate(transactionManager);
            own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            own.executeWithoutResult(status -> notificationService.createNotification(MESSAGE_KEY, args, username,
                NOTIFICATION_TYPE, subject));
        } catch (RuntimeException ex) {
            log.warn("A price role was not told of an unpriced fill: {}", ex.getClass().getSimpleName());
        }
    }

    /** The item is now priced or documented as free: its notifications are answered. In the caller's transaction. */
    public void priced(UUID pharmacyId, UUID medicationCatalogItemId) {
        if (pharmacyId != null && medicationCatalogItemId != null) {
            notificationService.markReadForSubject(referenceOf(pharmacyId, medicationCatalogItemId),
                Set.of(NOTIFICATION_TYPE));
        }
    }
}
