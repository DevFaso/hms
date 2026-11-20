package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyRefundStatus;
import com.example.hms.enums.PharmacySaleStatus;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacyPayment;
import com.example.hms.model.pharmacy.PharmacyRefund;
import com.example.hms.model.pharmacy.PharmacySale;
import com.example.hms.model.pharmacy.SaleLine;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.PharmacyReconciliationDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyReconciliationDTO.ControlledBalance;
import com.example.hms.payload.dto.pharmacy.PharmacyReconciliationDTO.DeferredFill;
import com.example.hms.payload.dto.pharmacy.PharmacyReconciliationDTO.Difference;
import com.example.hms.payload.dto.pharmacy.PharmacyReconciliationDTO.LotBalance;
import com.example.hms.payload.dto.pharmacy.PharmacyReconciliationDTO.Money;
import com.example.hms.payload.dto.pharmacy.PharmacyReconciliationDTO.StockLine;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.repository.pharmacy.PharmacyRefundRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PharmacySaleRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.CsvCells;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * QA D2673 (r65, billing-money plan PR 13, scorecard M4): one pharmacy's day, money against stock.
 *
 * <ul>
 *   <li><b>Money</b>: counter sales of the day (by method), checkout payments for fills (by method), refunds paid out,
 *       and the net; what the fills handed over cost, with what left « à facturer » and what left unpaid.</li>
 *   <li><b>Stock</b>: per medication and lot, what the sales and the fills of the day recorded against the SALE and
 *       DISPENSE movements written, with the receipts, returns, adjustments and transfers. A fill counts on the day it
 *       was created, when its stock left the shelf (a prepared fill moves stock when prepared).</li>
 *   <li><b>Differences</b>: a sale or fill whose movement is missing or differs, a sale line priced away from the
 *       list, a fill handed over unpaid with no reason, a controlled balance that is not the register's.</li>
 *   <li><b>Controlled substances</b>: the register's balance (every movement, signed) against the stock held now.</li>
 * </ul>
 *
 * The day is the calendar day of the server clock (owner decision D-16: midnight in Africa/Ouagadougou, UTC+0).
 * Read at the acting hospital by a pharmacist, a hospital admin or an accountant; another hospital's pharmacy answers
 * as missing.
 */
@Service
@RequiredArgsConstructor
public class PharmacyReconciliationService {

    /** The CSV export's media type and file name, built here so the controller writes no cell. */
    public static final String CSV_FILE_PREFIX = "rapprochement-pharmacie-";
    public static final String CSV_MEDIA_TYPE = "text/csv;charset=UTF-8";
    /** UTF-8 byte order mark, so a spreadsheet reads the accents. */
    private static final String BOM = String.valueOf((char) 0xFEFF);
    private static final String MONEY_SECTION = "argent";
    private static final String CSV_MEDICATION = "medicament";
    private static final String CSV_DIFFERENCE = "ecart";
    private static final String CSV_DEFERRED = "a_facturer";

    private static final Set<PharmacySaleStatus> SOLD = EnumSet.of(PharmacySaleStatus.COMPLETED,
            PharmacySaleStatus.REFUNDED);

    private final PharmacyRepository pharmacyRepository;
    private final PharmacySaleRepository saleRepository;
    private final DispenseRepository dispenseRepository;
    private final PharmacyPaymentRepository paymentRepository;
    private final PharmacyClaimRepository claimRepository;
    private final PharmacyRefundRepository refundRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final StockLotRepository stockLotRepository;
    private final InventoryItemRepository inventoryItemRepository;
    /** Review of #1239: the price in force when a sale was made. */
    private final PharmacyPriceHistory priceHistory;
    private final RoleValidator roleValidator;
    private final Clock clock;

    /** The active pharmacies of the acting hospital, by name: the page's picker (an accountant reads no registry). */
    @Transactional(readOnly = true)
    public List<PharmacyReconciliationDTO> pharmacies() {
        UUID hospitalId = requireReader();
        return pharmacyRepository.findByHospitalIdAndActiveTrueOrderByNameAsc(hospitalId).stream()
                .map(p -> PharmacyReconciliationDTO.builder().pharmacyId(p.getId()).pharmacyName(p.getName()).build())
                .toList();
    }

