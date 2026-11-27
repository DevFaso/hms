package com.example.hms.service.pharmacy;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * Either way the release is audited and the store manager and the hospital administrators get a task to set the
 * price (or document the item as free). The task closes when they do. An ordinary outpatient fill of an unpriced
 * item stays refused.
 *
 * <p>The task is read by the store manager and hospital administrators, who are not the dispensing pharmacist; it
 * names the medicine and the pharmacy, never the patient (QA D3301).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ProvisionalRule(ClinicalRuleSetKey.UNPRICED_FILL_RELEASE)
public class UnpricedFillRelease {

    /** The in-basket reference type of the pricing task; its id is {@link #referenceOf}. */
    public static final String REFERENCE_TYPE = "PHARMACY_ITEM_PRICE";

    /** Who sets a pharmacy's selling price: the store manager and the hospital administrators. */
    static final Set<String> PRICE_ROLE_CODES =
        Set.of(SecurityConstants.ROLE_STORE_MANAGER, SecurityConstants.ROLE_HOSPITAL_ADMIN);

    static final String TITLE_KEY = "pharmacy.unpricedFill.task.title";
    static final String BODY_KEY = "pharmacy.unpricedFill.task.body";

    private final InBasketService inBasketService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /**
     * Whether an unpriced fill may leave: a logged urgency override was asked for, or the order is charged to an
     * open stay. Nothing else releases it.
     */
    public static boolean releasable(String urgencyReason, boolean chargedToOpenStay) {
        return chargedToOpenStay || (urgencyReason != null && !urgencyReason.isBlank());
    }

    /**
     * One pricing task per medicine and pharmacy, whatever the number of fills: the id is derived from the two, so
     * the in-basket's one-item-per-reference-and-recipient rule keeps it single until the price is set.
     */
    public static UUID referenceOf(UUID pharmacyId, UUID medicationCatalogItemId) {
        return UUID.nameUUIDFromBytes(("pharmacy-item-price:" + pharmacyId + ":" + medicationCatalogItemId)
            .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Files, after the fill commits, a task for the store manager and the hospital administrators to price
     * {@code medication} at {@code pharmacy}. Never for the person who handed over. A failure to file never undoes
     * the fill; the item stays listed as unpriced in the inventory.
     */
    public void priceNeeded(Pharmacy pharmacy, MedicationCatalogItem medication, String medicationName,
                            UUID actorId) {
        UUID pharmacyId = pharmacy != null ? pharmacy.getId() : null;
        UUID hospitalId = pharmacy != null && pharmacy.getHospital() != null ? pharmacy.getHospital().getId() : null;
        UUID catalogId = medication != null ? medication.getId() : null;
        if (pharmacyId == null || hospitalId == null) {
            return;
        }
        UUID reference = referenceOf(pharmacyId, catalogId);
        Object[] args = {Objects.toString(medicationName, "—"), Objects.toString(pharmacy.getName(), "—")};
        TransactionCallbacks.afterCommit(() -> {
            try {
                List<com.example.hms.model.User> recipients = assignmentRepository
                    .findLiveUsersAtHospitalWithRoleCodes(hospitalId, PRICE_ROLE_CODES).stream()
                    .filter(user -> !Objects.equals(user.getId(), actorId))
                    .toList();
                for (com.example.hms.model.User user : recipients) {
                    CreateInBasketItemRequest item = new CreateInBasketItemRequest();
                    item.setHospitalId(hospitalId);
                    item.setItemType(InBasketItemType.TASK);
                    item.setPriority(InBasketPriority.URGENT);
                    item.setReferenceId(reference);
                    item.setReferenceType(REFERENCE_TYPE);
                    item.setTitleKey(TITLE_KEY);
                    item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, TITLE_KEY));
                    item.setBodyKey(BODY_KEY);
                    item.setBodyArgs(args);
                    item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, BODY_KEY, args));
                    item.setRecipientUserId(user.getId());
                    inBasketService.createItemInNewTransaction(item);
                }
                if (recipients.isEmpty()) {
                    log.warn("Unpriced fill at pharmacy {}: no store manager or hospital admin to file a pricing "
                        + "task for", pharmacyId);
                }
            } catch (RuntimeException ex) {
                log.warn("Pricing task not filed for pharmacy {}: {}", pharmacyId, ex.getMessage());
            }
        });
    }

    /** The item is now priced or documented as free: its pricing task is done. In the caller's transaction. */
    public void priced(UUID pharmacyId, UUID medicationCatalogItemId) {
        if (pharmacyId != null && medicationCatalogItemId != null) {
            inBasketService.removeAllItemsForReference(referenceOf(pharmacyId, medicationCatalogItemId),
                REFERENCE_TYPE);
        }
    }
}
