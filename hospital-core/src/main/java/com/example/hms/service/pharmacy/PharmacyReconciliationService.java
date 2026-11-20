package com.example.hms.service.pharmacy;

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
    private final RoleValidator roleValidator;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PharmacyReconciliationDTO daily(UUID pharmacyId, LocalDate date) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        UUID userId = roleValidator.getCurrentUserId();
        if (hospitalId == null || userId == null || !roleValidator.canReadPharmacyReconciliation(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("pharmacy.reconciliation.readersOnly"));
        }
        Pharmacy pharmacy = pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));
        LocalDate day = date != null ? date : LocalDate.now(clock);
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
        priceDifferences(sales, pharmacyId, differences);
        List<DeferredFill> deferred = new ArrayList<>();
        Money money = money(sales, payments, refunds, handedOver, deferred, differences);
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
                .build();
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
        return lot != null ? "lot:" + lot.getId() : "item:" + (item != null ? item.getId() : "?");
    }

    private static Bucket bucket(Map<String, Bucket> buckets, InventoryItem item, StockLot lot) {
        return buckets.computeIfAbsent(keyOf(item, lot), k -> {
            Bucket b = new Bucket();
            b.item = item != null ? item : (lot != null ? lot.getInventoryItem() : null);
            b.lot = lot;
            return b;
        });
    }

    private List<StockLine> stockLines(List<PharmacySale> sales, List<Dispense> fills,
                                       List<StockTransaction> movements, List<Difference> differences) {
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (StockTransaction tx : movements) {
            Bucket b = bucket(buckets, tx.getInventoryItem(), tx.getStockLot());
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
        for (PharmacySale sale : sales) {
            for (SaleLine line : sale.getLines()) {
                InventoryItem item = line.getStockLot() != null ? line.getStockLot().getInventoryItem()
                        : itemOf(sale.getPharmacy(), line.getMedicationCatalogItem());
                Bucket b = bucket(buckets, item, line.getStockLot());
                b.soldRecorded = b.soldRecorded.add(nz(line.getQuantity()));
            }
        }
        for (Dispense fill : fills) {
            // A fill with no lot (a pharmacy that keeps no stock) moves nothing and is not expected to.
            if (fill.getStockLot() == null) {
                continue;
            }
            Bucket b = bucket(buckets, fill.getStockLot().getInventoryItem(), fill.getStockLot());
            b.dispensedRecorded = b.dispensedRecorded.add(nz(fill.getQuantityDispensed()));
        }
        List<StockLine> lines = new ArrayList<>();
        for (Bucket b : buckets.values()) {
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
            lines.add(StockLine.builder()
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
                    .build());
        }
        lines.sort(java.util.Comparator.comparing((StockLine l) -> Objects.toString(l.getMedicationName(), ""))
                .thenComparing(l -> Objects.toString(l.getLotNumber(), "")));
        return lines;
    }

    private InventoryItem itemOf(Pharmacy pharmacy, MedicationCatalogItem medication) {
        if (pharmacy == null || medication == null) {
            return null;
        }
        return inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(pharmacy.getId(), medication.getId())
                .orElse(null);
    }

    /** QA D2671: a sale line priced away from the pharmacy's list price (today's) is listed. */
    private void priceDifferences(List<PharmacySale> sales, UUID pharmacyId, List<Difference> differences) {
        for (PharmacySale sale : sales) {
            for (SaleLine line : sale.getLines()) {
                if (line.getMedicationCatalogItem() == null || line.getUnitPrice() == null) {
                    continue;
                }
                BigDecimal list = PharmacyListPrice.of(pharmacyId, line.getMedicationCatalogItem().getId(),
                        inventoryItemRepository).orElse(null);
                if (list != null && list.compareTo(line.getUnitPrice()) != 0) {
                    differences.add(Difference.builder().type("PRICE_NOT_LIST")
                            .medicationName(medicationName(line.getMedicationCatalogItem()))
                            .lotNumber(line.getStockLot() != null ? line.getStockLot().getLotNumber() : null)
                            .referenceId(sale.getId()).expected(list).actual(line.getUnitPrice())
                            .amount(PharmacyListPrice.amount(list.subtract(line.getUnitPrice()), nz(line.getQuantity())))
                            .build());
                }
            }
        }
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

        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal deferredTotal = BigDecimal.ZERO;
        BigDecimal unpaidTotal = BigDecimal.ZERO;
        for (Dispense fill : handedOver) {
            BigDecimal charge = DispenseCharge.of(fill, inventoryItemRepository).orElse(BigDecimal.ZERO);
            if (charge.signum() <= 0) {
                continue;
            }
            charges = charges.add(charge);
            BigDecimal paid = nz(paymentRepository.sumPostedForDispense(fill.getId()));
            BigDecimal claimed = nz(claimRepository.sumLiveForDispense(fill.getId()));
            BigDecimal due = charge.subtract(paid).subtract(claimed).max(BigDecimal.ZERO);
            if (due.signum() <= 0) {
                continue;
            }
            String name = fill.getMedicationName();
            if (fill.getBillingDeferredReason() != null) {
                deferredTotal = deferredTotal.add(due);
                deferred.add(DeferredFill.builder().dispenseId(fill.getId()).medicationName(name)
                        .quantity(fill.getQuantityDispensed()).due(due).reason(fill.getBillingDeferredReason())
                        .deferredAt(fill.getBillingDeferredAt()).build());
            } else {
                unpaidTotal = unpaidTotal.add(due);
                differences.add(Difference.builder().type("UNPAID_HANDED_OVER").medicationName(name)
                        .lotNumber(fill.getStockLot() != null ? fill.getStockLot().getLotNumber() : null)
                        .referenceId(fill.getId()).expected(charge).actual(paid.add(claimed)).amount(due).build());
            }
        }
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
                .dispenseCharges(charges)
                .deferredTotal(deferredTotal)
                .unpaidTotal(unpaidTotal)
                .build();
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
        PharmacyReconciliationDTO day = daily(pharmacyId, date);
        StringBuilder csv = new StringBuilder("﻿");
        Money m = day.getMoney();
        csv.append(row("section", "libelle", "valeur")).append("\r\n");
        csv.append(row("argent", "ventes_comptoir", plain(m.getSalesTotal()))).append("\r\n");
        m.getSalesByMethod().forEach((k, v) -> csv.append(row("argent", "ventes_" + k, plain(v))).append("\r\n"));
        csv.append(row("argent", "paiements_delivrances", plain(m.getDispensePaymentsTotal()))).append("\r\n");
        m.getDispensePaymentsByMethod().forEach((k, v) ->
                csv.append(row("argent", "paiements_" + k, plain(v))).append("\r\n"));
        csv.append(row("argent", "remboursements", plain(m.getRefundsPaid()))).append("\r\n");
        csv.append(row("argent", "net_encaisse", plain(m.getNetCollected()))).append("\r\n");
        csv.append(row("argent", "a_facturer", plain(m.getDeferredTotal()))).append("\r\n");
        csv.append(row("argent", "remis_non_payes", plain(m.getUnpaidTotal()))).append("\r\n");
        csv.append("\r\n").append(row("medicament", "lot", "unite", "recu", "vendu_enregistre", "vendu_mouvement",
                "delivre_enregistre", "delivre_mouvement", "retour", "ajustement", "transfert", "ecart")).append("\r\n");
        for (StockLine l : day.getStock()) {
            csv.append(row(l.getMedicationName(), l.getLotNumber(), l.getUnit(), plain(l.getReceived()),
                    plain(l.getSoldRecorded()), plain(l.getSoldMoved()), plain(l.getDispensedRecorded()),
                    plain(l.getDispensedMoved()), plain(l.getReturned()), plain(l.getAdjusted()),
                    plain(l.getTransferred()), l.isDifference() ? "oui" : "non")).append("\r\n");
        }
        csv.append("\r\n").append(row("ecart", "medicament", "lot", "reference", "attendu", "constate", "montant"))
                .append("\r\n");
        for (Difference d : day.getDifferences()) {
            csv.append(row(d.getType(), d.getMedicationName(), d.getLotNumber(),
                    d.getReferenceId() != null ? d.getReferenceId().toString() : "", plain(d.getExpected()),
                    plain(d.getActual()), plain(d.getAmount()))).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
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
