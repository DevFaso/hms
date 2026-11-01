package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.bpa.BpaRuleEngine;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceDescriptor;
import com.example.hms.model.CdsAcknowledgement;
import com.example.hms.model.Patient;
import com.example.hms.repository.CdsAcknowledgementRepository;
import com.example.hms.repository.PatientRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CDS Hooks 1.0 {@code patient-view} service that runs the
 * {@link BpaRuleEngine} (malaria, sepsis qSOFA, OB hemorrhage) when a
 * provider opens a chart. Returns one card per fired rule; the front-end
 * Best-Practice Advisory panel renders them as a flat advisory list.
 *
 * <p>Co-exists with {@link PatientViewCdsService} (active allergies +
 * problems summary) on the same hook; CDS Hooks 1.0 allows multiple
 * services per hook and clients invoke each by its discrete id.
 */
@Component
public class BpaProtocolsCdsService implements CdsHookService {

    private static final String ID = "hms-bpa-protocols";

    private final BpaRuleEngine ruleEngine;
    private final PatientRepository patientRepository;
    private final CdsAcknowledgementRepository acknowledgementRepository;

    public BpaProtocolsCdsService(BpaRuleEngine ruleEngine,
                                  PatientRepository patientRepository,
                                  CdsAcknowledgementRepository acknowledgementRepository) {
        this.ruleEngine = ruleEngine;
        this.patientRepository = patientRepository;
        this.acknowledgementRepository = acknowledgementRepository;
    }

    @Override
    public CdsServiceDescriptor descriptor() {
        // Prefetch templates align with the inputs the BPA rule engine
        // consumes: patient demographics + recent vitals + active
        // problems + active medication requests. Partner EHRs (Cerner,
        // Epic, SMART App Launcher) can ship the bundles inline.
        java.util.Map<String, String> prefetch = java.util.Map.of(
            "patient", "Patient/{{context.patientId}}",
            "vitals", "Observation?patient={{context.patientId}}&category=vital-signs&_count=20&_sort=-date",
            "problems", "Condition?patient={{context.patientId}}&clinical-status=active",
            "medications", "MedicationRequest?patient={{context.patientId}}&status=active"
        );
        return new CdsServiceDescriptor(
            "patient-view",
            ID,
            "Best-Practice Advisories",
            "Runs the HMS BPA rule engine (malaria, sepsis qSOFA, OB hemorrhage) "
                + "against the patient's recent vitals, active problems, and active "
                + "prescriptions. Cards are advisory only.",
            prefetch
        );
    }

    /**
     * QA D1121 (round 20): read-only transaction around the chart-view
     * evaluation, so the patient and its readings stay readable for the whole
     * hook (open-in-view is off). The rules themselves must not depend on it
     * - {@code BpaRulesOutsideSessionIT} runs each one with no session open -
     * this is the second line of defence, not the fix.
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public CdsHookResponse evaluate(CdsHookRequest request) {
        UUID patientId = CdsHookContext.requirePatientId(request);
        if (patientId == null) return CdsHookResponse.empty();
        Patient patient = patientRepository.findByIdUnscoped(patientId).orElse(null);
        if (patient == null) return CdsHookResponse.empty();

        UUID hospitalId = patient.getHospitalId();
        List<CdsCard> cards = ruleEngine.evaluateForPatient(patient, hospitalId);
        return CdsHookResponse.of(suppressAcknowledged(cards, patientId));
    }

    /**
     * Drop cards the clinician has already acknowledged or overridden within
     * the suppression window.
     *
     * <p>QA D1194 (round 22): keyed by rule, patient and user. A BPA card's
     * uuid is the rule and its level ({@code BpaRuleEngine.stableCardUuid}),
     * so it is matched by uuid only - never by its summary, which is text in
     * the reader's language (an acknowledgement recorded in French hid the
     * card in French and nowhere else). Only the viewer's own
     * acknowledgements count: each clinician acknowledges an advisory for
     * themself, as in a hospital EHR. The {summary, indicator} fallback is
     * kept for a card that carries no uuid.
     */
    private List<CdsCard> suppressAcknowledged(List<CdsCard> cards, UUID patientId) {
        if (cards == null || cards.isEmpty()) {
            return cards == null ? List.of() : cards;
        }
        // Read directly from the repository — CdsHooksController admits only
        // clinical roles and runs PatientChartAccess.require on context.patientId
        // before any service evaluates, so the auth-gated service path is not
        // needed here.
        UUID viewerId = com.example.hms.security.context.HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        if (viewerId == null) {
            return cards;
        }
        List<CdsAcknowledgement> active =
                acknowledgementRepository.findActiveForPatientAndUser(patientId, viewerId,
                        LocalDateTime.now(java.time.ZoneId.systemDefault()));
        if (active.isEmpty()) {
            return cards;
        }
        Set<String> ackedUuids = active.stream()
                .map(CdsAcknowledgement::getCardUuid)
                .filter(u -> u != null && !u.isBlank())
                .collect(Collectors.toSet());
        Set<String> ackedFingerprints = active.stream()
                .map(a -> fingerprint(a.getCardSummary(), a.getIndicator()))
                .collect(Collectors.toSet());
        return cards.stream()
                .filter(c -> {
                    if (c.uuid() != null && !c.uuid().isBlank()) {
                        return !ackedUuids.contains(c.uuid());
                    }
                    String indicator = c.indicator() != null ? c.indicator().wire() : null;
                    return !ackedFingerprints.contains(fingerprint(c.summary(), indicator));
                })
                .toList();
    }

    private static String fingerprint(String summary, String indicator) {
        String s = summary == null ? "" : summary;
        String i = indicator == null ? "" : indicator.toLowerCase(java.util.Locale.ROOT);
        return s + "::" + i;
    }
}
