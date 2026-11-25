package com.example.hms.service.pharmacy;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;

import com.example.hms.enums.DispenseCheck;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.utility.LotBarcode;
import com.example.hms.utility.DisplayDates;
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
@ProvisionalRule(ClinicalRuleSetKey.DISPENSED_PRODUCT_SUBSTITUTION)
public class DispenseVerificationService {

    private final Clock clock;

    /** QA D3940: the order's own catalogue item, to compare products; absent in a pure-unit test. */
    private final com.example.hms.repository.MedicationCatalogItemRepository catalogue;

    public DispenseVerificationService(Clock clock) {
        this(clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DispenseVerificationService(Clock clock,
                                       com.example.hms.repository.MedicationCatalogItemRepository catalogue) {
        this.clock = clock;
        this.catalogue = catalogue;
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
            b.fail(DispenseCheck.PATIENT, com.example.hms.utility.MessageUtil.resolve("pharmacy.check.noPatient"));
            return;
        }
        UUID scanned = parseUuidOrNull(scan);
        if (scanned == null) {
            b.fail(DispenseCheck.PATIENT, com.example.hms.utility.MessageUtil.resolve("pharmacy.check.wristbandNotPatient"));
            return;
        }
        if (patient.getId().equals(scanned)) {
            b.pass(DispenseCheck.PATIENT);
        } else {
            b.fail(DispenseCheck.PATIENT,
                com.example.hms.utility.MessageUtil.resolve("pharmacy.check.wristbandOtherPatient"));
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
            b.fail(DispenseCheck.DRUG, com.example.hms.utility.MessageUtil.resolve("pharmacy.check.noLotSelected"));
            return;
        }
        String expected = normalize(lot.getBarcodeValue());
        if (expected.isEmpty()) {
            b.fail(DispenseCheck.DRUG,
                com.example.hms.utility.MessageUtil.resolve("pharmacy.check.lotNoLabel"));
            return;
        }
        if (!expected.equals(normalize(scan))) {
            // Two scan boxes on one screen: scanning the wristband into the
            // product field is an easy slip, and saying so is more use than
            // "no match".
            b.fail(DispenseCheck.DRUG, com.example.hms.utility.MessageUtil.resolve(
                LotBarcode.looksLikeLotBarcode(scan)
                    ? "pharmacy.check.packOtherLot"
                    : "pharmacy.check.notPackLabel"));
        }
        // A match is not recorded as a pass here: verifyDrug still has to
        // agree that the lot is the PRESCRIBED drug. Scanning the pack you
        // picked up proves only that you picked up that pack.
    }

    /**
     * Right drug: the lot's catalogue item must be the prescribed product
     * ({@link #classify}, QA D3940): the molecule AND the strength, not the
     * molecule alone. The match is kept on the result, so the caller lets a
     * recorded substitution carry only a real substitute through.
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
            b.fail(DispenseCheck.DRUG, com.example.hms.utility.MessageUtil.resolve("pharmacy.check.lotNoMedication"));
            return;
        }
        DispensedProductMatch match = classify(rx, item);
        b.productMatch(match);
        if (match.isPrescribedProduct()) {
            b.pass(DispenseCheck.DRUG);
        } else {
            b.fail(DispenseCheck.DRUG, refusalOf(match, rx, item));
        }
    }

    /**
     * QA D3940: why a product that is not the prescribed one is refused, in the
     * reader's language, naming both products with their strength.
     */
    public String refusalOf(DispensedProductMatch match, Prescription rx, MedicationCatalogItem item) {
        String product = describeProduct(item);
        String prescribed = describePrescribed(rx);
        return switch (match) {
            case SUBSTITUTE -> com.example.hms.utility.MessageUtil.resolve(
                "pharmacy.check.substituteNeedsRecord", product, prescribed);
            case OTHER_STRENGTH -> ProductStrength.labelOf(item).isPresent()
                ? com.example.hms.utility.MessageUtil.resolve("pharmacy.check.otherStrength", product, prescribed)
                : com.example.hms.utility.MessageUtil.resolve("pharmacy.check.strengthUnknown", product, prescribed);
            default -> com.example.hms.utility.MessageUtil.resolve("pharmacy.check.wrongDrug", product, prescribed);
        };
    }

    /** Fit to give: the lot must not have passed its expiry date. */
    private void verifyExpiry(StockLot lot, DispenseVerificationResult.Builder b) {
        if (lot == null) {
            return;
        }
        LocalDate expiry = lot.getExpiryDate();
        if (expiry == null) {
            b.fail(DispenseCheck.EXPIRY, com.example.hms.utility.MessageUtil.resolve("pharmacy.check.noExpiry"));
            return;
        }
        LocalDate today = LocalDate.now(clock);
        // QA D710: expired ON the expiry date (the expiry report says so too).
        if (!expiry.isAfter(today)) {
            b.fail(DispenseCheck.EXPIRY,
                com.example.hms.utility.MessageUtil.resolve("pharmacy.check.lotExpired", lot.getLotNumber(),
                    DisplayDates.date(expiry)));
        } else {
            b.pass(DispenseCheck.EXPIRY);
        }
    }

    // ── Matching ────────────────────────────────────────────────────────

    /**
     * True when the catalogue item is the product the prescription names: the
     * prescribed item or an {@link DispensedProductMatch#EQUIVALENT} of it
     * ({@link #classify}). The lot picker, the stock-out routing and the
     * dispense DRUG check read this one rule.
     */
    public boolean matchesPrescribedDrug(Prescription rx, MedicationCatalogItem item) {
        return classify(rx, item).isPrescribedProduct();
    }

    /** {@link #classify(Prescription, MedicationCatalogItem, MedicationCatalogItem)} with the order's own item. */
    public DispensedProductMatch classify(Prescription rx, MedicationCatalogItem item) {
        return classify(rx, prescribedItemOf(rx), item);
    }

    /**
     * The catalogue item the order was written from, or null (none, or not resolvable here). Read at the order's
     * own hospital, where the prescribing side resolved it ({@code applyCatalogPick}).
     */
    public MedicationCatalogItem prescribedItemOf(Prescription rx) {
        if (rx == null || rx.getMedicationCatalogItemId() == null || catalogue == null
                || rx.getHospital() == null || rx.getHospital().getId() == null) {
            return null;
        }
        return catalogue.findByIdAndHospital_Id(rx.getMedicationCatalogItemId(), rx.getHospital().getId())
            .orElse(null);
    }

    /**
     * QA D3940 (r77): how a catalogue item relates to the product the order
     * prescribes ({@link DispensedProductMatch} states the rule). A 250 mg
     * amoxicillin lot was dispensed for a 500 mg order: the check compared the
     * molecule (code, then name) and never the strength.
     *
     * <ul>
     *   <li>An order written from a catalogue item is compared product to
     *       product: the same item; else the same active ingredient (ATC code
     *       when both items carry one, else the generic name read as its
     *       ingredients), then the strength, then the form.</li>
     *   <li>An order with no item (free text, an external code) names a
     *       molecule by its code or its text (QA D138, D899, D1492), at the
     *       strength its text states; a text that states none compares the
     *       molecule alone, as before.</li>
     * </ul>
     *
     * @param prescribed the order's own item ({@link #prescribedItemOf}), or null
     */
    public static DispensedProductMatch classify(Prescription rx, MedicationCatalogItem prescribed,
                                                 MedicationCatalogItem item) {
        if (item == null) {
            return DispensedProductMatch.OTHER_DRUG;
        }
        if (rx == null) {
            return DispensedProductMatch.PRESCRIBED;
        }
        // QA D899: the catalog item the order was written from (V221) is the
        // drug, whatever its code or name. A UI-created item has no code, so
        // without this the match fell to the typed name alone.
        String prescribedCode = normalize(rx.getMedicationCode());
        boolean linked = rx.getMedicationCatalogItemId() != null && rx.getMedicationCatalogItemId().equals(item.getId());
        boolean codeNamesItem = !prescribedCode.isEmpty() && prescribedCode.equals(normalize(item.getCode()));
        // QA D1223 (round 24): the link (or the formulary code the link wrote)
        // says "this item", but the name written on the order names another
        // drug - an order saved with a mismatched catalogue id. The lot is not
        // the drug the order reads as: the DRUG check fails rather than letting
        // one input hide the other. The prescription is refused at creation,
        // edit and signature since round 24; this closes an older row.
        if ((linked || codeNamesItem) && OrderedDrugIdentity.contradicts(rx.getMedicationName(), item)) {
            return DispensedProductMatch.OTHER_DRUG;
        }
        if (linked) {
            return DispensedProductMatch.PRESCRIBED;
        }
        if (prescribed != null && !OrderedDrugIdentity.contradicts(rx.getMedicationName(), prescribed)) {
            return compareProducts(prescribed, item);
        }
        return compareWithOrderText(rx, prescribedCode, codeNamesItem, item);
    }

    /** An order written from {@code prescribed}: the other item is compared product to product. */
    private static DispensedProductMatch compareProducts(MedicationCatalogItem prescribed, MedicationCatalogItem item) {
        if (prescribed.getId() != null && prescribed.getId().equals(item.getId())) {
            return DispensedProductMatch.PRESCRIBED;
        }
        if (!sameActiveIngredient(prescribed, item)) {
            return DispensedProductMatch.OTHER_DRUG;
        }
        if (ProductStrength.compare(prescribed, item) != ProductStrength.Relation.SAME) {
            // A strength nobody recorded cannot be shown to be the same dose.
            return DispensedProductMatch.OTHER_STRENGTH;
        }
        return sameForm(prescribed, item) ? DispensedProductMatch.EQUIVALENT : DispensedProductMatch.SUBSTITUTE;
    }

    /** An order with no catalogue item: the molecule its code or text names, at the strength its text states. */
    private static DispensedProductMatch compareWithOrderText(Prescription rx, String prescribedCode,
                                                              boolean codeNamesItem, MedicationCatalogItem item) {
        if (codeNamesItem) {
            // The formulary code is the item's own: the order names this very product.
            return DispensedProductMatch.PRESCRIBED;
        }
        boolean molecule;
        if (!prescribedCode.isEmpty()) {
            molecule = prescribedCode.equals(normalize(item.getRxnormCode()))
                || prescribedCode.equals(normalize(item.getAtcCode()));
        } else {
            String prescribedName = normalize(rx.getMedicationName());
            if (prescribedName.isEmpty()) {
                // Nothing to compare against. Refusing here would block every
                // legacy prescription that predates coded ordering, so this is
                // deliberately permissive.
                return DispensedProductMatch.PRESCRIBED;
            }
            // QA D138: a free-text order names the drug the way people write it,
            // "Paracétamol 500 mg comprimé", while the catalogue says
            // "paracetamol". QA D1492: a cited brand is read as its generic, by
            // the map the limits and allergy checks use.
            molecule = namesCatalogItem(prescribedName, item)
                || OrderedDrugIdentity.citedBrandIsThisDrug(prescribedName, item);
        }
        if (!molecule) {
            return DispensedProductMatch.OTHER_DRUG;
        }
        // QA D3940: "Amoxicilline 500 mg" is not filled from a 250 mg lot.
        String text = ProductStrength.inText(rx.getMedicationName()).isPresent()
            ? rx.getMedicationName() : rx.getMedicationDisplayName();
        return ProductStrength.compare(text, item) == ProductStrength.Relation.DIFFERENT
            ? DispensedProductMatch.OTHER_STRENGTH : DispensedProductMatch.PRESCRIBED;
    }

    /**
     * The same active ingredient(s): the ATC code when both items carry one (it
     * names the substance, and a combination has its own), else the generic
     * names read as their ingredients in any spelling.
     */
    static boolean sameActiveIngredient(MedicationCatalogItem a, MedicationCatalogItem b) {
        String atcA = normalize(a.getAtcCode());
        String atcB = normalize(b.getAtcCode());
        if (!atcA.isEmpty() && !atcB.isEmpty()) {
            return atcA.equals(atcB);
        }
        java.util.Set<String> ingredientsA = OrderedDrugIdentity.ingredientsOf(a.getGenericName());
        return !ingredientsA.isEmpty() && ingredientsA.equals(OrderedDrugIdentity.ingredientsOf(b.getGenericName()));
    }

    /** The same dosage form, both stated. */
    private static boolean sameForm(MedicationCatalogItem a, MedicationCatalogItem b) {
        String formA = fold(normalize(a.getForm()));
        return !formA.isEmpty() && formA.equals(fold(normalize(b.getForm())));
    }

    /**
     * QA D138 / D922: whether free text names this catalogue item, the way
     * people write it ("Paracétamol 500 mg comprimé" names "paracetamol").
     * Accent- and case-insensitive; the catalogue name must be the whole first
     * part of the text, ending on a word boundary. The one name rule shared by
     * the dispense DRUG check and the prescription's catalogue resolution.
     */
    public static boolean namesCatalogItem(String prescribedName, MedicationCatalogItem item) {
        String normalized = normalize(prescribedName);
        if (normalized.isEmpty() || item == null) {
            return false;
        }
        String folded = fold(normalized);
        return namesDrug(folded, item.getGenericName())
            || namesDrug(folded, item.getBrandName())
            || namesDrug(folded, item.getNameFr());
    }

    private static boolean namesDrug(String foldedPrescribed, String catalogName) {
        String name = fold(normalize(catalogName));
        if (name.isEmpty() || !foldedPrescribed.startsWith(name)) {
            return false;
        }
        if (foldedPrescribed.length() == name.length()) {
            return true;
        }
        char next = foldedPrescribed.charAt(name.length());
        return Character.isWhitespace(next) || next == ',' || next == '(' || next == '/';
    }

    /** Lower-case text with its accents removed and its spaces collapsed. */
    private static String fold(String s) {
        String decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * Resolve the catalogue item behind a lot, tolerating a detached or
     * partially-loaded graph rather than throwing inside a safety check.
     */
    private MedicationCatalogItem catalogItemOf(StockLot lot) {
        InventoryItem inventoryItem = lot.getInventoryItem();
        return inventoryItem == null ? null : inventoryItem.getMedicationCatalogItem();
    }

    /**
     * Human-readable name of a catalogue item, for the refusal message.
     * QA D1221 (round 23) and D1235 (round 24): in the reader's language,
     * through the one naming rule ({@link com.example.hms.utility.DrugDisplayNames}):
     * the French name for a French reader, the INN for an English one, and the
     * catalogue's display name for a Spanish one (it holds no Spanish name; the
     * English INN read as a foreign word beside the prescribed "Paracétamol").
     * No name at all is said in the reader's language, never in English.
     */
    public static String describe(MedicationCatalogItem item) {
        String name = com.example.hms.utility.DrugDisplayNames.forReader(item);
        return name == null ? com.example.hms.utility.MessageUtil.resolve("pharmacy.check.unnamedProduct") : name;
    }

    /**
     * QA D3940: a catalogue product as a reader tells it from another strength,
     * "Amoxicilline 250 mg": its name in the reader's language ({@link #describe})
     * with its strength when the name does not already say it.
     */
    public static String describeProduct(MedicationCatalogItem item) {
        return withStrength(describe(item), item);
    }

    /** {@code name} followed by the item's strength, unless the name already states a strength. */
    public static String withStrength(String name, MedicationCatalogItem item) {
        return withStrength(name, item, com.example.hms.utility.DisplayNumbers.requestLocale());
    }

    /** {@link #withStrength(String, MedicationCatalogItem)} with the strength written for a reader of {@code locale}. */
    public static String withStrength(String name, MedicationCatalogItem item, Locale locale) {
        java.util.Optional<String> strength = ProductStrength.labelOf(item, locale);
        if (name == null || strength.isEmpty() || ProductStrength.inText(name).isPresent()) {
            return name;
        }
        return name + " " + strength.get();
    }

    /** Human-readable name of what was prescribed, for the refusal message (the reader's words when none). */
    public static String describePrescribed(Prescription rx) {
        String name = firstNonBlank(
            rx.getMedicationDisplayName(), rx.getMedicationName(), rx.getMedicationCode());
        return name == null ? com.example.hms.utility.MessageUtil.resolve("pharmacy.check.prescribedMedication") : name;
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
