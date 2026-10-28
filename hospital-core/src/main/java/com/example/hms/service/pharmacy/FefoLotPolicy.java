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
 * expired) expires before the chosen one and holds enough for this movement,
 * taking the later lot needs a reason. The reason is returned so the caller
 * records it with the movement; without one the movement is refused, naming
 * the lot to take. A shorter-dated lot that cannot cover the movement on its
 * own does not count: one movement comes from one lot.
 */
@Component
@RequiredArgsConstructor
public class FefoLotPolicy {

    /** The longest override reason kept (it is appended to the fill notes or the ledger reason). */
    static final int REASON_MAX = 300;

    private final StockLotRepository stockLotRepository;

    /**
     * The shorter-dated usable lot of the same item that could serve
     * {@code quantity} instead of {@code chosen}, if any (the shortest-dated
     * first).
     */
    public Optional<StockLot> earlierLot(StockLot chosen, BigDecimal quantity) {
        if (chosen == null || chosen.getInventoryItem() == null || chosen.getExpiryDate() == null) {
            return Optional.empty();
        }
        BigDecimal needed = quantity != null ? quantity : BigDecimal.ZERO;
        List<StockLot> usable = stockLotRepository.findAvailableLotsByFEFO(chosen.getInventoryItem().getId());
        if (usable == null) {
            return Optional.empty();
        }
        return usable.stream()
                .filter(lot -> lot.getId() != null && !lot.getId().equals(chosen.getId()))
                .filter(lot -> lot.getExpiryDate() != null && lot.getExpiryDate().isBefore(chosen.getExpiryDate()))
                .filter(lot -> lot.getRemainingQuantity() != null && lot.getRemainingQuantity().compareTo(needed) >= 0)
                .findFirst();
    }

    /**
     * Refuses a movement out of {@code chosen} that skips a shorter-dated lot
     * with no reason. Returns the trimmed reason when the rule was overridden,
     * null when FEFO was followed (a reason sent then is not recorded).
     */
    public String requireFefoOrReason(StockLot chosen, BigDecimal quantity, String overrideReason) {
        Optional<StockLot> earlier = earlierLot(chosen, quantity);
        if (earlier.isEmpty()) {
            return null;
        }
        String reason = overrideReason == null ? "" : overrideReason.trim();
        if (reason.isEmpty()) {
            StockLot lot = earlier.get();
            throw new BusinessException("pharmacy.fefo.reasonRequired", lot.getLotNumber(),
                    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                            .withLocale(org.springframework.context.i18n.LocaleContextHolder.getLocale())
                            .format(lot.getExpiryDate()));
        }
        if (reason.length() > REASON_MAX) {
            throw new BusinessException("pharmacy.fefo.reasonTooLong", String.valueOf(REASON_MAX));
        }
        return reason;
    }
}
