package com.example.hms.service.billing;

import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.model.Encounter;
import com.example.hms.model.Treatment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * QA D972: one kind of billable item of a checked-out visit. Each
 * {@link BillableOrderType} charged on the visit invoice has exactly one
 * implementation; the checkout listener asks every source for its lines.
 *
 * <p>A source reads by the visit's id inside the listener's own transaction
 * and leaves out what was withdrawn: billing never charges an order the
 * clinician took back.
 *
 * <p>Billing-money plan PR 3: a line is priced, in this order, by the hospital's price list through the link its
 * {@link BillableOrderType} declares ({@link Visit#tariff}, the version in force on the service day), else by the
 * hospital's service of the same name ({@link VisitPriceList}), else posted at zero for the cashier.
 */
public interface VisitChargeSource {

    /** The registry entry this source charges. */
    BillableOrderType type();

    /** The visit's lines of this kind, in a stable order; empty when it has none. */
    List<VisitCharge> charges(Visit visit);

    /**
     * The visit being billed, when it was closed, the hospital's priced services (matched by name, the fallback) and
     * its price list on the service day (billing-money plan PR 3).
     */
    record Visit(Encounter encounter, LocalDateTime checkedOutAt, VisitPriceList prices,
                 TariffResolver.Tariffs tariffs) {

        public Visit {
            tariffs = tariffs != null ? tariffs : TariffResolver.Tariffs.none();
        }

        /** The hospital's tariff for this link on the service day, if its price list has one. */
        public Optional<TariffResolver.Tariff> tariff(PriceListLinkType type, String rawKey) {
            return tariffs.find(type, rawKey);
        }
    }

    /**
     * One line. A null {@code unitPrice} posts the line at zero for the
     * cashier to price: never a guessed price (QA D516).
     *
     * <p>QA D1901: {@code text} is set when the server words the line (the
     * default consultation, the bed days, a transfusion); the line then
     * stores its code and arguments and {@code description} is the French
     * copy. A hospital's own service name, an order's test or study name is
     * shown as written and has no {@code text}.
     */
    record VisitCharge(String description, BillingText text, ItemCategory category, int quantity,
                       BigDecimal unitPrice, Treatment service, TariffResolver.Tariff tariff) {

        public VisitCharge {
            quantity = Math.max(quantity, 1);
        }

        /** A line priced by a service, or not at all (no price-list tariff). */
        public VisitCharge(String description, BillingText text, ItemCategory category, int quantity,
                           BigDecimal unitPrice, Treatment service) {
            this(description, text, category, quantity, unitPrice, service, null);
        }

        /**
         * PR 3: a line priced from the hospital's price list. It is named by the hospital's label as typed (plan
         * 3.7: no code, never translated) and copies the version's price.
         */
        public static VisitCharge tariffed(TariffResolver.Tariff tariff, ItemCategory category, int quantity) {
            return new VisitCharge(tariff.label(), null, category, quantity, tariff.unitPrice(), null, tariff);
        }

        /** A line named as written (a hospital's service, an order), priced by its service when one matched. */
        public static VisitCharge of(String description, ItemCategory category, int quantity,
                                     java.util.Optional<Treatment> service) {
            return new VisitCharge(description, null, category, quantity,
                service.map(Treatment::getPrice).orElse(null), service.orElse(null));
        }

        /** A line the server words (QA D1901): its code and arguments, the French copy as description. */
        public static VisitCharge worded(BillingText text, ItemCategory category, int quantity,
                                         java.util.Optional<Treatment> service) {
            return new VisitCharge(text.french(), text, category, quantity,
                service.map(Treatment::getPrice).orElse(null), service.orElse(null));
        }
    }
}
