package com.example.hms.service.lab;

import com.example.hms.model.LabOrder;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tells the performing laboratory that an order was sent to it (audit gap B1).
 *
 * <p>An order routed to another hospital's laboratory is invisible until
 * someone there opens the worklist. This mirrors
 * {@code CriticalValueNotificationService}: the recipients are the active lab
 * users at that hospital, the channel is the in-app notification (no e-mail,
 * no SMS), and nothing here can fail the order — the recipient list and the
 * message are resolved inside the ordering transaction while the session is
 * open, and the rows are written {@link TransactionCallbacks#afterCommit after
 * commit}, so a rolled-back order notifies nobody and a notification failure
 * rolls nothing back.
 *
 * <p>The after-commit callback still runs with the committed transaction's
 * resources bound to the thread, so data access there silently joins a
 * transaction that will never flush again (the item-45 lesson). Each
 * notification is therefore written in its own {@code REQUIRES_NEW}
 * transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabOrderRoutingNotifier {

    /** Who works the incoming worklist at a laboratory. */
    static final List<String> LAB_ROLES = List.of("ROLE_LAB_SCIENTIST", "ROLE_LAB_TECHNICIAN", "ROLE_LAB_MANAGER");
    static final String NOTIFICATION_TYPE = "LAB_ORDER_RECEIVED";
    static final String MESSAGE_KEY = "lab.order.external.received";

    private final StaffRepository staffRepository;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final PlatformTransactionManager transactionManager;

    /**
     * Schedule the notification for an order that names a performing hospital.
     * Must be called inside the transaction that saved the order: the ordering
     * hospital and test names are read here, not after commit.
     */
    public void notifyPerformingLab(LabOrder order) {
        if (order == null || !order.isPerformedExternally()) {
            return;
        }
        try {
            UUID performingHospitalId = order.getPerformingHospital().getId();
            Set<String> recipients = new LinkedHashSet<>();
            for (String role : LAB_ROLES) {
                recipients.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(performingHospitalId, role));
            }
            if (recipients.isEmpty()) {
                log.info("Lab order {} routed to hospital {} which has no active lab user to notify",
                    order.getId(), performingHospitalId);
                return;
            }
            String orderingHospital = order.getHospital() != null ? order.getHospital().getName() : null;
            String testName = order.getLabTestDefinition() != null ? order.getLabTestDefinition().getName() : null;
            String message = messageSource.getMessage(MESSAGE_KEY,
                new Object[]{orderingHospital, testName}, NotificationLocales.STAFF);
            UUID orderId = order.getId();
            log.info("Lab order {} routed to hospital {}: notifying {} lab user(s) after commit",
                orderId, performingHospitalId, recipients.size());
            TransactionCallbacks.afterCommit(() -> deliver(orderId, recipients, message));
        } catch (RuntimeException ex) {
            log.warn("Could not schedule the performing-lab notification for lab order {}: {}",
                order.getId(), ex.getMessage());
        }
    }

    private void deliver(UUID orderId, Set<String> recipients, String message) {
        TransactionTemplate ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (String username : recipients) {
            try {
                ownTransaction.executeWithoutResult(status ->
                    notificationService.createNotification(message, username, NOTIFICATION_TYPE));
            } catch (RuntimeException ex) {
                log.warn("Performing-lab notification for lab order {} failed for one recipient: {}",
                    orderId, ex.getMessage());
            }
        }
    }
}
