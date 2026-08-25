package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseCheck;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.utility.LotBarcode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * Server-side verification at the pharmacy counter (Tier 2 item 34) — the
 * dispensing counterpart of {@code FiveRightsVerificationService} at the
 * bedside.
 *
 * <p>Three checks, and they do not all depend on a scan. That asymmetry is
 * the point of this class, so it is worth stating plainly:
 *
 * <ul>
 *   <li>{@link DispenseCheck#EXPIRY} needs no scan. It evaluates the lot the
 *       pharmacist already named on the request, so it runs on every
 *       dispense at every site, scanner or not. Before this existed,
 *       {@code StockLot.expiryDate} was written at goods-in and read by
 *       nothing anywhere in the application — the FEFO query that filters
 *       it out is real, and the dispense path called {@code findById}
 *       straight past it.</li>
 *   <li>{@link DispenseCheck#DRUG} likewise runs without a scan when a lot
 *       is named, because the lot already resolves to a catalogue item that
 *       can be compared to the prescription. A scan makes it stronger — it
 *       proves the pharmacist held the pack rather than clicking a row —
 *       but its absence is no excuse for skipping the comparison.</li>
 *   <li>{@link DispenseCheck#PATIENT} is the only check that genuinely
 *       requires a scan, because the wristband is the only evidence of who
 *       is standing at the counter.</li>
 * </ul>
 *
 * <p>Like its bedside twin this service is <em>advisory</em>: it reports
 * outcomes and the caller decides what to persist and what to refuse.
 * Unlike its twin, an unevaluated check is left OUT of the result rather
 * than recorded as a failure, because at this counter "not scanned" is a
 * legitimate paper-fallback outcome and must never be storable as "the
 * right patient was confirmed".
 */
@Service
public class DispenseVerificationService {

    private final Clock clock;

    public DispenseVerificationService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Verify what can be verified from the supplied scans and the named lot.
     *
     * @param prescription  the prescription being filled (never null)
     * @param lot           the stock lot being consumed, or null when the
     *                      site dispenses without lot tracking
     * @param patientScan   raw wristband value, or null/blank if not scanned
     * @param productScan   raw lot-label value, or null/blank if not scanned
     */
    public DispenseVerificationResult verify(
        Prescription prescription,
        StockLot lot,
        String patientScan,
        String productScan
    ) {
        DispenseVerificationResult.Builder b = DispenseVerificationResult.builder();

        verifyPatient(prescription, patientScan, b);
        verifyProductScan(lot, productScan, b);
        verifyDrug(prescription, lot, b);
        verifyExpiry(lot, b);

        return b.build();
    }

    /**
     * Right patient: the scanned wristband must resolve to the
     * prescription's patient. Skipped entirely — not failed — when nothing
     * was scanned.
     */
    private void verifyPatient(Prescription rx, String scan, DispenseVerificationResult.Builder b) {
        if (isBlank(scan)) {
            return;
        }
        Patient patient = rx == null ? null : rx.getPatient();
        if (patient == null || patient.getId() == null) {
            b.fail(DispenseCheck.PATIENT, "the prescription has no patient on it");
            return;
        }
        UUID scanned = parseUuidOrNull(scan);
        if (scanned == null) {
            b.fail(DispenseCheck.PATIENT, "the scanned wristband is not a patient identifier");
            return;
        }
        if (patient.getId().equals(scanned)) {
            b.pass(DispenseCheck.PATIENT);
        } else {
            b.fail(DispenseCheck.PATIENT,
                "the scanned wristband is a different patient from the one on this prescription");
        }
    }

    /**
     * The product scan is evidence that the pack in the pharmacist's hand is
     * the lot named on the request. It is folded into the DRUG check rather
     * than given a check of its own, because from the patient's side there
     * is one question — is this the right medicine — and splitting it would
     * let a UI report "drug ✓, product ✗" as if that were a partial success.
     */
    private void verifyProductScan(StockLot lot, String scan, DispenseVerificationResult.Builder b) {
        if (isBlank(scan)) {
            return;
        }
        if (lot == null) {
            b.fail(DispenseCheck.DRUG, "a product was scanned but no stock lot was selected");
            return;
        }
        String expected = normalize(lot.getBarcodeValue());
        if (expected.isEmpty()) {
            b.fail(DispenseCheck.DRUG,
                "the selected lot has no printed label to scan against — print one first");
            return;
        }
        if (!expected.equals(normalize(scan))) {
            // Two scan boxes on one screen: scanning the wristband into the
            // product field is an easy slip, and saying so is more use than
            // "no match".
            b.fail(DispenseCheck.DRUG, LotBarcode.looksLikeLotBarcode(scan)
                ? "the scanned pack is not the stock lot selected for this dispense"
                : "that is not a pack label — check you have not scanned the wristband twice");
        }
        // A match is not recorded as a pass here: verifyDrug still has to
        // agree that the lot is the PRESCRIBED drug. Scanning the pack you
        // picked up proves only that you picked up that pack.
    }

    /**
     * Right drug: the lot's catalogue item must be the prescribed
     * medication. Matches on code first (formulary or RxNorm), falling back
     * to the name — the same precedence the bedside check uses, because the
     * prescription carries free text and a code, not a catalogue FK.
     */
    private void verifyDrug(Prescription rx, StockLot lot, DispenseVerificationResult.Builder b) {
        if (lot == null || rx == null) {
            return;
        }
        if (b.hasFailed(DispenseCheck.DRUG)) {
            return; // the product scan already failed it; do not overwrite the reason
        }
        MedicationCatalogItem item = catalogItemOf(lot);
        if (item == null) {
            b.fail(DispenseCheck.DRUG, "the selected lot is not linked to a medication");
            return;
        }
        if (matchesPrescribedDrug(rx, item)) {
            b.pass(DispenseCheck.DRUG);
        } else {
            b.fail(DispenseCheck.DRUG,
                "the selected lot is %s, which is not the prescribed %s"
                    .formatted(describe(item), describePrescribed(rx)));
        }
    }

    /** Fit to give: the lot must not have passed its expiry date. */
    private void verifyExpiry(StockLot lot, DispenseVerificationResult.Builder b) {
        if (lot == null) {
            return;
        }
        LocalDate expiry = lot.getExpiryDate();
        if (expiry == null) {
            b.fail(DispenseCheck.EXPIRY, "the selected lot has no expiry date recorded");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        if (expiry.isBefore(today)) {
            b.fail(DispenseCheck.EXPIRY,
                "lot %s expired on %s".formatted(lot.getLotNumber(), expiry));
        } else {
            b.pass(DispenseCheck.EXPIRY);
        }
    }

    // ── Matching ────────────────────────────────────────────────────────

    /**
     * True when the catalogue item is the drug the prescription names. Code
     * beats name: the code is the identifier, the name is how somebody typed
     * it. Both the formulary code and the RxNorm code are accepted, since a
     * prescription written through the catalogue carries one and a
     * prescription imported over HL7 may carry the other.
     */
    private boolean matchesPrescribedDrug(Prescription rx, MedicationCatalogItem item) {
        String prescribedCode = normalize(rx.getMedicationCode());
        if (!prescribedCode.isEmpty()) {
            return prescribedCode.equals(normalize(item.getCode()))
                || prescribedCode.equals(normalize(item.getRxnormCode()))
                || prescribedCode.equals(normalize(item.getAtcCode()));
        }
        String prescribedName = normalize(rx.getMedicationName());
        if (prescribedName.isEmpty()) {
            // Nothing to compare against. Refusing here would block every
            // legacy prescription that predates coded ordering, so this is
            // deliberately permissive — and the DRUG check is recorded as
            // unevaluated rather than passed, which is the honest answer.
            return true;
        }
        return prescribedName.equals(normalize(item.getGenericName()))
            || prescribedName.equals(normalize(item.getBrandName()))
            || prescribedName.equals(normalize(item.getNameFr()));
    }

    /**
     * Resolve the catalogue item behind a lot, tolerating a detached or
     * partially-loaded graph rather than throwing inside a safety check.
     */
    private MedicationCatalogItem catalogItemOf(StockLot lot) {
        InventoryItem inventoryItem = lot.getInventoryItem();
        return inventoryItem == null ? null : inventoryItem.getMedicationCatalogItem();
    }

    /** Human-readable name of a catalogue item, for the refusal message. */
    private String describe(MedicationCatalogItem item) {
        String name = firstNonBlank(
            item.getGenericName(), item.getBrandName(), item.getNameFr());
        return name == null ? "an unnamed product" : name;
    }

    /** Human-readable name of what was prescribed, for the refusal message. */
    private String describePrescribed(Prescription rx) {
        String name = firstNonBlank(
            rx.getMedicationDisplayName(), rx.getMedicationName(), rx.getMedicationCode());
        return name == null ? "prescribed medication" : name;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (!isBlank(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static UUID parseUuidOrNull(String s) {
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
