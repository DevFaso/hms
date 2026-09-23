package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Tells the prescriber what the pharmacy did with their order (gap G6).
 *
 * <p>Until this, nothing in {@code service/pharmacy} wrote a notification to
 * anyone but the pharmacist's own reorder alert: a prescription could be
 * dispensed, back-ordered, sent to a partner and refused there, and the
 * doctor who wrote it learned nothing unless they went looking.
 *
 * <p>Two rules shape the wiring. The write is deferred to AFTER the caller's
 * transaction commits, so a rolled-back dispense never announces itself; and
 * the callback carries only the prescription id, because after commit there
 * is no persistence context and the prescription's {@code staff.user} is a
 * LAZY association (see out-of-session-lazy-proxies). The
 * {@link PrescriberPharmacyNotificationWriter} reloads the row in its own
 * REQUIRES_NEW transaction and does the reading there.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PrescriberPharmacyNotifier {

    /** The pharmacy outcomes a prescriber is told about. Immutable: it is public. */
    public static final Set<PrescriptionStatus> NOTIFIED_EVENTS = Set.of(
            PrescriptionStatus.DISPENSED,
            PrescriptionStatus.PARTIALLY_FILLED,
            PrescriptionStatus.PENDING_STOCK,
            PrescriptionStatus.PARTNER_ACCEPTED,
            PrescriptionStatus.PARTNER_REJECTED,
            PrescriptionStatus.PARTNER_DISPENSED,
            PrescriptionStatus.PENDING_CLARIFICATION);

    private final PrescriberPharmacyNotificationWriter writer;

    /**
     * Queue a notification for the prescriber of {@code prescription} about
     * {@code event}, delivered once the surrounding transaction commits.
     * Silently ignores events outside {@link #NOTIFIED_EVENTS} and a
     * prescription with no id yet. Best-effort: never throws into the caller.
     */
    public void notifyPrescriber(Prescription prescription, PrescriptionStatus event) {
        if (prescription == null || prescription.getId() == null || !NOTIFIED_EVENTS.contains(event)) {
            return;
        }
        UUID prescriptionId = prescription.getId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                writer.write(prescriptionId, event);
            } catch (RuntimeException ex) {
                // An after-commit failure would surface to the caller of
                // commit() as if the business write had failed; it did not.
                log.warn("Prescriber notification for prescription {} ({}) failed: {}",
                        prescriptionId, event, ex.getMessage());
            }
        });
    }
}
