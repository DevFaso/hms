package com.example.hms.service.billing;

import com.example.hms.enums.PriceListLinkType;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.PriceListPrice;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.PriceListPriceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing-money plan PR 3 (plan section 3.3, scorecard M1): the price a charge takes from its hospital's price list.
 *
 * <p>Given the hospital, the link a charge source names (its type and raw key) and the service date, it returns the
 * version of the hospital's one active item on that link that is in force on that day. The key is written exactly as
 * the price list stored it ({@link PriceListLinks#lookupKey}).
 *
 * <ul>
 *   <li><b>Tenancy.</b> Only the given hospital's items are read. A null hospital never falls open to every
 *       hospital's list: it finds nothing (the #1179 review's HIGH finding).</li>
 *   <li><b>Dates (D-4).</b> The version in force on the service day: a price that starts tomorrow never prices
 *       today's visit. The line copies the price and the version id, so a later price never rewrites it.</li>
 *   <li><b>Business day (D-14/D-16).</b> A server timestamp is turned into a day in the hospital's business zone
 *       (Africa/Ouagadougou).</li>
 * </ul>
 */
@Component
public class TariffResolver {

    /** What priced a line: the item and the version whose price it copies, and the label as the hospital typed it. */
    public record Tariff(UUID itemId, UUID priceId, String label, BigDecimal unitPrice) {
    }

    private final PriceListItemRepository itemRepository;
    private final PriceListPriceRepository priceRepository;
    private final Clock clock;
    private final ZoneId businessZone;

    public TariffResolver(PriceListItemRepository itemRepository,
                          PriceListPriceRepository priceRepository,
                          Clock clock,
                          @Value("${app.billing.business-zone:Africa/Ouagadougou}") String businessZone) {
        this.itemRepository = itemRepository;
        this.priceRepository = priceRepository;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    /**
     * The tariff of {@code hospitalId} for this link on {@code day}; empty when the hospital, the key or the day is
     * missing, when no active item has the link, or when no version is in force that day. Runs in the caller's
     * transaction (the charge's), which holds the item's share lock until it commits.
     */
    public Optional<Tariff> resolve(UUID hospitalId, PriceListLinkType type, String rawKey, LocalDate day) {
        if (hospitalId == null || day == null) {
            return Optional.empty();
        }
        Optional<String> key = PriceListLinks.lookupKey(type, rawKey);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        List<PriceListItem> items = itemRepository.findActiveByLinkForCharge(hospitalId, type, key.get());
        // One active item per link (uq_price_list_item_active_link); anything else is never guessed between.
        if (items == null || items.size() != 1) {
            return Optional.empty();
        }
        PriceListItem item = items.get(0);
        if (!hospitalId.equals(item.getHospitalId())) {
            return Optional.empty();
        }
        List<PriceListPrice> versions = priceRepository.findInForce(item.getId(), hospitalId, day);
        if (versions == null) {
            return Optional.empty();
        }
        return versions.stream()
            .filter(version -> hospitalId.equals(version.getHospitalId()) && version.coversDay(day))
            .findFirst()
            .map(version -> new Tariff(item.getId(), version.getId(), item.getLabel(), version.getUnitPrice()));
    }

    /**
     * PR 4 review: share-locks every active linked item of the hospital in code order, once per checkout, before
     * any charge is priced (see {@code PriceListItemRepository#lockActiveLinkedForCheckout}).
     */
    public void lockForCheckout(UUID hospitalId) {
        if (hospitalId != null) {
            itemRepository.lockActiveLinkedForCheckout(hospitalId);
        }
    }

    /** The business day of a server timestamp (written in the server's zone), or today when there is none. */
    public LocalDate businessDayOf(LocalDateTime serverTime) {
        if (serverTime == null) {
            return LocalDate.now(clock.withZone(businessZone));
        }
        return serverTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(businessZone).toLocalDate();
    }

    /** Today in the hospital's business zone. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(businessZone));
    }

    /** The resolver bound to one hospital and one service day, for the charge sources of one visit. */
    public static final class Tariffs {

        private final TariffResolver resolver;
        private final UUID hospitalId;
        private final LocalDate day;
        /** Whether this checkout already holds its share locks (taken on the first lookup, in code order). */
        private boolean locked;

        public Tariffs(TariffResolver resolver, UUID hospitalId, LocalDate day) {
            this.resolver = resolver;
            this.hospitalId = hospitalId;
            this.day = day;
        }

        /** No price list (a unit test that does not exercise one). */
        public static Tariffs none() {
            return new Tariffs(null, null, null);
        }

        public Optional<Tariff> find(PriceListLinkType type, String rawKey) {
            if (resolver == null) {
                return Optional.empty();
            }
            if (!locked && hospitalId != null) {
                resolver.lockForCheckout(hospitalId);
                locked = true;
            }
            return resolver.resolve(hospitalId, type, rawKey, day);
        }

        public LocalDate day() {
            return day;
        }
    }
}
