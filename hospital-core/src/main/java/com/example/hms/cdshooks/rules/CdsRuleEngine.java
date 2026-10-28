package com.example.hms.cdshooks.rules;

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
import java.util.EnumSet;
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
 * <p>Active-prescription scope: we exclude terminal statuses
 * (CANCELLED / DISCONTINUED / PARTNER_REJECTED). The remaining set is
 * the patient's "current medication list" for interaction and
 * duplicate purposes.
 */
@Service
public class CdsRuleEngine {

    private static final Logger logger = LoggerFactory.getLogger(CdsRuleEngine.class);

    /**
     * Statuses considered terminal for active-medication checks. Anything
     * else (DRAFT, SIGNED, TRANSMITTED, DISPENSED, etc.) counts as a
     * prescription the patient could currently be on or about to take.
     */
    private static final Set<PrescriptionStatus> TERMINAL_STATUSES = EnumSet.of(
        PrescriptionStatus.CANCELLED,
        PrescriptionStatus.DISCONTINUED,
        PrescriptionStatus.PARTNER_REJECTED
    );

    /**
     * Numeric prefix in a freetext dosage string: "500", "12.5", "0,25".
     * Anchored to the start so we accept "500 mg PO BID" but ignore
     * stray numbers in the middle of the string.
     */
    private static final Pattern DOSE_NUMBER = Pattern.compile(
        "^\\s*(\\d+[\\.,]?\\d*)\\s*(mg|g)?",
        Pattern.CASE_INSENSITIVE
    );

    private final List<CdsRule> rules;
    private final PrescriptionRepository prescriptionRepository;
    private final MedicationCatalogItemRepository catalogRepository;
    private final PatientVitalSignRepository vitalSignRepository;

    public CdsRuleEngine(
        List<CdsRule> rules,
        PrescriptionRepository prescriptionRepository,
        MedicationCatalogItemRepository catalogRepository,
        PatientVitalSignRepository vitalSignRepository
    ) {
        this.rules = List.copyOf(rules);
        this.prescriptionRepository = prescriptionRepository;
        this.catalogRepository = catalogRepository;
        this.vitalSignRepository = vitalSignRepository;
    }

    /** Build a context for the given proposed order; never null. */
    public CdsRuleContext buildContext(Patient patient, UUID hospitalId,
                                       String proposedMedicationName,
                                       String proposedMedicationCode,
                                       String proposedDosage) {
        return buildContext(patient, hospitalId, proposedMedicationName, proposedMedicationCode,
            proposedDosage, null, null);
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
     */
    public CdsRuleContext buildContext(Patient patient, UUID hospitalId,
                                       String proposedMedicationName,
                                       String proposedMedicationCode,
                                       String proposedDosage,
                                       MedicationCatalogItem proposedCatalogItem,
                                       UUID excludedPrescriptionId) {
        MedicationCatalogItem catalogItem = proposedCatalogItem != null
            ? proposedCatalogItem
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

        Double weight = patient == null ? null : loadLatestWeight(patient.getId(), hospitalId);
        Double doseMg = parseDoseMg(proposedDosage, weight);

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
            activeRxnorms
        );
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
                // signing. Log and continue with the rest.
                logger.warn("CDS rule {} threw {}: {}", rule.id(),
                    ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        return cards;
    }

    /** Convenience: build context + evaluate. */
    public List<CdsCard> evaluateProposedPrescription(Patient patient, UUID hospitalId,
                                                       String proposedMedicationName,
                                                       String proposedMedicationCode,
                                                       String proposedDosage) {
        return evaluate(buildContext(patient, hospitalId,
            proposedMedicationName, proposedMedicationCode, proposedDosage));
    }

    /** Convenience: build context (with a resolved item and an edited order) + evaluate. */
    public List<CdsCard> evaluateProposedPrescription(Patient patient, UUID hospitalId,
                                                       String proposedMedicationName,
                                                       String proposedMedicationCode,
                                                       String proposedDosage,
                                                       MedicationCatalogItem proposedCatalogItem,
                                                       UUID excludedPrescriptionId) {
        return evaluate(buildContext(patient, hospitalId, proposedMedicationName, proposedMedicationCode,
            proposedDosage, proposedCatalogItem, excludedPrescriptionId));
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
        return all.stream()
            .filter(p -> p.getStatus() != null && !TERMINAL_STATUSES.contains(p.getStatus()))
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
        Optional<PatientVitalSign> latest = hospitalId != null
            ? vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId)
            : vitalSignRepository.findFirstByPatient_IdOrderByRecordedAtDesc(patientId);
        return latest.map(PatientVitalSign::getWeightKg).orElse(null);
    }

    /**
     * Parse leading number from a dosage string into mg. Accepts "500 mg",
     * "0,25 g" (returns 250), "12.5 mg". Returns null when the string
     * cannot be parsed — rules then degrade gracefully.
     */
    /**
     * QA D935: {@link #parseDoseMg(String)}, except that a weight-based dose
     * ("15 mg/kg") is the number times the patient's weight, and is unknown
     * (null) when no weight is on file. Read as plain milligrams it made a
     * 15 mg/kg order look like 15 mg.
     */
    static Double parseDoseMg(String dosage, Double weightKg) {
        if (dosage != null && PER_KG.matcher(dosage).find()) {
            Double perKg = parseDoseMg(dosage);
            return perKg == null || weightKg == null || weightKg <= 0d ? null : perKg * weightKg;
        }
        return parseDoseMg(dosage);
    }

    /** "mg/kg", "mg / kg", "g/kg" right after the leading number. */
    private static final Pattern PER_KG = Pattern.compile(
        "^\\s*\\d+[\\.,]?\\d*\\s*(mg|g)?\\s*/\\s*kg\\b",
        Pattern.CASE_INSENSITIVE
    );

    static Double parseDoseMg(String dosage) {
        if (dosage == null || dosage.isBlank()) return null;
        Matcher m = DOSE_NUMBER.matcher(dosage);
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
