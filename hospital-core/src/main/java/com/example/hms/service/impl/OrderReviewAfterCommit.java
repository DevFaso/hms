package com.example.hms.service.impl;

import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.OrderReviewType;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.config.SecurityConstants;
import com.example.hms.service.InBasketService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.pharmacy.partner.WithdrawnOrderPartnerHandler;
import com.example.hms.service.support.OrderReviewHoldGuard;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * What a hold tells other people, each in a transaction of its own after the event has committed: the hold row alone
 * already stops every use, so nothing here may undo recording a death. Ids and text only: these run with no persistence
 * context of the event's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReviewAfterCommit {

    /** Whoever can decide, when the order's own clinicians are gone or unknown. */
    static final Set<String> FALLBACK_ROLE_CODES = Set.of(SecurityConstants.ROLE_DOCTOR);

    private final PrescriptionRepository prescriptions;
    private final WithdrawnOrderPartnerHandler partnerOffers;
    private final UserRoleHospitalAssignmentRepository assignments;
    private final InBasketService inBasket;

    /** One item for a hospital's readers. {@code recipients} empty falls back to its doctors. */
    public record Notice(UUID hospital, UUID patient, String patientName, UUID reference, String referenceType,
                         String titleKey, String bodyKey, Object[] bodyArgs, Set<UUID> recipients) {
    }

    /** Under the order's lock, and only if the hold still stands: a decision may have come first. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recallPartnerOffers(UUID orderId) {
        Prescription order = prescriptions.findByIdForUpdate(orderId).orElse(null);
        if (order != null && OrderReviewHoldGuard.isHeld(OrderReviewType.PRESCRIPTION, orderId)) {
            partnerOffers.recallForReview(order);
            prescriptions.save(order);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void file(Notice notice) {
        Set<UUID> readers = new LinkedHashSet<>(notice.recipients());
        if (readers.isEmpty()) {
            for (User doctor : assignments.findLiveUsersAtHospitalWithRoleCodes(notice.hospital(), FALLBACK_ROLE_CODES)) {
                readers.add(doctor.getId());
            }
        }
        if (readers.isEmpty()) {
            log.error("Order review notice {} for hospital {} has nobody to tell", notice.reference(), notice.hospital());
        }
        for (UUID reader : readers) {
            CreateInBasketItemRequest item = new CreateInBasketItemRequest();
            item.setRecipientUserId(reader);
            item.setHospitalId(notice.hospital());
            item.setItemType(InBasketItemType.TASK);
            item.setPriority(InBasketPriority.URGENT);
            item.setReferenceId(notice.reference());
            item.setReferenceType(notice.referenceType());
            item.setPatientId(notice.patient());
            item.setPatientName(notice.patientName());
            item.setTitleKey(notice.titleKey());
            item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, notice.titleKey()));
            item.setBodyKey(notice.bodyKey());
            item.setBodyArgs(notice.bodyArgs());
            item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, notice.bodyKey(), notice.bodyArgs()));
            inBasket.createItem(item);
        }
    }
}
