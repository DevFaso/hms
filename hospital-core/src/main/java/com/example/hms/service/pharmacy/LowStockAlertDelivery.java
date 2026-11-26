package com.example.hms.service.pharmacy;

import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.NotificationService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.service.i18n.NotificationLocalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** D3950: manual and scheduled stock alerts share the same live recipients and durable daily claim. */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class LowStockAlertDelivery {
    static final Set<String> RECIPIENT_ROLES = Set.of("ROLE_PHARMACIST", "ROLE_STORE_MANAGER", "PHARMACIST", "STORE_MANAGER");
    private final UserRoleHospitalAssignmentRepository assignments;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final InventoryItemRepository items;
    private final org.springframework.transaction.PlatformTransactionManager transactions;

    public int deliver(UUID itemId) {
        InventoryItem item = itemId == null ? null : items.findForAlertDelivery(itemId).orElse(null);
        if (!eligible(item)) return 0;
        UUID hospitalId = item.getPharmacy().getHospital().getId();
        var recipients = new LinkedHashMap<UUID, User>();
        assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId, RECIPIENT_ROLES).stream()
            .filter(user -> user.getId() != null && user.getUsername() != null && !user.getUsername().isBlank())
            .forEach(user -> recipients.putIfAbsent(user.getId(), user));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int sent = 0;
        for (User recipient : recipients.values()) {
            try {
                Integer delivered = transaction.execute(status -> deliverOne(itemId, recipient));
                sent += delivered == null ? 0 : delivered;
            } catch (RuntimeException failure) {
                // The failed recipient's claim rolls back before continuing; other colleagues still get the warning.
                log.warn("Low-stock notification failed for inventory item {} and recipient {}", itemId, recipient.getId(), failure);
            }
        }
        return sent;
    }

    private int deliverOne(UUID itemId, User recipient) {
        // A separate persistence context for every claim: quantities and eligibility are never a sweep snapshot.
        InventoryItem item = items.findForAlertDelivery(itemId).orElse(null);
        if (!eligible(item)) return 0;
        boolean stillAuthorized = assignments.findLiveUsersAtHospitalWithRoleCodes(
            item.getPharmacy().getHospital().getId(), RECIPIENT_ROLES).stream()
            .anyMatch(user -> recipient.getId().equals(user.getId()));
        if (!stillAuthorized) return 0;
        int claimed = jdbc.update("""
            INSERT INTO clinical.low_stock_alert_receipts (inventory_item_id, recipient_user_id, alert_day)
            VALUES (?, ?, ?) ON CONFLICT DO NOTHING
            """, item.getId(), recipient.getId(), LocalDate.now(clock));
        if (claimed == 0) return 0;
        Object[] arguments = {NotificationLocalizer.drugName(item.getMedicationCatalogItem()),
            item.getPharmacy().getName() == null ? NotificationLocalizer.key("inventory.lowStock.unknown") : item.getPharmacy().getName(),
            item.getQuantityOnHand(), item.getReorderThreshold()};
        notifications.createNotification("inventory.lowStock.notification", arguments,
            recipient.getUsername(), "REORDER_ALERT", item.getId());
        return 1;
    }

    private static boolean eligible(InventoryItem item) {
        return item != null && item.isActive() && item.getPharmacy() != null && item.getPharmacy().isActive()
            && item.getPharmacy().getHospital() != null && item.getPharmacy().getHospital().isActive()
            && item.getQuantityOnHand() != null && item.getReorderThreshold() != null
            && item.getQuantityOnHand().compareTo(item.getReorderThreshold()) <= 0;
    }
}
