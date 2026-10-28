package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.ControlledRegisterDTO;
import com.example.hms.payload.dto.pharmacy.ControlledRegisterDrugDTO;
import com.example.hms.payload.dto.pharmacy.ControlledRegisterEntryDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D967: the controlled-drug (stupéfiants) register. Only the general stock
 * ledger existed - no medicine column, no patient or prescriber, no balance -
 * so a morphine inspection had nothing to read.
 *
 * <p>A read view on existing data: every ledger movement (receipt, dispense,
 * return, adjustment, transfer) of the pharmacy's items whose catalogue entry
 * is controlled, per drug, oldest first, with the running balance the ledger
 * gives (opening balance = every movement before the period), the patient and
 * prescriber of a dispense (and of a return booked against one), who recorded
 * it and the witness. Scoped to the caller's hospital like the ledger.
 * Exported as CSV and PDF in the request's language.
 */
@Service
@RequiredArgsConstructor
public class ControlledDrugRegisterService {

    /** The most movements one read returns; a longer period is asked to be split. */
    static final int MAX_ROWS = 5000;
    /** The longest period one read covers. */
    static final long MAX_DAYS = 366;

    private static final Pattern PRESCRIPTION_IN_REASON = Pattern.compile(
            "for prescription ([0-9a-fA-F-]{36})");

    private final PharmacyRepository pharmacyRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final DispenseRepository dispenseRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final RoleValidator roleValidator;

    @Transactional(readOnly = true)
    public ControlledRegisterDTO register(UUID pharmacyId, UUID inventoryItemId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException("pharmacy.register.periodInvalid");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new BusinessException("pharmacy.register.periodTooLong", String.valueOf(MAX_DAYS));
        }
        Pharmacy pharmacy = pharmacyInScope(pharmacyId);
        List<InventoryItem> drugs = inventoryItemRepository.findAllWithCatalogItemByPharmacyId(pharmacy.getId())
                .stream()
                .filter(ControlledStockWitness::isControlled)
                .filter(item -> inventoryItemId == null || inventoryItemId.equals(item.getId()))
                .sorted(Comparator.comparing(ControlledDrugRegisterService::drugName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        if (inventoryItemId != null && drugs.isEmpty()) {
            // Another pharmacy's item, or one that is not controlled: same answer as a missing one.
            throw new ResourceNotFoundException("inventoryItem.notFound", inventoryItemId);
        }
        if (drugs.isEmpty()) {
            return new ControlledRegisterDTO(pharmacy.getId(), pharmacy.getName(), from, to, false, List.of());
        }
        List<UUID> itemIds = drugs.stream().map(InventoryItem::getId).toList();
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();

        Map<UUID, BigDecimal> opening = new HashMap<>();
        List<Object[]> sums = stockTransactionRepository.sumByItemAndTypeBefore(itemIds, start);
        if (sums != null) {
            for (Object[] row : sums) {
                if (row != null && row.length == 3 && row[0] instanceof UUID id
                        && row[1] instanceof StockTransactionType type && row[2] instanceof BigDecimal total) {
                    opening.merge(id, signed(type, total), BigDecimal::add);
                }
            }
        }
        List<StockTransaction> rows = stockTransactionRepository.findRegisterRows(itemIds, start, end,
                PageRequest.of(0, MAX_ROWS + 1));
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_ROWS);
        }
        People people = people(rows);

