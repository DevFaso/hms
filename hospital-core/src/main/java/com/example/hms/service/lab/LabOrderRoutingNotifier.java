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
 * no SMS), and nothing here can fail the order.
 *
 * <p>Two transaction rules earn that last clause, and both were learned the
 * hard way. <strong>Nothing transactional runs in the caller's
 * transaction</strong>: a repository call participates in it, so a failure
 * there marks it rollback-only before the {@code catch} here swallows the
 * exception, and {@code createLabOrder} then returns a DTO and dies at commit
 * with {@code UnexpectedRollbackException} — a notification lookup failing
 * the order it was meant to announce. Only plain values are read from the
 * entity while the session is open; resolving the recipients waits.
 * <strong>And the after-commit callback is not a transaction</strong>: it
 * runs with the committed transaction's resources still bound, so data access
 * there silently joins one that will never flush again (the item-45 lesson).
 * Everything it does — the recipient lookup and each notification row — is
 * therefore wrapped in its own {@code REQUIRES_NEW} transaction.
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
     * Call it inside the transaction that saved the order: the hospital and
     * test names are LAZY and are read here, as plain strings, while the
     * session is open. Nothing else about this method touches the database.
     */
    public void notifyPerformingLab(LabOrder order) {
        if (order == null || !order.isPerformedExternally()) {
            return;
        }
        UUID orderId = order.getId();
        Routing routing;
        try {
            routing = new Routing(
                orderId,
                order.getPerformingHospital().getId(),
                order.getHospital() != null ? order.getHospital().getName() : null,
                order.getLabTestDefinition() != null ? order.getLabTestDefinition().getName() : null);
        } catch (RuntimeException ex) {
            log.warn("Could not read the routing of lab order {} for its performing-lab notification: {}",
                orderId, ex.getMessage());
            return;
        }
        log.info("Lab order {} routed to hospital {}: notifying its lab users after commit",
            orderId, routing.performingHospitalId());
        TransactionCallbacks.afterCommit(() -> deliver(routing));
    }

    /** What the notification needs, as plain values detached from the session. */
    private record Routing(UUID orderId, UUID performingHospitalId, String orderingHospitalName, String testName) { }

    private void deliver(Routing routing) {
        TransactionTemplate ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Set<String> recipients;
        try {
            recipients = ownTransaction.execute(status -> resolveRecipients(routing.performingHospitalId()));
        } catch (RuntimeException ex) {
            log.warn("Could not resolve the lab users of hospital {} for lab order {}: {}",
                routing.performingHospitalId(), routing.orderId(), ex.getMessage());
            return;
        }
        if (recipients == null || recipients.isEmpty()) {
            log.info("Lab order {} routed to hospital {} which has no active lab user to notify",
                routing.orderId(), routing.performingHospitalId());
            return;
        }

        String message;
        try {
            message = messageSource.getMessage(MESSAGE_KEY,
                new Object[]{routing.orderingHospitalName(), routing.testName()}, NotificationLocales.STAFF);
        } catch (RuntimeException ex) {
            log.warn("Could not render the performing-lab notification for lab order {}: {}",
                routing.orderId(), ex.getMessage());
            return;
        }

        for (String username : recipients) {
            try {
                ownTransaction.executeWithoutResult(status ->
                    notificationService.createNotification(message, username, NOTIFICATION_TYPE));
            } catch (RuntimeException ex) {
                log.warn("Performing-lab notification for lab order {} failed for one recipient: {}",
                    routing.orderId(), ex.getMessage());
            }
        }
    }

    private Set<String> resolveRecipients(UUID performingHospitalId) {
        Set<String> recipients = new LinkedHashSet<>();
        for (String role : LAB_ROLES) {
            recipients.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(performingHospitalId, role));
        }
        return recipients;
    }
}
