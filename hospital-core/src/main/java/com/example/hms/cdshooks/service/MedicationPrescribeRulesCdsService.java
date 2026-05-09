package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceDescriptor;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.cdshooks.service.MedicationDraftExtractor.ProposedMedication;
import com.example.hms.model.Patient;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.terminology.TerminologyCodes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CDS Hooks 1.0 {@code medication-prescribe} service. Fires while the
 * clinician is authoring a medication order — even earlier than
 * {@code order-select} — and is therefore strictly medication-only:
 * any non-{@code MedicationRequest} entry in the payload is ignored
 * rather than gracefully skipped (the spec only ever delivers
 * {@code MedicationRequest} resources to this hook).
 *
 * <p>Builds on the same {@link CdsRuleEngine} used by {@code order-sign}
 * and {@code order-select}, so drug-drug, duplicate-order, and pediatric-
 * dose advisories all surface at authoring time. Surfaces ISMP "tall-man"
 * lettering ({@link MedicationCatalogItem#getTallManName()}, V93) in the
 * card detail when the matched catalog row carries one — disambiguates
 * confusable name pairs at the point they can still be edited.
 *
 * <p>Like the other CDS services, this one tolerates RxNorm-only payloads:
 * {@link MedicationDraftExtractor} pulls the canonical RxCUI when present,
 * and the engine resolves the catalog row via the V93 partial index.
 */
@Component
public class MedicationPrescribeRulesCdsService implements CdsHookService {

    private static final String ID = "hms-medication-prescribe-rules";
    private static final Source SOURCE = new Source(
        "HMS medication-prescribe rules",
        null,
        null
    );

    private final CdsRuleEngine ruleEngine;
    private final PatientRepository patientRepository;
    private final MedicationCatalogItemRepository catalogRepository;

    public MedicationPrescribeRulesCdsService(CdsRuleEngine ruleEngine,
                                              PatientRepository patientRepository,
                                              MedicationCatalogItemRepository catalogRepository) {
        this.ruleEngine = ruleEngine;
        this.patientRepository = patientRepository;
        this.catalogRepository = catalogRepository;
    }

    @Override
    public CdsServiceDescriptor descriptor() {
        return new CdsServiceDescriptor(
            "medication-prescribe",
            ID,
            "Drug safety check at authoring time",
            "Fires while the clinician is composing a medication order."
                + " Runs the HMS CDS rule engine (drug-drug interaction,"
                + " duplicate-order, pediatric-dose) and surfaces ISMP"
                + " tall-man lettering for confusable-name pairs when"
                + " present. Cards are advisory; final blocking gate is"
                + " enforced at order-sign.",
            null
        );
    }

    @Override
    public CdsHookResponse evaluate(CdsHookRequest request) {
        UUID patientId = CdsHookContext.requirePatientId(request);
        if (patientId == null) return CdsHookResponse.empty();
        Patient patient = patientRepository.findByIdUnscoped(patientId).orElse(null);
        if (patient == null) return CdsHookResponse.empty();

        UUID hospitalId = patient.getHospitalId();

        List<CdsCard> cards = new ArrayList<>();
        for (Map<String, Object> draft : CdsHookContext.medicationDrafts(request)) {
            // medication-prescribe carries MedicationRequest only — anything
            // else is a payload bug we silently drop.
            if (!"MedicationRequest".equals(draft.get("resourceType"))) continue;

            ProposedMedication proposed = MedicationDraftExtractor.extract(draft);
            if (proposed.name() == null && proposed.code() == null) continue;

            cards.addAll(ruleEngine.evaluateProposedPrescription(
                patient, hospitalId,
                proposed.name(),
                proposed.code(),
                proposed.dose()
            ));

            tallManAdvisory(hospitalId, proposed).ifPresent(cards::add);
        }
        return CdsHookResponse.of(cards);
    }

    /**
     * Emits an INFO card carrying the catalog-row's tall-man lettering when
     * one is configured. Disambiguates pairs like "predniSONE" /
     * "prednisoLONE" while the prescriber can still edit the name. Returns
     * empty when no match, no tall-man entry, or hospital scope unknown.
     */
    private Optional<CdsCard> tallManAdvisory(UUID hospitalId, ProposedMedication proposed) {
        if (hospitalId == null) return Optional.empty();
        String code = proposed.code();
        if (code == null || code.isBlank()) return Optional.empty();
        String trimmed = code.trim();
        Optional<MedicationCatalogItem> match =
            catalogRepository.findByHospitalIdAndCode(hospitalId, trimmed);
        if (match.isEmpty() && TerminologyCodes.isValidRxNorm(trimmed)) {
            // RxNorm-keyed fallback so RxNorm-only payloads still get the
            // advisory. Mirrors the gate in CdsRuleEngine.resolveCatalogItem
            // — only attempt the rxnorm_code lookup when the value parses as
            // a well-formed RxCUI, otherwise we'd issue a needless query
            // against a code shape (e.g. ATC J01CA04, internal AMOX-500)
            // that can never match the rxnorm_code column.
            List<MedicationCatalogItem> byRxnorm =
                catalogRepository.findActiveByHospitalIdAndRxnormCode(hospitalId, trimmed);
            match = byRxnorm.isEmpty() ? Optional.empty() : Optional.of(byRxnorm.get(0));
        }
        return match
            .map(MedicationCatalogItem::getTallManName)
            .filter(s -> s != null && !s.isBlank())
            .map(tallMan -> new CdsCard(
                "Confusable name — verify spelling",
                "Use ISMP tall-man lettering: " + tallMan,
                CdsCard.Indicator.INFO,
                SOURCE,
                null, null, null, null
            ));
    }
}
