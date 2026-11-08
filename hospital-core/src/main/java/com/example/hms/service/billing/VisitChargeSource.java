package com.example.hms.service.billing;

import com.example.hms.enums.ItemCategory;
import com.example.hms.model.Encounter;
import com.example.hms.model.Treatment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * QA D972: one kind of billable item of a checked-out visit. Each
 * {@link BillableOrderType} charged on the visit invoice has exactly one
 * implementation; the checkout listener asks every source for its lines.
 *
 * <p>A source reads by the visit's id inside the listener's own transaction
 * and leaves out what was withdrawn: billing never charges an order the
 * clinician took back.
 */
public interface VisitChargeSource {

    /** The registry entry this source charges. */
    BillableOrderType type();

    /** The visit's lines of this kind, in a stable order; empty when it has none. */
    List<VisitCharge> charges(Visit visit);

    /** The visit being billed, when it was closed, and the hospital's priced services. */
    record Visit(Encounter encounter, LocalDateTime checkedOutAt, VisitPriceList prices) {
    }

    /**
     * One line. A null {@code unitPrice} posts the line at zero for the
     * cashier to price: never a guessed price (QA D516).
     */
    record VisitCharge(String description, ItemCategory category, int quantity, BigDecimal unitPrice,
                       Treatment service) {

        public VisitCharge {
            quantity = Math.max(quantity, 1);
        }

        /** A line priced by the hospital's service, when one matched. */
        public static VisitCharge of(String description, ItemCategory category, int quantity,
                                     java.util.Optional<Treatment> service) {
            return new VisitCharge(description, category, quantity,
                service.map(Treatment::getPrice).orElse(null), service.orElse(null));
        }
    }
}
