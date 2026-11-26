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
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** D3950: manual and scheduled stock alerts share the same live recipients and durable daily claim. */
@Service
@RequiredArgsConstructor
public class LowStockAlertDelivery {
    static final Set<String> RECIPIENT_ROLES = Set.of("ROLE_PHARMACIST", "ROLE_STORE_MANAGER", "PHARMACIST", "STORE_MANAGER");
    private final UserRoleHospitalAssignmentRepository assignments;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public int deliver(InventoryItem item, Object[] arguments) {
        if (item == null || !item.isActive() || item.getPharmacy() == null || !item.getPharmacy().isActive()
                || item.getPharmacy().getHospital() == null || !item.getPharmacy().getHospital().isActive()
                || item.getQuantityOnHand() == null || item.getReorderThreshold() == null
                || item.getQuantityOnHand().compareTo(item.getReorderThreshold()) > 0) {
            return 0;
        }
        UUID hospitalId = item.getPharmacy().getHospital().getId();
        var recipients = new LinkedHashMap<UUID, User>();
        assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId, RECIPIENT_ROLES).stream()
            .filter(user -> user.getId() != null && user.getUsername() != null && !user.getUsername().isBlank())
            .forEach(user -> recipients.putIfAbsent(user.getId(), user));
        LocalDate day = LocalDate.now(clock);
        int sent = 0;
        for (User recipient : recipients.values()) {
            int claimed = jdbc.update("""
                INSERT INTO clinical.low_stock_alert_receipts (inventory_item_id, recipient_user_id, alert_day)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, item.getId(), recipient.getId(), day);
            if (claimed == 1) {
                // The row and notification commit together; a failed notification leaves the claim retryable.
                notifications.createNotification("inventory.lowStock.notification", arguments,
                    recipient.getUsername(), "REORDER_ALERT", item.getId());
                sent++;
            }
        }
        return sent;
    }
}
