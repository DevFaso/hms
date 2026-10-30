package com.example.hms.service.lab;

import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.service.InBasketService;
import com.example.hms.service.NotificationService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * QA D962: a rejected specimen reaches the clinician who ordered the test.
 *
 * <p>The order goes back to awaiting collection, and nobody on the ward would
 * know a new sample is needed, or why, unless they are told: the ordering
 * clinician gets an in-basket ORDER item (URGENT, the reason as its body) and
 * an in-app notification naming the test, the patient and the reason.
 *
 * <p>The transaction rules are {@link LabOrderRoutingNotifier}'s: plain values
 * are read inside the rejecting transaction, both writes happen after it
 * commits, each in a {@code REQUIRES_NEW} transaction of its own, and a failure
 * is logged (ids only) and never undoes the rejection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabSpecimenRejectionNotifier {

    static final String NOTIFICATION_TYPE = "LAB_SPECIMEN_REJECTED";
    static final String MESSAGE_KEY = "lab.specimen.rejected.notification";
    static final String REFERENCE_TYPE = "LAB_SPECIMEN";

    private final NotificationService notificationService;
    private final InBasketService inBasketService;
    private final PlatformTransactionManager transactionManager;

    /** Call inside the transaction that rejected {@code specimen}. */
    public void specimenRejected(LabSpecimen specimen) {
        LabOrder order = specimen != null ? specimen.getLabOrder() : null;
        User clinician = order != null && order.getOrderingStaff() != null ? order.getOrderingStaff().getUser() : null;
        if (clinician == null || clinician.getId() == null || order.getHospital() == null) {
            return;
        }
        UUID specimenId = specimen.getId();
        String reason = specimen.getRejectionReason();
        Object testName = testNameOf(order);
        Patient patient = order.getPatient();
        Object patientName = patient != null && patient.getFullName() != null
            ? patient.getFullName()
            : NotificationLocalizer.key("patient.fallback.generic");

        CreateInBasketItemRequest item = inBasketItem(order, specimenId, reason, testName, clinician);

        String username = clinician.getUsername();
        Object[] args = {testName, patientName, reason};
        TransactionCallbacks.afterCommit(() -> deliver(item, username, args, specimenId));
    }

    static final String IN_BASKET_TITLE_KEY = "lab.specimen.rejected.inBasketTitle";

    /** The urgent in-basket item for the ordering clinician, built inside the rejecting transaction. */
    private static CreateInBasketItemRequest inBasketItem(LabOrder order, UUID specimenId, String reason,
                                                          Object testName, User clinician) {
        Patient patient = order.getPatient();
        CreateInBasketItemRequest item = new CreateInBasketItemRequest();
        item.setRecipientUserId(clinician.getId());
        item.setHospitalId(order.getHospital().getId());
        item.setItemType(InBasketItemType.ORDER);
        item.setPriority(InBasketPriority.URGENT);
        item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, IN_BASKET_TITLE_KEY,
            testName instanceof String name ? name
                : MessageUtil.resolve(NotificationLocales.STAFF, com.example.hms.mapper.LabOrderMapper.DELETED_TEST_LABEL)));
        // QA D1053: the key too, so each reader sees the title in their language.
        item.setTitleKey(IN_BASKET_TITLE_KEY);
        item.setTitleArgs(new Object[] {testName});
        item.setBody(reason);
        item.setReferenceId(specimenId);
        item.setReferenceType(REFERENCE_TYPE);
        item.setPatientId(patient != null ? patient.getId() : null);
        item.setPatientName(patient != null ? patient.getFullName() : null);
        item.setEncounterId(order.getEncounter() != null ? order.getEncounter().getId() : null);
        return item;
    }

    /** After the commit: the in-basket item and the notification, each on its own, neither failing the other. */
    private void deliver(CreateInBasketItemRequest item, String username, Object[] args, UUID specimenId) {
        try {
            inBasketService.createItemInNewTransaction(item);
        } catch (RuntimeException ex) {
            log.warn("In-basket item not filed for rejected specimen {}: {}", specimenId,
                ex.getClass().getSimpleName());
        }
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            TransactionTemplate own = new TransactionTemplate(transactionManager);
            own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            own.executeWithoutResult(status ->
                notificationService.createNotification(MESSAGE_KEY, args, username, NOTIFICATION_TYPE, specimenId));
        } catch (RuntimeException ex) {
            log.warn("Notification not written for rejected specimen {}: {}", specimenId,
                ex.getClass().getSimpleName());
        }
    }

    private static Object testNameOf(LabOrder order) {
        if (order.getLabTestDefinition() != null && order.getLabTestDefinition().getName() != null) {
            return order.getLabTestDefinition().getName();
        }
        return NotificationLocalizer.key(com.example.hms.mapper.LabOrderMapper.DELETED_TEST_LABEL);
    }
}