    /** The acting hospital, when the caller is a pharmacist, hospital admin or accountant there. */
    private UUID requireReader() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        UUID userId = roleValidator.getCurrentUserId();
        if (hospitalId == null || userId == null || !roleValidator.canReadPharmacyReconciliation(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("pharmacy.reconciliation.readersOnly"));
        }
        return hospitalId;
    }

    @Transactional(readOnly = true)
    public PharmacyReconciliationDTO daily(UUID pharmacyId, LocalDate date) {
        return build(pharmacyId, date);
    }

    /** The day, or today on the server clock when none is given (owner decision D-16). */
    public LocalDate dayOf(LocalDate date) {
        return date != null ? date : LocalDate.now(clock);
    }

    private PharmacyReconciliationDTO build(UUID pharmacyId, LocalDate date) {
        UUID hospitalId = requireReader();
        Pharmacy pharmacy = pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));
        LocalDate day = dayOf(date);
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = from.plusDays(1);

        List<PharmacySale> sales = saleRepository.findForDay(hospitalId, pharmacyId, from, to).stream()
                .filter(s -> SOLD.contains(s.getStatus())).toList();
        List<Dispense> fillsCreated = dispenseRepository.findCreatedForDay(pharmacyId, from, to);
        List<Dispense> handedOver = dispenseRepository.findHandedOverForDay(pharmacyId, from, to);
        List<PharmacyPayment> payments = paymentRepository.findPostedForPharmacyDay(hospitalId, pharmacyId, from, to);
        List<PharmacyRefund> refunds = refundRepository.findPaidBetween(hospitalId, pharmacyId, from, to);
        List<StockTransaction> movements = stockTransactionRepository.findForDay(pharmacyId, from, to);

        List<Difference> differences = new ArrayList<>();
        List<StockLine> stock = stockLines(sales, fillsCreated, movements, differences);
        priceDifferences(sales, differences);
        List<DeferredFill> deferred = new ArrayList<>();
        Money money = money(sales, payments, refunds, handedOver, deferred, differences);
        describeDeferrals(hospitalId, deferred, money);
        refundDifferences(new Day(hospitalId, pharmacyId, from, to), refunds, differences);
        unrefundedReturns(pharmacyId, movements, differences);
        List<LotBalance> lotBalances = lotBalances(pharmacyId, from, to, differences);
        List<ControlledBalance> controlled = controlled(pharmacyId, differences);

        return PharmacyReconciliationDTO.builder()
                .pharmacyId(pharmacy.getId())
                .pharmacyName(pharmacy.getName())
                .date(day)
                .generatedAt(LocalDateTime.now(clock))
                .money(money)
                .stock(stock)
                .differences(differences)
                .controlled(controlled)
                .deferred(deferred)
                .lotBalances(lotBalances)
                .build();
    }

    /** One pharmacy's day: the hospital, the pharmacy and [from, to). */
    private record Day(UUID hospitalId, UUID pharmacyId, LocalDateTime from, LocalDateTime to) {
    }

    // ───────────────────────────── lots ─────────────────────────────

    /**
     * Review of #1239: every lot of the pharmacy, not only those a sale or a fill touched. The ledger's balance when
     * the day began, plus what the ledger moved during the day, must be the stock the lot held when the day ended
     * (what it holds now, less what the ledger moved since). A lot whose quantity changed with no movement, or a
     * movement that changed no quantity, is a LOT_BALANCE_MISMATCH whichever screen or script caused it. A lot that
     * never held or moved anything is checked and not listed.
     */
    private List<LotBalance> lotBalances(UUID pharmacyId, LocalDateTime from, LocalDateTime to,
                                         List<Difference> differences) {
        List<StockLot> lots = stockLotRepository.findAllWithItemByPharmacyId(pharmacyId);
        if (lots.isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> atOpening = signedByLot(stockTransactionRepository.sumByLotAndTypeBefore(pharmacyId, from));
        Map<UUID, BigDecimal> atClosing = signedByLot(stockTransactionRepository.sumByLotAndTypeBefore(pharmacyId, to));
        Map<UUID, BigDecimal> since = signedByLot(stockTransactionRepository.sumByLotAndTypeFrom(pharmacyId, to));
        List<LotBalance> out = new ArrayList<>();
        for (StockLot lot : lots) {
            BigDecimal opening = atOpening.getOrDefault(lot.getId(), BigDecimal.ZERO);
            BigDecimal moved = atClosing.getOrDefault(lot.getId(), BigDecimal.ZERO).subtract(opening);
            BigDecimal closing = nz(lot.getRemainingQuantity()).subtract(since.getOrDefault(lot.getId(), BigDecimal.ZERO));
            boolean matches = opening.add(moved).compareTo(closing) == 0;
            if (matches && opening.signum() == 0 && moved.signum() == 0 && closing.signum() == 0) {
                continue;
            }
            String name = nameOf(lot.getInventoryItem());
            if (!matches) {
                differences.add(Difference.builder().type("LOT_BALANCE_MISMATCH").medicationName(name)
                        .lotNumber(lot.getLotNumber()).referenceId(lot.getId()).expected(opening.add(moved))
                        .actual(closing).build());
            }
            out.add(LotBalance.builder().stockLotId(lot.getId())
                    .inventoryItemId(lot.getInventoryItem() != null ? lot.getInventoryItem().getId() : null)
                    .medicationName(name).lotNumber(lot.getLotNumber()).opening(opening).movements(moved)
                    .closing(closing).matches(matches).build());
        }
        out.sort(java.util.Comparator.comparing((LotBalance l) -> Objects.toString(l.getMedicationName(), ""))
                .thenComparing(l -> Objects.toString(l.getLotNumber(), "")));
        return out;
    }

    /** {@code [lotId, type, total]} rows as each lot's signed balance. */
    private static Map<UUID, BigDecimal> signedByLot(List<Object[]> rows) {
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        if (rows == null) {
            return out;
        }
        for (Object[] row : rows) {
            out.merge((UUID) row[0],
                    ControlledDrugRegisterService.signed((StockTransactionType) row[1], (BigDecimal) row[2]),
                    BigDecimal::add);
        }
        return out;
    }

    // ───────────────────────────── refunds ─────────────────────────────

    /** A refund in one of these states was asked for a return (or refused): the return is not "forgotten". */
    private static final Set<PharmacyRefundStatus> RETURN_HANDLED = EnumSet.of(PharmacyRefundStatus.REQUESTED,
            PharmacyRefundStatus.APPROVED, PharmacyRefundStatus.PAID, PharmacyRefundStatus.REJECTED);
    private static final Set<PharmacyRefundStatus> PAID_OUT = EnumSet.of(PharmacyRefundStatus.PAID);
    private static final String REFUND_EXCEEDS_PAID = "REFUND_EXCEEDS_PAID";

    /**
     * Review of #1239 (money back twice): refunds paid out on a sale or a fill never exceed what is still counted
     * as paid on it. The services refuse the two ways it happened (a cancel that voids the payment of a refunded
     * fill; a payout after the payment was voided); a row that diverges anyway (data written before the fix, or by
     * hand) is listed as REFUND_EXCEEDS_PAID on the day the refund was paid and on the day the payment was voided.
     */
    private void refundDifferences(Day day, List<PharmacyRefund> refundsPaid, List<Difference> differences) {
        Set<UUID> dispenseIds = new java.util.LinkedHashSet<>();
        Set<UUID> saleIds = new java.util.LinkedHashSet<>();
        for (PharmacyRefund refund : refundsPaid) {
            if (refund.getDispenseId() != null) {
                dispenseIds.add(refund.getDispenseId());
            } else if (refund.getSaleId() != null) {
                saleIds.add(refund.getSaleId());
            }
        }
        List<UUID> voided = refundRepository.findRefundedDispensesVoidedBetween(day.hospitalId(), day.pharmacyId(),
                day.from(), day.to());
        dispenseIds.addAll(nzList(voided));
        dispenseIds.forEach(dispenseId -> refundAboveDispensePayment(dispenseId, day.pharmacyId(), differences));
        saleIds.forEach(saleId -> refundAboveSaleTotal(saleId, day.pharmacyId(), differences));
    }

    /** A fill whose paid refunds exceed its payments still counted. */
    private void refundAboveDispensePayment(UUID dispenseId, UUID pharmacyId, List<Difference> differences) {
        BigDecimal stillPaid = nz(paymentRepository.sumPostedForDispense(dispenseId));
        BigDecimal paidOut = nz(refundRepository.sumForDispense(dispenseId, PAID_OUT));
        if (paidOut.compareTo(stillPaid) <= 0) {
            return;
        }
        Dispense fill = dispenseRepository.findByIdAndPharmacy_Id(dispenseId, pharmacyId).orElse(null);
        StockLot lot = fill != null ? fill.getStockLot() : null;
        differences.add(Difference.builder().type(REFUND_EXCEEDS_PAID)
                .medicationName(fill != null ? fill.getMedicationName() : null)
                .lotNumber(lot != null ? lot.getLotNumber() : null)
                .referenceId(dispenseId).expected(stillPaid).actual(paidOut)
                .amount(paidOut.subtract(stillPaid)).build());
    }

    /** A counter sale whose paid refunds exceed its total (nothing, once it is no longer a sale). */
    private void refundAboveSaleTotal(UUID saleId, UUID pharmacyId, List<Difference> differences) {
        BigDecimal stillPaid = saleRepository.findByIdAndPharmacy_Id(saleId, pharmacyId)
                .filter(sale -> SOLD.contains(sale.getStatus()))
                .map(sale -> nz(sale.getTotalAmount())).orElse(BigDecimal.ZERO);
        BigDecimal paidOut = nz(refundRepository.sumForSale(saleId, PAID_OUT));
        if (paidOut.compareTo(stillPaid) > 0) {
            differences.add(Difference.builder().type(REFUND_EXCEEDS_PAID).referenceId(saleId)
                    .expected(stillPaid).actual(paidOut).amount(paidOut.subtract(stillPaid)).build());
        }
    }

    /**
     * Review of #1239: a patient return of the day that names a paid counter sale or a paid fill, and for which no
     * refund was asked (or the request was withdrawn), is listed as RETURN_NOT_REFUNDED with what it would give
     * back: the stock came back, the money did not. The stock a cancelled fill puts back is not a patient return
     * (its payment is voided), and a return of a transfer names no patient.
     */
    private void unrefundedReturns(UUID pharmacyId, List<StockTransaction> movements, List<Difference> differences) {
        List<StockTransaction> returns = movements.stream()
                .filter(tx -> tx.getTransactionType() == StockTransactionType.RETURN && tx.getReferenceId() != null)
                .toList();
        if (returns.isEmpty()) {
            return;
        }
        Set<UUID> handled = new java.util.HashSet<>(nzList(refundRepository.findRefundedReturnIds(
                returns.stream().map(StockTransaction::getId).toList(), RETURN_HANDLED)));
        for (StockTransaction ret : returns) {
            if (handled.contains(ret.getId())) {
                continue;
            }
            BigDecimal owed = refundOwed(pharmacyId, ret);
            if (owed.signum() > 0) {
                differences.add(Difference.builder().type("RETURN_NOT_REFUNDED")
                        .medicationName(nameOf(ret.getInventoryItem()))
                        .lotNumber(ret.getStockLot() != null ? ret.getStockLot().getLotNumber() : null)
                        .referenceId(ret.getId()).expected(owed).actual(BigDecimal.ZERO).amount(owed).build());
            }
        }
    }

    /**
     * What a return of a paid sale or fill would give back; zero when it names neither. Both are read by id AND
     * pharmacy: a reference to another pharmacy's (or hospital's) row answers as naming nothing.
     */
    private BigDecimal refundOwed(UUID pharmacyId, StockTransaction ret) {
        PharmacySale sale = saleRepository.findByIdAndPharmacy_Id(ret.getReferenceId(), pharmacyId).orElse(null);
        if (sale != null) {
            if (sale.getStatus() != PharmacySaleStatus.COMPLETED) {
                return BigDecimal.ZERO;
            }
            BigDecimal linePrice = sale.getLines().stream().filter(line -> PharmacyRefundService.sameStock(line, ret))
                    .map(SaleLine::getUnitPrice).filter(Objects::nonNull).findFirst().orElse(null);
            BigDecimal left = nz(sale.getTotalAmount())
                    .subtract(nz(refundRepository.sumForSale(sale.getId(), PharmacyRefundService.LIVE)));
            return PharmacyRefundAmount.ofSaleLine(linePrice, ret, sale.getCreatedAt()).min(left).max(BigDecimal.ZERO);
        }
        Dispense fill = dispenseRepository.findByIdAndPharmacy_Id(ret.getReferenceId(), pharmacyId).orElse(null);
        if (fill == null || fill.getStatus() == com.example.hms.enums.DispenseStatus.CANCELLED) {
            return BigDecimal.ZERO;
        }
        BigDecimal paid = nz(paymentRepository.sumPostedForDispense(fill.getId()));
        BigDecimal left = paid.subtract(nz(refundRepository.sumForDispense(fill.getId(), PharmacyRefundService.LIVE)));
        return PharmacyRefundAmount.ofDispense(paid, fill.getQuantityDispensed(), ret, fill.getCreatedAt())
                .min(left).max(BigDecimal.ZERO);
    }

    private static List<UUID> nzList(List<UUID> ids) {
        return ids != null ? ids : List.of();
    }

    // ───────────────────────────── stock ─────────────────────────────

    private static final class Bucket {
        InventoryItem item;
        StockLot lot;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal soldRecorded = BigDecimal.ZERO;
        BigDecimal soldMoved = BigDecimal.ZERO;
        BigDecimal dispensedRecorded = BigDecimal.ZERO;
        BigDecimal dispensedMoved = BigDecimal.ZERO;
        BigDecimal returned = BigDecimal.ZERO;
        BigDecimal adjusted = BigDecimal.ZERO;
        BigDecimal transferred = BigDecimal.ZERO;
    }

    private static String keyOf(InventoryItem item, StockLot lot) {
        if (lot != null) {
            return "lot:" + lot.getId();
        }
        return "item:" + (item != null ? item.getId() : "?");
    }

    private static InventoryItem itemOrLotItem(InventoryItem item, StockLot lot) {
        if (item != null) {
            return item;
        }
        return lot != null ? lot.getInventoryItem() : null;
    }

    private static Bucket bucket(Map<String, Bucket> buckets, InventoryItem item, StockLot lot) {
        return buckets.computeIfAbsent(keyOf(item, lot), k -> {
            Bucket b = new Bucket();
            b.item = itemOrLotItem(item, lot);
            b.lot = lot;
            return b;
        });
    }

    private List<StockLine> stockLines(List<PharmacySale> sales, List<Dispense> fills,
                                       List<StockTransaction> movements, List<Difference> differences) {
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        movements.forEach(tx -> moved(bucket(buckets, tx.getInventoryItem(), tx.getStockLot()), tx));
        for (PharmacySale sale : sales) {
            for (SaleLine line : sale.getLines()) {
                InventoryItem item = line.getStockLot() != null ? line.getStockLot().getInventoryItem()
                        : itemOf(sale.getPharmacy(), line.getMedicationCatalogItem());
                Bucket b = bucket(buckets, item, line.getStockLot());
                b.soldRecorded = b.soldRecorded.add(nz(line.getQuantity()));
            }
        }
        // A fill with no lot (a pharmacy that keeps no stock) moves nothing and is not expected to.
        fills.stream().filter(fill -> fill.getStockLot() != null).forEach(fill -> {
            Bucket b = bucket(buckets, fill.getStockLot().getInventoryItem(), fill.getStockLot());
            b.dispensedRecorded = b.dispensedRecorded.add(nz(fill.getQuantityDispensed()));
        });
        List<StockLine> lines = new ArrayList<>();
        for (Bucket b : buckets.values()) {
            lines.add(lineOf(b, differences));
        }
        lines.sort(java.util.Comparator.comparing((StockLine l) -> Objects.toString(l.getMedicationName(), ""))
                .thenComparing(l -> Objects.toString(l.getLotNumber(), "")));
        return lines;
    }

    /** One movement counted in its bucket. */
    private static void moved(Bucket b, StockTransaction tx) {
        BigDecimal q = nz(tx.getQuantity());
        switch (tx.getTransactionType()) {
            case RECEIPT -> b.received = b.received.add(q);
            case SALE -> b.soldMoved = b.soldMoved.add(q);
            case DISPENSE -> b.dispensedMoved = b.dispensedMoved.add(q);
            case RETURN -> b.returned = b.returned.add(q);
            case ADJUSTMENT -> b.adjusted = b.adjusted.add(q);
            case TRANSFER -> b.transferred = b.transferred.add(q);
        }
    }

    /** A bucket as a stock line; a sale or fill whose movement differs is a difference. */
    private static StockLine lineOf(Bucket b, List<Difference> differences) {
        String name = nameOf(b.item);
        String lotNumber = b.lot != null ? b.lot.getLotNumber() : null;
        boolean saleOff = b.soldRecorded.compareTo(b.soldMoved) != 0;
        boolean fillOff = b.dispensedRecorded.compareTo(b.dispensedMoved) != 0;
        if (saleOff) {
            differences.add(Difference.builder().type("SALE_STOCK_MISMATCH").medicationName(name)
                    .lotNumber(lotNumber).expected(b.soldRecorded).actual(b.soldMoved).build());
        }
        if (fillOff) {
            differences.add(Difference.builder().type("DISPENSE_STOCK_MISMATCH").medicationName(name)
                    .lotNumber(lotNumber).expected(b.dispensedRecorded).actual(b.dispensedMoved).build());
        }
        return StockLine.builder()
                .inventoryItemId(b.item != null ? b.item.getId() : null)
                .medicationName(name)
                .unit(b.item != null ? b.item.getUnit() : null)
                .stockLotId(b.lot != null ? b.lot.getId() : null)
                .lotNumber(lotNumber)
                .received(b.received)
                .soldRecorded(b.soldRecorded)
                .soldMoved(b.soldMoved)
                .dispensedRecorded(b.dispensedRecorded)
                .dispensedMoved(b.dispensedMoved)
                .returned(b.returned)
                .adjusted(b.adjusted)
                .transferred(b.transferred)
                .difference(saleOff || fillOff)
                .build();
    }

    private InventoryItem itemOf(Pharmacy pharmacy, MedicationCatalogItem medication) {
        if (pharmacy == null || medication == null) {
            return null;
        }
        return inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(pharmacy.getId(), medication.getId())
                .orElse(null);
    }

    /**
     * QA D2671: a sale line priced away from the pharmacy's list price is listed. Review of #1239: the list price is
     * the one in force WHEN THE SALE WAS MADE ({@link PharmacyPriceHistory#priceAt}); compared with today's, every
     * past sale turned into a difference the day a price changed, and a sale under the price of its day went unseen
     * once the price was lowered to it.
     */
    private void priceDifferences(List<PharmacySale> sales, List<Difference> differences) {
        for (PharmacySale sale : sales) {
            LocalDateTime soldAt = sale.getSaleDate() != null ? sale.getSaleDate() : sale.getCreatedAt();
            for (SaleLine line : sale.getLines()) {
                priceDifference(sale, line, soldAt, differences);
            }
        }
    }

    /** One sale line against the list price of its item when the sale was made. */
    private void priceDifference(PharmacySale sale, SaleLine line, LocalDateTime soldAt,
                                 List<Difference> differences) {
        if (line.getMedicationCatalogItem() == null || line.getUnitPrice() == null) {
            return;
        }
        StockLot lot = line.getStockLot();
        InventoryItem item = lot != null && lot.getInventoryItem() != null
                ? lot.getInventoryItem() : itemOf(sale.getPharmacy(), line.getMedicationCatalogItem());
        BigDecimal list = item == null ? null : priceHistory.priceAt(item, soldAt).orElse(null);
        if (list == null || list.compareTo(line.getUnitPrice()) == 0) {
            return;
        }
        differences.add(Difference.builder().type("PRICE_NOT_LIST")
                .medicationName(medicationName(line.getMedicationCatalogItem()))
                .lotNumber(lot != null ? lot.getLotNumber() : null)
                .referenceId(sale.getId()).expected(list).actual(line.getUnitPrice())
                .amount(PharmacyListPrice.amount(list.subtract(line.getUnitPrice()), nz(line.getQuantity())))
                .build());
    }

    // ───────────────────────────── money ─────────────────────────────

    private Money money(List<PharmacySale> sales, List<PharmacyPayment> payments, List<PharmacyRefund> refunds,
                        List<Dispense> handedOver, List<DeferredFill> deferred, List<Difference> differences) {
        Map<String, BigDecimal> salesByMethod = new TreeMap<>();
        BigDecimal salesTotal = BigDecimal.ZERO;
        for (PharmacySale sale : sales) {
            salesTotal = salesTotal.add(nz(sale.getTotalAmount()));
            salesByMethod.merge(String.valueOf(sale.getPaymentMethod()), nz(sale.getTotalAmount()), BigDecimal::add);
        }
        Map<String, BigDecimal> paymentsByMethod = new TreeMap<>();
        BigDecimal paymentsTotal = BigDecimal.ZERO;
        for (PharmacyPayment payment : payments) {
            paymentsTotal = paymentsTotal.add(nz(payment.getAmount()));
            paymentsByMethod.merge(String.valueOf(payment.getPaymentMethod()), nz(payment.getAmount()), BigDecimal::add);
        }
        BigDecimal refundsPaid = refunds.stream().map(PharmacyRefund::getAmount).map(PharmacyReconciliationService::nz)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Settlement settled = new Settlement();
        handedOver.forEach(fill -> settle(fill, settled, deferred, differences));
        return Money.builder()
                .salesCount(sales.size())
                .salesTotal(salesTotal)
                .salesByMethod(salesByMethod)
                .dispensePaymentsCount(payments.size())
                .dispensePaymentsTotal(paymentsTotal)
                .dispensePaymentsByMethod(paymentsByMethod)
                .refundsCount(refunds.size())
                .refundsPaid(refundsPaid)
                .netCollected(salesTotal.add(paymentsTotal).subtract(refundsPaid))
                .dispenseCharges(settled.charges)
                .deferredTotal(settled.deferredTotal)
                .unpaidTotal(settled.unpaidTotal)
                .build();
    }

    /** What the fills handed over cost, and what of it left « à facturer » or unpaid. */
    private static final class Settlement {
        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal deferredTotal = BigDecimal.ZERO;
        BigDecimal unpaidTotal = BigDecimal.ZERO;
    }

    /**
     * QA D2675: a fill handed over with money still due is listed « à facturer » when it was marked so, and is a
     * difference otherwise.
     */
    private void settle(Dispense fill, Settlement settled, List<DeferredFill> deferred, List<Difference> differences) {
        BigDecimal charge = DispenseCharge.of(fill, inventoryItemRepository).orElse(BigDecimal.ZERO);
        if (charge.signum() <= 0) {
            return;
        }
        settled.charges = settled.charges.add(charge);
        // Review of #1239: a DRAFT claim was never sent to a payer and covers nothing.
        BigDecimal covered = nz(paymentRepository.sumPostedForDispense(fill.getId()))
                .add(nz(claimRepository.sumSentForDispense(fill.getId())));
        BigDecimal due = charge.subtract(covered).max(BigDecimal.ZERO);
        if (due.signum() <= 0) {
            return;
        }
        if (fill.leftOnDeferredBilling()) {
            settled.deferredTotal = settled.deferredTotal.add(due);
            deferred.add(DeferredFill.builder().dispenseId(fill.getId()).medicationName(fill.getMedicationName())
                    .quantity(fill.getQuantityDispensed()).due(due).reason(fill.getBillingDeferredReason())
                    .deferredAt(fill.getBillingDeferredAt()).deferralId(fill.getBillingDeferralId()).build());
        } else {
            settled.unpaidTotal = settled.unpaidTotal.add(due);
            differences.add(Difference.builder().type("UNPAID_HANDED_OVER").medicationName(fill.getMedicationName())
                    .lotNumber(fill.getStockLot() != null ? fill.getStockLot().getLotNumber() : null)
                    .referenceId(fill.getId()).expected(charge).actual(covered).amount(due).build());
        }
    }

    /** A fill marked « à facturer » before policy rule 6: a reason and nobody who approved it. */
    static final String KIND_UNAPPROVED = "UNAPPROVED";

    /**
     * Policy rule 6 (V345): the reconciliation names the deferral behind every « à facturer » fill: its kind, who
     * asked, who approved, who handed it over, its limit or voucher, and whether an urgency override was reviewed.
     * A fill with no deferral row (marked before the rule) is listed as UNAPPROVED. An optional collaborator: a
     * service built by hand in a unit test has none and lists every fill as before.
     */
    private DispenseBillingDeferralService billingDeferrals;

    @org.springframework.beans.factory.annotation.Autowired
    public void setBillingDeferrals(DispenseBillingDeferralService billingDeferrals) {
        this.billingDeferrals = billingDeferrals;
    }

    private void describeDeferrals(UUID hospitalId, List<DeferredFill> deferred, Money money) {
        BigDecimal overrides = BigDecimal.ZERO;
        BigDecimal stays = BigDecimal.ZERO;
        Map<UUID, com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDTO> byId = billingDeferrals == null
                ? Map.of()
                : billingDeferrals.byIds(hospitalId, deferred.stream().map(DeferredFill::getDeferralId)
                        .filter(java.util.Objects::nonNull).distinct().toList());
        for (DeferredFill fill : deferred) {
            com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDTO d =
                    fill.getDeferralId() == null ? null : byId.get(fill.getDeferralId());
            if (d == null) {
                // Marked before the rule, or a deferral this hospital does not hold: nobody approved it here.
                fill.setDeferralId(null);
                fill.setKind(KIND_UNAPPROVED);
                continue;
            }
            fill.setKind(d.getKind());
            fill.setRequestedByName(d.getRequestedByName());
            fill.setApprovedByName(d.getDecidedByName());
            fill.setHandedOverByName(d.getExecutedByName());
            fill.setCoverReference(d.getCoverReference());
            fill.setCreditLimit(d.getCreditLimit());
            fill.setApprovalNote(d.getDecisionNote());
            if (com.example.hms.enums.DispenseBillingDeferralKind.URGENCY_OVERRIDE.name().equals(d.getKind())) {
                fill.setReviewed(d.getReviewedAt() != null);
                fill.setReviewedByName(d.getReviewedByName());
                overrides = overrides.add(nz(fill.getDue()));
            } else if (com.example.hms.enums.DispenseBillingDeferralKind.ADMISSION_ACCOUNT.name().equals(d.getKind())) {
                stays = stays.add(nz(fill.getDue()));
            }
        }
        money.setUrgencyOverrideTotal(overrides);
        money.setAdmissionAccountTotal(stays);
    }

    // ───────────────────────────── controlled ─────────────────────────────

    /** Every controlled item of the pharmacy: the register's running balance against the lots held now. */
    private List<ControlledBalance> controlled(UUID pharmacyId, List<Difference> differences) {
        List<InventoryItem> items = inventoryItemRepository.findAllWithCatalogItemByPharmacyId(pharmacyId).stream()
                .filter(ControlledStockWitness::isControlled).toList();
        if (items.isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> register = new LinkedHashMap<>();
        for (Object[] row : stockTransactionRepository.sumByItemAndTypeBefore(
                items.stream().map(InventoryItem::getId).toList(), LocalDateTime.now(clock).plusSeconds(1))) {
            UUID itemId = (UUID) row[0];
            StockTransactionType type = (StockTransactionType) row[1];
            register.merge(itemId, ControlledDrugRegisterService.signed(type, (BigDecimal) row[2]), BigDecimal::add);
        }
        List<ControlledBalance> out = new ArrayList<>();
        for (InventoryItem item : items) {
            BigDecimal held = stockLotRepository.existsByInventoryItemId(item.getId())
                    ? stockLotRepository.findAllByInventoryItemIdOrderByExpiryDateAsc(item.getId()).stream()
                        .map(StockLot::getRemainingQuantity).map(PharmacyReconciliationService::nz)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                    : nz(item.getQuantityOnHand());
            BigDecimal balance = register.getOrDefault(item.getId(), BigDecimal.ZERO);
            boolean matches = balance.compareTo(held) == 0;
            String name = nameOf(item);
            if (!matches) {
                differences.add(Difference.builder().type("CONTROLLED_BALANCE").medicationName(name)
                        .expected(balance).actual(held).build());
            }
            out.add(ControlledBalance.builder().inventoryItemId(item.getId()).medicationName(name)
                    .registerBalance(balance).stockBalance(held).matches(matches).build());
        }
        return out;
    }

    // ───────────────────────────── export ─────────────────────────────

    /** The day as CSV (';' separated, UTF-8 with BOM), every cell neutralised against formulas. */
    @Transactional(readOnly = true)
    public byte[] exportCsv(UUID pharmacyId, LocalDate date) {
        PharmacyReconciliationDTO day = build(pharmacyId, date);
        StringBuilder csv = new StringBuilder(BOM);
        Money m = day.getMoney();
        csv.append(row("section", "libelle", "valeur")).append("\r\n");
        csv.append(row(MONEY_SECTION, "ventes_comptoir", plain(m.getSalesTotal()))).append("\r\n");
        m.getSalesByMethod().forEach((k, v) -> csv.append(row(MONEY_SECTION, "ventes_" + k, plain(v))).append("\r\n"));
        csv.append(row(MONEY_SECTION, "paiements_delivrances", plain(m.getDispensePaymentsTotal()))).append("\r\n");
        m.getDispensePaymentsByMethod().forEach((k, v) ->
                csv.append(row(MONEY_SECTION, "paiements_" + k, plain(v))).append("\r\n"));
        csv.append(row(MONEY_SECTION, "remboursements", plain(m.getRefundsPaid()))).append("\r\n");
        csv.append(row(MONEY_SECTION, "net_encaisse", plain(m.getNetCollected()))).append("\r\n");
        csv.append(row(MONEY_SECTION, CSV_DEFERRED, plain(m.getDeferredTotal()))).append("\r\n");
        csv.append(row(MONEY_SECTION, "remis_non_payes", plain(m.getUnpaidTotal()))).append("\r\n");
        csv.append(row(MONEY_SECTION, "a_facturer_urgence", plain(m.getUrgencyOverrideTotal()))).append("\r\n");
        csv.append(row(MONEY_SECTION, "a_facturer_compte_hospitalisation", plain(m.getAdmissionAccountTotal())))
                .append("\r\n");
        // Policy rule 6: every « à facturer » fill of the day with the deferral behind it.
        csv.append("\r\n").append(row(CSV_DEFERRED, CSV_MEDICATION, "quantite", "du", "type", "motif", "reference",
                "plafond", "demande_par", "approuve_par", "remis_par", "revue")).append("\r\n");
        for (DeferredFill f : day.getDeferred()) {
            csv.append(row(CSV_DEFERRED, f.getMedicationName(), plain(f.getQuantity()), plain(f.getDue()), f.getKind(),
                    f.getReason(), f.getCoverReference(), plain(f.getCreditLimit()), f.getRequestedByName(),
                    f.getApprovedByName(), f.getHandedOverByName(), reviewedCell(f))).append("\r\n");
        }
        csv.append("\r\n").append(row(CSV_MEDICATION, "lot", "unite", "recu", "vendu_enregistre", "vendu_mouvement",
                "delivre_enregistre", "delivre_mouvement", "retour", "ajustement", "transfert", CSV_DIFFERENCE)).append("\r\n");
        for (StockLine l : day.getStock()) {
            csv.append(row(l.getMedicationName(), l.getLotNumber(), l.getUnit(), plain(l.getReceived()),
                    plain(l.getSoldRecorded()), plain(l.getSoldMoved()), plain(l.getDispensedRecorded()),
                    plain(l.getDispensedMoved()), plain(l.getReturned()), plain(l.getAdjusted()),
                    plain(l.getTransferred()), l.isDifference() ? "oui" : "non")).append("\r\n");
        }
        csv.append("\r\n").append(row(CSV_DIFFERENCE, CSV_MEDICATION, "lot", "reference", "attendu", "constate", "montant"))
                .append("\r\n");
        for (Difference d : day.getDifferences()) {
            csv.append(row(d.getType(), d.getMedicationName(), d.getLotNumber(),
                    d.getReferenceId() != null ? d.getReferenceId().toString() : "", plain(d.getExpected()),
                    plain(d.getActual()), plain(d.getAmount()))).append("\r\n");
        }
        csv.append("\r\n").append(row("solde_lot", CSV_MEDICATION, "lot", "ouverture", "mouvements", "cloture", CSV_DIFFERENCE))
                .append("\r\n");
        for (LotBalance l : day.getLotBalances()) {
            csv.append(row("solde_lot", l.getMedicationName(), l.getLotNumber(), plain(l.getOpening()),
                    plain(l.getMovements()), plain(l.getClosing()), l.isMatches() ? "non" : "oui")).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Whether an urgency override was reviewed; empty for every other kind. */
    private static String reviewedCell(DeferredFill fill) {
        if (fill.getReviewed() == null) {
            return "";
        }
        return Boolean.TRUE.equals(fill.getReviewed()) ? "oui" : "non";
    }

    /** The export's file name for that day. */
    public static String csvFileName(LocalDate date) {
        return CSV_FILE_PREFIX + date + ".csv";
    }

    /** One CSV row; each cell neutralised (CsvCells.safe) then quoted. */
    private static String row(Object... values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(';');
            }
            out.append(quoted(values[i] == null ? "" : String.valueOf(values[i])));
        }
        return out.toString();
    }

    private static String quoted(String value) {
        return "\"" + CsvCells.safe(value).replace("\"", "\"\"") + "\"";
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    // ───────────────────────────── values ─────────────────────────────

    private static String nameOf(InventoryItem item) {
        return item == null ? null : medicationName(item.getMedicationCatalogItem());
    }

    private static String medicationName(MedicationCatalogItem medication) {
        if (medication == null) {
            return null;
        }
        return medication.getNameFr() != null ? medication.getNameFr() : medication.getGenericName();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
