package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.repository.pharmacy.StockLotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * QA D964: first expired, first out, on every path that takes stock off a
 * chosen lot - a fill (one-step or prepared), a counter sale line and a
 * transfer out. A paracetamol and a morphine fill went out of the later-dated
 * lot while R16-PCM-COURT (100 left, shorter-dated) stayed on the shelf, with
 * no warning.
 *
 * <p>The rule: when another usable lot of the same item (stock left, not
 * expired) expires before the chosen one, taking the later lot needs a
 * reason. The reason is returned so the caller records it with the movement;
 * without one the movement is refused, naming the lot to take.
 *
 * <p>QA D1029 (D964's class, round 17): the rule used to count a shorter-dated
 * lot only when it could cover the whole movement on its own, so 130 tablets
 * left the 2028 lot while 120 sat in the 2027 lot, with no reason asked. Any
 * shorter-dated lot with stock left now counts: the earliest lot is emptied
 * first and the remainder comes from the next (a counter sale does it in its
 * lines; the dispensing screen does it in one fill per lot), or a reason is
 * given.
 */
@Component
@RequiredArgsConstructor
public class FefoLotPolicy {

    /** The longest override reason kept (it is appended to the fill notes or the ledger reason). */
    static final int REASON_MAX = 300;

    private final StockLotRepository stockLotRepository;

    /**
     * The shorter-dated usable lot of the same item, with stock left, that
     * should be emptied before {@code chosen} is touched, if any (the
     * shortest-dated first). {@code quantity} is kept for the callers' shape;
     * QA D1029: the earlier lot no longer has to cover it.
     */
    public Optional<StockLot> earlierLot(StockLot chosen, BigDecimal quantity) {
        return earlierLot(chosen, quantity, java.util.Map.of());
    }

    /**
     * {@link #earlierLot(StockLot, BigDecimal)}, less what the other lines of
     * the same movement take off each lot ({@code alreadyTaken}, by lot id;
     * the chosen lot's own entry is never read): a counter sale is built
     * before any stock moves, so 10 from the shorter lot and 5 from the next
     * is FEFO, not a skip, in either order (review of #975).
     */
    public Optional<StockLot> earlierLot(StockLot chosen, BigDecimal quantity,
                                         java.util.Map<java.util.UUID, BigDecimal> alreadyTaken) {
        if (chosen == null || chosen.getInventoryItem() == null || chosen.getExpiryDate() == null) {
            return Optional.empty();
        }
        List<StockLot> usable = stockLotRepository.findAvailableLotsByFEFO(chosen.getInventoryItem().getId());
        if (usable == null) {
            return Optional.empty();
        }
        return usable.stream()
                .filter(lot -> lot.getId() != null && !lot.getId().equals(chosen.getId()))
                .filter(lot -> lot.getExpiryDate() != null && lot.getExpiryDate().isBefore(chosen.getExpiryDate()))
                // QA D1029: any stock left on the earlier lot, not only enough for the whole movement.
                .filter(lot -> lot.getRemainingQuantity() != null && lot.getRemainingQuantity()
                        .subtract(alreadyTaken.getOrDefault(lot.getId(), BigDecimal.ZERO)).signum() > 0)
                .findFirst();
    }

    /**
     * Refuses a movement out of {@code chosen} that skips a shorter-dated lot
     * with no reason. Returns the trimmed reason when the rule was overridden,
     * null when FEFO was followed (a reason sent then is not recorded).
     */
    public String requireFefoOrReason(StockLot chosen, BigDecimal quantity, String overrideReason) {
        return requireFefoOrReason(chosen, quantity, overrideReason, java.util.Map.of());
    }

    /** {@link #requireFefoOrReason(StockLot, BigDecimal, String)} with what earlier lines already take. */
    public String requireFefoOrReason(StockLot chosen, BigDecimal quantity, String overrideReason,
                                      java.util.Map<java.util.UUID, BigDecimal> alreadyTaken) {
        Optional<StockLot> earlier = earlierLot(chosen, quantity, alreadyTaken);
        if (earlier.isEmpty()) {
            return null;
        }
        String reason = overrideReason == null ? "" : overrideReason.trim();
        if (reason.isEmpty()) {
            StockLot lot = earlier.get();
            // QA D1083: the message says what the rule tested (stock left on
            // the earlier lot, D1029) and how much, never that the earlier lot
            // "covers this quantity": it often does not (250 left for 300).
            BigDecimal left = lot.getRemainingQuantity()
                    .subtract(alreadyTaken.getOrDefault(lot.getId(), BigDecimal.ZERO));
            throw new BusinessException("pharmacy.fefo.reasonRequired", lot.getLotNumber(),
                    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                            .withLocale(org.springframework.context.i18n.LocaleContextHolder.getLocale())
                            .format(lot.getExpiryDate()),
                    com.example.hms.utility.DisplayNumbers.quantity(left));
        }
        if (reason.length() > REASON_MAX) {
            throw new BusinessException("pharmacy.fefo.reasonTooLong", String.valueOf(REASON_MAX));
        }
        return reason;
    }
}
