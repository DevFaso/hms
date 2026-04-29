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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
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
        MedicationCatalogItem catalogItem = resolveCatalogItem(hospitalId, proposedMedicationCode);
        String rxnorm = catalogItem == null ? null : catalogItem.getRxnormCode();

        List<Prescription> active = patient == null
            ? List.of()
            : loadActivePrescriptions(patient.getId(), hospitalId);
        List<String> activeRxnorms = mapRxnorms(active, hospitalId);

        Double weight = patient == null ? null : loadLatestWeight(patient.getId(), hospitalId);
        Double doseMg = parseDoseMg(proposedDosage);

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

    /* =====================================================================
       Helpers — kept package-private so tests can verify in isolation.
       ===================================================================== */

    MedicationCatalogItem resolveCatalogItem(UUID hospitalId, String code) {
        if (hospitalId == null || code == null || code.isBlank()) return null;
        return catalogRepository.findByHospitalIdAndCode(hospitalId, code).orElse(null);
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

    List<String> mapRxnorms(List<Prescription> active, UUID hospitalId) {
        if (active.isEmpty()) return List.of();
        List<String> rxnorms = new ArrayList<>(active.size());
        for (Prescription p : active) {
            rxnorms.add(rxnormForPrescription(p, hospitalId));
        }
        return rxnorms;
    }

    String rxnormForPrescription(Prescription p, UUID hospitalId) {
        if (hospitalId == null || p.getMedicationCode() == null
            || p.getMedicationCode().isBlank()) return null;
        return catalogRepository.findByHospitalIdAndCode(hospitalId, p.getMedicationCode())
            .map(MedicationCatalogItem::getRxnormCode)
            .orElse(null);
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
