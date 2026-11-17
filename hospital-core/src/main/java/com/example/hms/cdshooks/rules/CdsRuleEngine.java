package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.terminology.TerminologyCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link CdsRuleContext} for a proposed prescription and runs
 * every registered {@link CdsRule} against it. The result is the
 * concatenated list of {@link CdsCard cards} returned by the rules.
 *
 * <p>This service is the only place that talks to the persistence layer
 * for rule evaluation — rules are pure functions of the context. That
 * keeps the rules trivially unit-testable with a hand-built context and
 * avoids hidden N+1 lookups inside individual rules.
 *
 * <p>Active-prescription scope (QA D1515, D1523): the context's other orders
 * are the orders in force ({@link PrescriptionStatus#isInForce()}) and the
 * proposals ({@link PrescriptionStatus#isProposal()}: drafts and orders
 * waiting for a signature), withdrawn and rejected ones left out. Only the
 * duplicate and interaction rules read that list, and they warn about a
 * proposal as another order the patient may soon be on, naming its status.
 * Exposure totals and dose limits never read it: the paracetamol facts keep
 * only orders in force ({@link #inForce}).
 */
@Service
public class CdsRuleEngine {

    private static final Logger logger = LoggerFactory.getLogger(CdsRuleEngine.class);

    /**
     * Numeric prefix in a freetext dosage string: "500", "12.5", "0,25".
     * Anchored to the start so we accept "500 mg PO BID" but ignore
     * stray numbers in the middle of the string.
     */
    private static final Pattern DOSE_NUMBER = Pattern.compile(
        "^\\s*(\\d+[\\.,]?\\d*)\\s*(mg|g)?",
        Pattern.CASE_INSENSITIVE
    );

    /** The rules whose silence reads as a dose within its limits. */
    private static final Set<String> DOSE_RULES = Set.of("adult-paracetamol", "pediatric-dose");

    private final List<CdsRule> rules;
    private final PrescriptionRepository prescriptionRepository;
    private final MedicationCatalogItemRepository catalogRepository;
    private final PatientVitalSignRepository vitalSignRepository;
    private final ParacetamolFactsReader paracetamolFacts;

    /**
     * QA D1121 (round 20): a rule that throws is a defect, not a quiet log
     * line. In production the chart must still load, so the failure is logged
     * at ERROR with its stack trace and the other rules still run; with
     * {@code hms.cds.rules.fail-fast=true} (the test profile) it is rethrown,
     * so any test that reaches the engine fails on it. The D1114 time-zone fix
     * threw LazyInitializationException in every time-stamped rule and no test
     * noticed, because the engine only logged a WARN.
     */
    private boolean failFast;

    /** The wording of a dose check that could not run, in the caller's language; English when built outside Spring. */
    private CdsCardText cardText = CdsCardText.ENGLISH;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCardText(CdsCardText cardText) {
        this.cardText = cardText;
    }

    @org.springframework.beans.factory.annotation.Value("${hms.cds.rules.fail-fast:false}")
    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public CdsRuleEngine(
        List<CdsRule> rules,
        PrescriptionRepository prescriptionRepository,
        MedicationCatalogItemRepository catalogRepository,
        PatientVitalSignRepository vitalSignRepository
    ) {
        this(rules, prescriptionRepository, catalogRepository, vitalSignRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CdsRuleEngine(
        List<CdsRule> rules,
        PrescriptionRepository prescriptionRepository,
        MedicationCatalogItemRepository catalogRepository,
        PatientVitalSignRepository vitalSignRepository,
        ParacetamolFactsReader paracetamolFacts
    ) {
        this.rules = List.copyOf(rules);
        this.prescriptionRepository = prescriptionRepository;
        this.catalogRepository = catalogRepository;
        this.vitalSignRepository = vitalSignRepository;
        this.paracetamolFacts = paracetamolFacts;
    }

    /** The catalogue item a saved order carries: its link, else its code; {@code null} when neither resolves here. */
    public MedicationCatalogItem itemOf(Prescription order, UUID hospitalId) {
        if (order.getMedicationCatalogItemId() != null && hospitalId != null) {
            return catalogRepository.findByIdAndHospital_Id(order.getMedicationCatalogItemId(), hospitalId).orElse(null);
        }
        return resolveCatalogItem(hospitalId, order.getMedicationCode());
    }

    /**
     * QA D1515: whether a saved order is one the patient is on, for every rule: in force by its status
     * ({@link PrescriptionStatus#isInForce()}). A draft or an unsigned order is not, so one abandoned
     * after a refused signature never adds to a later total.
     */
    public static boolean inForce(Prescription order) {
        return order != null && order.getStatus() != null && order.getStatus().isInForce();
    }

    /**
     * QA D1523: whether a saved order is one the duplicate and interaction warnings name: in force, or a
     * proposal the patient may soon be on. Never read by an exposure total or a dose limit.
     */
    public static boolean inForceOrProposed(Prescription order) {
        return inForce(order) || (order != null && order.getStatus() != null && order.getStatus().isProposal());
    }

    /**
     * Build a context for the given proposed order; never null. CR-01: the route the order states
     * ({@code null} when it states none) is part of the order, so no entry point exists without it
     * (OrderRouteEntryPointGuardTest).
     */
    public CdsRuleContext buildContext(Patient patient, UUID hospitalId,
                                       String proposedMedicationName,
                                       String proposedMedicationCode,
                                       String proposedDosage,
                                       String proposedRoute) {
        return buildContext(patient, hospitalId, new ProposedOrder(proposedMedicationName, proposedMedicationCode,
            proposedDosage, null, null, null, null, proposedRoute, null));
    }

    /**
     * QA D922/D935/D970: the context for an order whose catalogue item the
     * caller already resolved (by id, or by name at the hospital), and which
     * may be an edit of an existing prescription.
     *
     * @param proposedCatalogItem the item the order was written from; when
     *        {@code null} the item is resolved from the code, as before
     * @param excludedPrescriptionId the prescription being edited, which is
     *        not "another active order" of itself (D970); {@code null} on create
     * @param proposedRoute the route the order states; {@code null} when it states none
     */
    public CdsRuleContext buildContext(Patient patient, UUID hospitalId,
                                       String proposedMedicationName,
                                       String proposedMedicationCode,
                                       String proposedDosage,
                                       MedicationCatalogItem proposedCatalogItem,
                                       UUID excludedPrescriptionId,
                                       String proposedRoute) {
        return buildContext(patient, hospitalId, new ProposedOrder(proposedMedicationName, proposedMedicationCode,
            proposedDosage, null, proposedCatalogItem, excludedPrescriptionId, null, proposedRoute, null));
    }

    /**
     * The order being written, as the rules read it: its name and code, the
     * dose and frequency as typed, the catalogue item picked or resolved (may
     * be null), and the order being edited, left out of the duplicate check
     * (null on a create). CR-01: the route is the one this order states, null when
     * it states none, and never the catalogue item's; the formulation is the
     * ordered product's, the catalogue item's form when none is given.
     */
    public record ProposedOrder(String medicationName, String medicationCode, String dosage, String frequency,
                                MedicationCatalogItem catalogItem, UUID excludedPrescriptionId,
                                Double prnDosesPerDay, String route, String formulation) {
    }

    /**
     * Round 18 (QA D935): the context with the order's frequency, so the
     * paediatric rule can check the daily total, and the date of the weight
     * it uses, so an old weight is flagged.
     */
    public CdsRuleContext buildContext(Patient patient, UUID hospitalId, ProposedOrder order) {
        return buildContext(patient, hospitalId, order, null);
    }

    /** {@code factsRead}: the facts when the caller has read them already; {@code null} reads them here. */
    private CdsRuleContext buildContext(Patient patient, UUID hospitalId, ProposedOrder order, ParacetamolFacts factsRead) {
        String proposedMedicationName = order.medicationName();
        String proposedMedicationCode = order.medicationCode();
        String proposedDosage = order.dosage();
        String proposedFrequency = order.frequency();
        UUID excludedPrescriptionId = order.excludedPrescriptionId();
        MedicationCatalogItem catalogItem = order.catalogItem() != null
            ? order.catalogItem()
            : resolveCatalogItem(hospitalId, proposedMedicationCode);
        String rxnorm = catalogItem == null ? null : catalogItem.getRxnormCode();

        List<Prescription> active = patient == null
            ? List.of()
            : loadActivePrescriptions(patient.getId(), hospitalId);
        if (excludedPrescriptionId != null) {
            active = active.stream()
                .filter(p -> !excludedPrescriptionId.equals(p.getId()))
                .toList();
        }
        List<String> activeRxnorms = mapRxnorms(active, hospitalId);

        Optional<PatientVitalSign> weighed = patient == null
            ? Optional.empty() : loadLatestWeighing(patient.getId(), hospitalId);
        Double weight = weighed.map(PatientVitalSign::getWeightKg).orElse(null);
        java.time.LocalDateTime weighedAt = weighed.map(PatientVitalSign::getRecordedAt).orElse(null);
        // QA D1462: a cited paracetamol brand on an item says the INN is the item's, not the strength.
        boolean strengthUnknown = ParacetamolIdentity.nameIsParacetamolAlone(proposedMedicationName)
            && com.example.hms.service.pharmacy.OrderedDrugIdentity.namesOnlyThroughCitedBrand(proposedMedicationName, catalogItem);
        Double doseMg = parseDoseMg(proposedDosage, weight, strengthUnknown ? null : catalogItem);

        return new CdsRuleContext(
            patient,
            hospitalId,
            proposedMedicationName,
            proposedMedicationCode,
            rxnorm,
            doseMg,
            catalogItem,
            weight,
            active,
            activeRxnorms,
            proposedFrequency,
            parseDoseUnits(proposedDosage),
            weighedAt,
            order.prnDosesPerDay(),
            factsRead != null ? factsRead
                : readParacetamolFacts(patient, hospitalId, proposedMedicationName, catalogItem, excludedPrescriptionId),
            OrderedRoute.of(order.route(),
                order.formulation() != null || catalogItem == null ? order.formulation() : catalogItem.getForm())
        );
    }

    /** QA D1121: as a rule that throws, a failed read costs its cards, never the order. */
    private ParacetamolFacts readParacetamolFacts(Patient patient, UUID hospitalId, String name,
                                                  MedicationCatalogItem item, UUID editedOrderId) {
        if (paracetamolFacts == null) {
            return ParacetamolFacts.NONE;
        }
        try {
            return paracetamolFacts.read(patient, hospitalId, name, item, editedOrderId);
        } catch (RuntimeException ex) {
            if (failFast) {
                throw ex;
            }
            logger.error("CDS paracetamol facts threw {}: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return ParacetamolFacts.READ_FAILED;
        }
    }

    /** Run every registered rule against the context. */
    public List<CdsCard> evaluate(CdsRuleContext context) {
        if (context == null) return List.of();
        List<CdsCard> cards = new ArrayList<>();
        for (CdsRule rule : rules) {
            try {
                List<CdsCard> ruleCards = rule.evaluate(context);
                if (ruleCards != null) cards.addAll(ruleCards);
            } catch (RuntimeException ex) {
                // Defensive: a buggy rule must not block a clinician from
                // signing. Log and continue with the rest. QA D1121: ERROR
                // with the stack trace, and rethrown under fail-fast.
                if (failFast) {
                    throw ex;
                }
                logger.error("CDS rule {} threw {}: {}", rule.id(),
                    ex.getClass().getSimpleName(), ex.getMessage(), ex);
                // CR-02: a dose check that did not run is no clean check: the signature stays held.
                if (DOSE_RULES.contains(rule.id())) {
                    cards.add(doseCheckFailed(cardText, context.proposedMedicationName(), rule.id()));
                }
            }
        }
        return cards;
    }

    /** The card of a dose check that could not run: held as a finding is, with its own key. */
    static CdsCard doseCheckFailed(CdsCardText text, String medicationName, String ruleId) {
        return new CdsCard(
            text.get("cds.doseCheck.failed.summary",
                "{0}: the dose check could not run: cannot assess, review required",
                medicationName == null ? "" : medicationName),
            text.get("cds.doseCheck.failed.detail",
                "Nothing was judged, which is not a clean check. The order is signed only with the prescriber's explicit exception."),
            CdsCard.Indicator.WARNING,
            new com.example.hms.cdshooks.dto.CdsHookDtos.Source(text.get("cds.source.doseCheck", "e-Keneya Dose Check"), null, null),
            null, null, null, UUID.randomUUID().toString(), null, null, Boolean.TRUE, "checkFailed:" + ruleId);
    }

    /** Convenience: build context + evaluate. */
    public List<CdsCard> evaluateProposedPrescription(Patient patient, UUID hospitalId,
                                                       String proposedMedicationName,
                                                       String proposedMedicationCode,
                                                       String proposedDosage,
                                                       String proposedRoute) {
        return evaluate(buildContext(patient, hospitalId,
            proposedMedicationName, proposedMedicationCode, proposedDosage, proposedRoute));
    }

    /** Round 18: build context (with the frequency, a resolved item and an edited order) + evaluate. */
    public List<CdsCard> evaluateProposedPrescription(Patient patient, UUID hospitalId, ProposedOrder order) {
        return evaluate(buildContext(patient, hospitalId, order));
    }

    /** Convenience: build context (with a resolved item and an edited order) + evaluate. */
    public List<CdsCard> evaluateProposedPrescription(Patient patient, UUID hospitalId,
                                                       String proposedMedicationName,
                                                       String proposedMedicationCode,
                                                       String proposedDosage,
                                                       MedicationCatalogItem proposedCatalogItem,
                                                       UUID excludedPrescriptionId,
                                                       String proposedRoute) {
        return evaluate(buildContext(patient, hospitalId, proposedMedicationName, proposedMedicationCode,
            proposedDosage, proposedCatalogItem, excludedPrescriptionId, proposedRoute));
    }

    /**
     * CR-03: the adult paracetamol exposure at the moment of a dose, as warnings that refuse nothing.
     * Empty for any other drug. A failed read throws, so the caller can say the exposure is unknown:
     * it is never an empty answer, which reads as nothing to say.
     */
    public List<CdsCard> paracetamolAtAdministration(Patient patient, UUID hospitalId, ProposedOrder order,
                                                     boolean doseJustRecorded) {
        AdultParacetamolRule rule = rules.stream().filter(AdultParacetamolRule.class::isInstance)
            .map(AdultParacetamolRule.class::cast).findFirst().orElse(null);
        if (rule == null || patient == null) {
            return List.of();
        }
        MedicationCatalogItem item = order.catalogItem() != null
            ? order.catalogItem() : resolveCatalogItem(hospitalId, order.medicationCode());
        ParacetamolFacts facts = readParacetamolFacts(patient, hospitalId, order.medicationName(), item,
            order.excludedPrescriptionId());
        if (facts == ParacetamolFacts.READ_FAILED) {
            throw new IllegalStateException();
        }
        return facts.applies() ? rule.atAdministration(buildContext(patient, hospitalId, order, facts), doseJustRecorded) : List.of();
    }

    /* =====================================================================
       Helpers — kept package-private so tests can verify in isolation.
       ===================================================================== */

    /**
     * Resolves a catalog row by the proposed medication code. Callers may
     * pass either the formulary {@code code} column value (the historical
     * behaviour used by {@code order-sign}) or — since V93 — an RxNorm
     * RxCUI. We try the formulary code first because that is the cheapest,
     * most-selective lookup; if it misses and the supplied value parses as
     * a well-formed RxCUI, we fall back to {@code rxnorm_code} so the
     * {@code order-select} / {@code medication-prescribe} hooks resolve
     * cleanly when the FHIR payload only carries the canonical RxNorm
     * coding. The fallback is bounded by the partial index
     * {@code idx_med_catalog_rxnorm_active} added in V93.
     */
    MedicationCatalogItem resolveCatalogItem(UUID hospitalId, String code) {
        if (hospitalId == null || code == null || code.isBlank()) return null;
        Optional<MedicationCatalogItem> byCode =
            catalogRepository.findByHospitalIdAndCode(hospitalId, code);
        if (byCode.isPresent()) return byCode.get();
        String trimmed = code.trim();
        if (!TerminologyCodes.isValidRxNorm(trimmed)) return null;
        List<MedicationCatalogItem> byRxnorm =
            catalogRepository.findActiveByHospitalIdAndRxnormCode(hospitalId, trimmed);
        return byRxnorm.isEmpty() ? null : byRxnorm.get(0);
    }

    List<Prescription> loadActivePrescriptions(UUID patientId, UUID hospitalId) {
        if (patientId == null) return List.of();
        List<Prescription> all = hospitalId != null
            ? prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)
            : prescriptionRepository.findByPatient_Id(patientId, Pageable.unpaged()).getContent();
        // QA D1523: the duplicate and interaction rules, the only readers of this list, warn about a proposal too.
        return all.stream()
            .filter(CdsRuleEngine::inForceOrProposed)
            .toList();
    }

    /**
     * Batch-resolves the RxNorm code for each prescription against the
     * medication catalog in a single query — avoids the per-row catalog
     * lookup that would otherwise add N round-trips at order-sign time.
     * Result list is parallel to {@code active}: nulls fill positions
     * whose prescription had no medicationCode or no catalog match.
     */
    List<String> mapRxnorms(List<Prescription> active, UUID hospitalId) {
        if (active.isEmpty()) return List.of();
        if (hospitalId == null) return nullList(active.size());

        Set<String> distinctCodes = new HashSet<>();
        for (Prescription p : active) {
            String code = p.getMedicationCode();
            if (code != null && !code.isBlank()) distinctCodes.add(code);
        }
        if (distinctCodes.isEmpty()) return nullList(active.size());

        Map<String, String> rxnormByCode = new HashMap<>();
        for (MedicationCatalogItem item :
                catalogRepository.findByHospitalIdAndCodeIn(hospitalId, distinctCodes)) {
            if (item.getCode() != null && item.getRxnormCode() != null) {
                rxnormByCode.put(item.getCode(), item.getRxnormCode());
            }
        }
        List<String> rxnorms = new ArrayList<>(active.size());
        for (Prescription p : active) {
            String code = p.getMedicationCode();
            rxnorms.add(code == null ? null : rxnormByCode.get(code));
        }
        return rxnorms;
    }

    private static List<String> nullList(int size) {
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) out.add(null);
        return out;
    }

    Double loadLatestWeight(UUID patientId, UUID hospitalId) {
        return loadLatestWeighing(patientId, hospitalId).map(PatientVitalSign::getWeightKg).orElse(null);
    }

    /**
     * Round 18 (QA D935): the latest vital-signs row that RECORDS a weight.
     * The latest row of any kind was read before, so a temperature taken
     * after the weighing made the child's weight "not on file".
     */
    Optional<PatientVitalSign> loadLatestWeighing(UUID patientId, UUID hospitalId) {
        return hospitalId != null
            ? vitalSignRepository.findFirstByPatient_IdAndHospital_IdAndWeightKgIsNotNullOrderByRecordedAtDesc(
                patientId, hospitalId)
            : vitalSignRepository.findFirstByPatient_IdAndWeightKgIsNotNullOrderByRecordedAtDesc(patientId);
    }

    /**
     * QA D935: {@link #parseDoseMg(String)}, except that a weight-based dose
     * ("15 mg/kg") is the number times the patient's weight, and is unknown
     * (null) when no weight is on file. Read as plain milligrams it made a
     * 15 mg/kg order look like 15 mg.
     */
    static Double parseDoseMg(String dosage, Double weightKg) {
        return parseDoseMg(dosage, weightKg, null);
    }

    /**
     * Round 18 (QA D935): as {@link #parseDoseMg(String, Double)}, and a dose
     * written as a count of tablets or capsules ("2 cp") is that count times
     * the catalogue item's strength when the strength is one number in mg or g
     * (paracetamol 500 mg: "2 cp" is 1000 mg). Any other unit (mL, IU, or a
     * count of a combined strength such as 20/120) is unknown (null), never
     * read as mg: "2 cp" and "5 ml" were read as 2 mg and 5 mg before, so no
     * limit could fire. Micrograms are converted.
     */
    static Double parseDoseMg(String dosage, Double weightKg, MedicationCatalogItem item) {
        String read = DoseRange.highEnd(dosage);
        if (read != null && PER_KG.matcher(read).find()) {
            Double perKg = parseDoseMg(read);
            return perKg == null || weightKg == null || weightKg <= 0d ? null : perKg * weightKg;
        }
        String unit = leadingUnit(read);
        return unit == null ? parseDoseMg(read) : doseMgInUnit(read, unit, item);
    }

    /** The dose in mg when it is written with a unit word: a count, micrograms, mg or g; null otherwise. */
    private static Double doseMgInUnit(String dosage, String unit, MedicationCatalogItem item) {
        if (COUNT_UNITS.contains(unit)) {
            Double count = leadingNumber(dosage);
            Double strengthMg = strengthMgOf(item);
            return count == null || strengthMg == null ? null : count * strengthMg;
        }
        if (MICROGRAM_UNITS.contains(unit)) {
            Double micrograms = leadingNumber(dosage);
            return micrograms == null ? null : micrograms / 1000d;
        }
        if (!unit.equals("mg") && !unit.equals("g")) {
            return null;
        }
        return parseDoseMg(dosage);
    }

    /**
     * Round 18 (QA D935): the dose as a count of units, for a drug dosed by
     * weight band: "2 cp", "2 comprimés", "2 tablets", or a bare "2". Null for
     * a dose in mg, mL or any other measured unit.
     */
    static Double parseDoseUnits(String dosage) {
        String read = DoseRange.highEnd(dosage);
        String unit = leadingUnit(read);
        if (unit == null || COUNT_UNITS.contains(unit)) {
            return leadingNumber(read);
        }
        return null;
    }

    /** Tablet, capsule and sachet words in English, French and Spanish, lower-cased. */
    static final Set<String> COUNT_UNITS = Set.of(
        "cp", "cps", "comp", "comps", "cpr", "comprimé", "comprimés", "comprime", "comprimes",
        "tab", "tabs", "tablet", "tablets", "comprimido", "comprimidos",
        "gélule", "gélules", "gelule", "gelules", "caps", "capsule", "capsules", "cápsula", "cápsulas",
        "sachet", "sachets", "sobre", "sobres", "unit", "units", "unité", "unités", "unidad", "unidades");

    private static final Set<String> MICROGRAM_UNITS = Set.of("mcg", "ug", "\u00b5g", "\u03bcg");

    /**
     * The dose in mg when it is written with its unit, for a limit that must not read a bare
     * number as mg: "2" is more likely two tablets. Null for a bare number, as for any unknown.
     */
    static Double parseLabelledDoseMg(String dosage, Double weightKg, MedicationCatalogItem item) {
        return leadingUnit(DoseRange.highEnd(dosage)) == null ? null : parseDoseMg(dosage, weightKg, item);
    }

    /** Number, then the unit word right after it. Possessive quantifiers: no backtracking (CodeQL ReDoS). */
    private static final Pattern LEADING_QUANTITY = Pattern.compile(
        "^\\s*+(\\d++(?:[.,]\\d++)?+)\\s*+([\\p{L}\\u00b5\\u03bc]++)?+");

    private static Double leadingNumber(String dosage) {
        if (dosage == null) {
            return null;
        }
        Matcher m = LEADING_QUANTITY.matcher(dosage);
        if (!m.find()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String leadingUnit(String dosage) {
        if (dosage == null) {
            return null;
        }
        Matcher m = LEADING_QUANTITY.matcher(dosage);
        if (!m.find() || m.group(2) == null) {
            return null;
        }
        return m.group(2).toLowerCase(Locale.ROOT);
    }

    private static final Pattern PLAIN_NUMBER = Pattern.compile("\\d++(?:\\.\\d++)?+");

    /** The item's strength in mg when it is one number in mg or g ("500" mg); else null ("20/120"). */
    static Double strengthMgOf(MedicationCatalogItem item) {
        if (item == null || item.getStrength() == null) {
            return null;
        }
        String strength = item.getStrength().trim().replace(',', '.');
        if (!PLAIN_NUMBER.matcher(strength).matches()) {
            return null;
        }
        String unit = item.getStrengthUnit() == null ? "" : item.getStrengthUnit().trim().toLowerCase(Locale.ROOT);
        double value = Double.parseDouble(strength);
        return switch (unit) {
            case "mg" -> value;
            case "g" -> value * 1000d;
            default -> null;
        };
    }

    /** "mg/kg", "mg / kg", "g/kg" right after the leading number. */
    private static final Pattern PER_KG = Pattern.compile(
        // Decimals only after their separator, and the unit, when present,
        // owning the spaces after it: no two adjacent quantifiers can share
        // a typed run of digits or spaces (CodeQL polynomial ReDoS).
        "^\\s*\\d+(?:[.,]\\d*)?\\s*(?:(?:mg|g)\\s*)?/\\s*kg\\b",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Parse leading number from a dosage string into mg. Accepts "500 mg",
     * "0,25 g" (returns 250), "12.5 mg", a range at its high end ("2-4 mg"
     * returns 4). Returns null when the string
     * cannot be parsed — rules then degrade gracefully.
     */
    static Double parseDoseMg(String dosage) {
        if (dosage == null || dosage.isBlank()) return null;
        Matcher m = DOSE_NUMBER.matcher(DoseRange.highEnd(dosage));
        if (!m.find()) return null;
        try {
            String numeric = m.group(1).replace(',', '.');
            double value = Double.parseDouble(numeric);
            String unit = m.group(2);
            if (unit != null && unit.toLowerCase(Locale.ROOT).equals("g")) {
                value *= 1000d;
            }
            return value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