        Map<UUID, List<ControlledRegisterEntryDTO>> entries = new LinkedHashMap<>();
        Map<UUID, BigDecimal> balance = new HashMap<>(opening);
        for (StockTransaction tx : rows) {
            UUID itemId = tx.getInventoryItem().getId();
            BigDecimal moved = signed(tx.getTransactionType(), tx.getQuantity());
            BigDecimal after = balance.getOrDefault(itemId, BigDecimal.ZERO).add(moved);
            balance.put(itemId, after);
            Prescription prescription = people.prescriptionOf(tx);
            entries.computeIfAbsent(itemId, k -> new ArrayList<>()).add(new ControlledRegisterEntryDTO(
                    tx.getId(),
                    tx.getCreatedAt(),
                    tx.getTransactionType() != null ? tx.getTransactionType().name() : null,
                    moved,
                    tx.getStockLot() != null ? tx.getStockLot().getLotNumber() : tx.getLotNumber(),
                    after,
                    prescription != null ? patientName(prescription.getPatient()) : null,
                    prescription != null ? prescriberName(prescription.getStaff()) : null,
                    userName(tx.getPerformedByUser()),
                    userName(tx.getWitnessedByUser()),
                    reasonText(LocaleContextHolder.getLocale(), tx.getReason())));
        }
        // Review of #975: the shelf count is today's; it is only comparable
        // with a closing balance carried to today, and a truncated register
        // has no closing balance at all (its last row is not the period's).
        boolean coversToday = !to.isBefore(LocalDate.now(java.time.ZoneId.systemDefault()));
        List<ControlledRegisterDrugDTO> out = new ArrayList<>();
        for (InventoryItem item : drugs) {
            BigDecimal open = opening.getOrDefault(item.getId(), BigDecimal.ZERO);
            out.add(new ControlledRegisterDrugDTO(item.getId(), drugName(item), item.getUnit(), open,
                    truncated ? null : balance.getOrDefault(item.getId(), open),
                    coversToday && !truncated ? item.getQuantityOnHand() : null,
                    entries.getOrDefault(item.getId(), List.of())));
        }
        return new ControlledRegisterDTO(pharmacy.getId(), pharmacy.getName(), from, to, truncated, out);
    }

    /** The register as CSV (';' separated, UTF-8 with BOM so a spreadsheet reads the accents). */
    @Transactional(readOnly = true)
    public byte[] exportCsv(UUID pharmacyId, UUID inventoryItemId, LocalDate from, LocalDate to) {
        ControlledRegisterDTO register = register(pharmacyId, inventoryItemId, from, to);
        Locale locale = LocaleContextHolder.getLocale();
        DateTimeFormatter when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        StringBuilder csv = new StringBuilder("﻿");
        csv.append(String.join(";", headers(locale))).append("\r\n");
        for (ControlledRegisterDrugDTO drug : register.drugs()) {
            csv.append(cells(drug.medicationName(), "", label(locale, "pharmacy.register.opening"), "", "",
                    plain(drug.openingBalance()), "", "", "", "", "")).append("\r\n");
            for (ControlledRegisterEntryDTO e : drug.entries()) {
                csv.append(cells(drug.medicationName(),
                        e.occurredAt() != null ? when.format(e.occurredAt()) : "",
                        movementLabel(locale, e.movementType()), plain(e.quantity()), e.lotNumber(),
                        plain(e.balanceAfter()), e.patientName(), e.prescriberName(), e.recordedBy(),
                        e.witnessedBy(), e.reason())).append("\r\n");
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The register as a landscape PDF, one table per drug, for inspection. */
    @Transactional(readOnly = true)
    public byte[] exportPdf(UUID pharmacyId, UUID inventoryItemId, LocalDate from, LocalDate to) {
        ControlledRegisterDTO register = register(pharmacyId, inventoryItemId, from, to);
        Locale locale = LocaleContextHolder.getLocale();
        DateTimeFormatter day = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        DateTimeFormatter when = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale);
        try (PharmacyPdfWriter pdf = new PharmacyPdfWriter(true)) {
            pdf.paragraph(PharmacyPdfWriter.BOLD, 15f, label(locale, "pharmacy.register.title"));
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 10f, label(locale, "pharmacy.register.subtitle",
                    Objects.toString(register.pharmacyName(), ""), day.format(register.from()),
                    day.format(register.to())));
            if (register.truncated()) {
                pdf.paragraph(PharmacyPdfWriter.BOLD, 9f, label(locale, "pharmacy.register.truncated",
                        String.valueOf(MAX_ROWS)));
            }
            if (register.drugs().isEmpty()) {
                pdf.space(8f);
                pdf.paragraph(PharmacyPdfWriter.REGULAR, 10f, label(locale, "pharmacy.register.noDrugs"));
            }
            float w = pdf.contentWidth();
            float[] widths = {w * 0.10f, w * 0.10f, w * 0.06f, w * 0.09f, w * 0.07f, w * 0.13f, w * 0.12f,
                w * 0.11f, w * 0.11f, w * 0.11f};
            for (ControlledRegisterDrugDTO drug : register.drugs()) {
                pdf.space(10f);
                pdf.paragraph(PharmacyPdfWriter.BOLD, 11f, drug.medicationName()
                        + (drug.unit() != null ? " (" + drug.unit() + ")" : ""));
                pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label(locale, "pharmacy.register.balances",
                        plain(drug.openingBalance()), plain(drug.closingBalance()), plain(drug.quantityOnHand())));
                List<String> head = headers(locale).subList(1, 11);
                pdf.tableHeader(widths, head.toArray(new String[0]));
                for (ControlledRegisterEntryDTO e : drug.entries()) {
                    pdf.tableRow(new String[] {
                        e.occurredAt() != null ? when.format(e.occurredAt()) : "",
                        movementLabel(locale, e.movementType()), plain(e.quantity()), nz(e.lotNumber()),
                        plain(e.balanceAfter()), nz(e.patientName()), nz(e.prescriberName()),
                        nz(e.recordedBy()), nz(e.witnessedBy()), nz(e.reason())});
                }
                if (drug.entries().isEmpty()) {
                    pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label(locale, "pharmacy.register.noMovement"));
                }
                pdf.endTable();
            }
            return pdf.finish();
        } catch (IOException e) {
            throw new UncheckedIOException("Controlled-drug register rendering failed", e);
        }
    }

    private Pharmacy pharmacyInScope(UUID pharmacyId) {
        Pharmacy pharmacy = pharmacyRepository.findById(pharmacyId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notFound", pharmacyId));
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && (pharmacy.getHospital() == null
                || !hospitalId.equals(pharmacy.getHospital().getId()))) {
            throw new ResourceNotFoundException("pharmacy.notFound", pharmacyId);
        }
        return pharmacy;
    }

    /** What came in is positive, what went out negative; an adjustment carries its own sign. */
    static BigDecimal signed(StockTransactionType type, BigDecimal quantity) {
        BigDecimal q = quantity == null ? BigDecimal.ZERO : quantity;
        if (type == null) {
            return BigDecimal.ZERO;
        }
        return switch (type) {
            case RECEIPT, RETURN, ADJUSTMENT -> q;
            case DISPENSE, TRANSFER -> q.negate();
        };
    }

    /** The prescriptions behind the dispenses and returns of the period, loaded in two queries. */
    private People people(List<StockTransaction> rows) {
        Set<UUID> prescriptionIds = new HashSet<>();
        Set<UUID> dispenseIds = new HashSet<>();
        for (StockTransaction tx : rows) {
            UUID fromReason = prescriptionIdIn(tx.getReason());
            if (fromReason != null) {
                prescriptionIds.add(fromReason);
            } else if (tx.getTransactionType() == StockTransactionType.RETURN && tx.getReferenceId() != null) {
                dispenseIds.add(tx.getReferenceId());
            }
        }
        Map<UUID, Prescription> byDispense = new HashMap<>();
        if (!dispenseIds.isEmpty()) {
            for (Dispense d : dispenseRepository.findAllById(dispenseIds)) {
                if (d.getPrescription() != null) {
                    byDispense.put(d.getId(), d.getPrescription());
                }
            }
        }
        Map<UUID, Prescription> byId = new HashMap<>();
        if (!prescriptionIds.isEmpty()) {
            for (Prescription p : prescriptionRepository.findAllById(prescriptionIds)) {
                byId.put(p.getId(), p);
            }
        }
        return new People(byId, byDispense);
    }

    /** Ledger rows name their prescription: "Dispense for prescription <id>" and its reversals. */
    static UUID prescriptionIdIn(String reason) {
        if (reason == null) {
            return null;
        }
        Matcher m = PRESCRIPTION_IN_REASON.matcher(reason);
        if (!m.find()) {
            return null;
        }
        try {
            return UUID.fromString(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record People(Map<UUID, Prescription> byId, Map<UUID, Prescription> byDispense) {
        Prescription prescriptionOf(StockTransaction tx) {
            UUID id = prescriptionIdIn(tx.getReason());
            if (id != null) {
                return byId.get(id);
            }
            return tx.getReferenceId() != null ? byDispense.get(tx.getReferenceId()) : null;
        }
    }

    private static List<String> headers(Locale locale) {
        return List.of(label(locale, "pharmacy.register.col.drug"), label(locale, "pharmacy.register.col.date"),
                label(locale, "pharmacy.register.col.movement"), label(locale, "pharmacy.register.col.quantity"),
                label(locale, "pharmacy.register.col.lot"), label(locale, "pharmacy.register.col.balance"),
                label(locale, "pharmacy.register.col.patient"), label(locale, "pharmacy.register.col.prescriber"),
                label(locale, "pharmacy.register.col.recordedBy"), label(locale, "pharmacy.register.col.witness"),
                label(locale, "pharmacy.register.col.reason"));
    }

    /** Adjustment codes the ledger stores (the form's and the API's spellings) and their label key. */
    private static final Map<String, String> ADJUSTMENT_CODES = Map.of(
            "DAMAGE", "DAMAGE", "DAMAGED", "DAMAGE",
            "EXPIRY_WRITE_OFF", "EXPIRY_WRITE_OFF", "EXPIRED", "EXPIRY_WRITE_OFF", "EXPIRY", "EXPIRY_WRITE_OFF",
            "CYCLE_COUNT_VARIANCE", "CYCLE_COUNT_VARIANCE",
            "CONTROLLED_DISCREPANCY", "CONTROLLED_DISCREPANCY");
    private static final Pattern ADJUSTMENT_REASON = Pattern.compile("^([A-Z_]+)(?::\\s*(.*))?$", Pattern.DOTALL);
    private static final String RECEIPT_PREFIX = "Stock receipt: lot ";
    private static final String RETURN_PREFIX = "RETURN ref ";
    private static final String TRANSFER_PREFIX = "TRANSFER to ";

    /**
     * QA D1000 in the register: the ledger stores its own reasons in English
     * ("Dispense for prescription <id>", "RETURN ref X", "DAMAGE: ...");
     * each known shape reads in the request's language, the typed words kept.
     */
    static String reasonText(Locale locale, String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String r = reason.trim();
        if (r.startsWith("Dispense for prescription")) {
            return label(locale, "pharmacy.register.reason.dispense");
        }
        if (r.startsWith("Dispense cancelled") || r.startsWith("Prepared fill cancelled")) {
            return label(locale, "pharmacy.register.reason.cancelled");
        }
        if (r.startsWith(RECEIPT_PREFIX)) {
            return label(locale, "pharmacy.register.reason.receipt", r.substring(RECEIPT_PREFIX.length()));
        }
        if (r.startsWith(RETURN_PREFIX)) {
            return label(locale, "pharmacy.register.reason.return", r.substring(RETURN_PREFIX.length()));
        }
        if (r.startsWith(TRANSFER_PREFIX)) {
            return label(locale, "pharmacy.register.reason.transfer", r.substring(TRANSFER_PREFIX.length()));
        }
        Matcher m = ADJUSTMENT_REASON.matcher(r);
        if (m.matches() && ADJUSTMENT_CODES.containsKey(m.group(1))) {
            String code = label(locale, "pharmacy.register.adjustment." + ADJUSTMENT_CODES.get(m.group(1)));
            return m.group(2) == null || m.group(2).isBlank() ? code : code + " : " + m.group(2).trim();
        }
        return r;
    }

    private static String movementLabel(Locale locale, String type) {
        return type == null ? "" : label(locale, "pharmacy.register.movement." + type);
    }

    private static String label(Locale locale, String key, Object... args) {
        return MessageUtil.resolve(locale, key, args);
    }

    private static String cells(String... values) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                row.append(';');
            }
            row.append(csvCell(values[i]));
        }
        return row.toString();
    }

    /** Quoted when needed; a leading formula character is neutralised (CSV injection). */
    static String csvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String v = value;
        char first = v.charAt(0);
        boolean number = v.matches("-?\\d+(\\.\\d+)?");
        // OWASP CSV injection: '=', '+', '@', '-' and also a leading tab or
        // carriage return, which spreadsheet importers treat as triggers.
        if (first == '=' || first == '+' || first == '@' || first == '\t' || first == '\r'
                || (first == '-' && !number)) {
            v = "'" + v;
        }
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    static String drugName(InventoryItem item) {
        MedicationCatalogItem med = item.getMedicationCatalogItem();
        if (med == null) {
            return null;
        }
        String name = med.getNameFr() != null && !med.getNameFr().isBlank() ? med.getNameFr() : med.getGenericName();
        String strength = med.getStrength() == null ? "" : " " + med.getStrength()
                + (med.getStrengthUnit() == null ? "" : " " + med.getStrengthUnit());
        return (Objects.toString(name, "") + strength).trim();
    }

    private static String patientName(Patient patient) {
        if (patient == null) {
            return null;
        }
        String full = (Objects.toString(patient.getFirstName(), "").trim() + " "
                + Objects.toString(patient.getLastName(), "").trim()).trim();
        return full.isEmpty() ? null : full;
    }

    private static String prescriberName(Staff staff) {
        return staff == null ? null : userName(staff.getUser());
    }

    private static String userName(User user) {
        if (user == null) {
            return null;
        }
        String full = (Objects.toString(user.getFirstName(), "").trim() + " "
                + Objects.toString(user.getLastName(), "").trim()).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
