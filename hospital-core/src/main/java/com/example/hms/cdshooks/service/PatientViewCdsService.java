package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceDescriptor;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.cdshooks.terminology.ProblemLoincBindings;
import com.example.hms.cdshooks.terminology.ProblemLoincBindings.LoincCoding;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientProblem;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.terminology.TerminologyCodes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * On {@code patient-view} (provider opens a chart) returns a small set of
 * cards summarising what would otherwise live in a Storyboard sidebar:
 * active allergies and active problems.
 *
 * <p>This is the foundation for the Best-Practice-Advisory pop-ups expected
 * in West-African deployments: malaria-protocol triggers, sickle-cell
 * disease alerts, pregnancy-safety flags. Those are added as additional
 * CDS services on the same hook.
 */
@Component
public class PatientViewCdsService implements CdsHookService {

    private static final String ID = "hms-patient-view";
    private static final String SOURCE_LABEL = "HMS Clinical Summary";

    private final PatientAllergyRepository allergyRepository;
    private final PatientProblemRepository problemRepository;

    public PatientViewCdsService(
        PatientAllergyRepository allergyRepository,
        PatientProblemRepository problemRepository
    ) {
        this.allergyRepository = allergyRepository;
        this.problemRepository = problemRepository;
    }

    @Override
    public CdsServiceDescriptor descriptor() {
        // CDS Hooks 1.0 prefetch templates — partner EHRs (Cerner, Epic,
        // SMART App Launcher) can pre-resolve these FHIR queries and ship
        // the bundles inside the invocation request so the service avoids
        // a round-trip back to the EHR's FHIR server. Each value uses the
        // {{context.<key>}} substitution defined by the spec; the keys
        // mirror the FHIR resource names this service consults today.
        java.util.Map<String, String> prefetch = java.util.Map.of(
            "patient", "Patient/{{context.patientId}}",
            "allergies", "AllergyIntolerance?patient={{context.patientId}}&clinical-status=active",
            "problems", "Condition?patient={{context.patientId}}&clinical-status=active"
        );
        return new CdsServiceDescriptor(
            "patient-view",
            ID,
            "Patient summary",
            "Active allergies and active problems for the patient being opened.",
            prefetch
        );
    }

    @Override
    public CdsHookResponse evaluate(CdsHookRequest request) {
        UUID patientId = CdsHookContext.requirePatientId(request);
        if (patientId == null) return CdsHookResponse.empty();

        List<CdsCard> cards = new ArrayList<>(2);
        addAllergyCard(cards, patientId);
        addProblemCard(cards, patientId);
        return CdsHookResponse.of(cards);
    }

    private void addAllergyCard(List<CdsCard> sink, UUID patientId) {
        List<PatientAllergy> allergies = allergyRepository.findByPatient_Id(patientId).stream()
            .filter(PatientAllergy::isActive)
            .toList();
        if (allergies.isEmpty()) return;

        boolean anyHigh = allergies.stream()
            .anyMatch(a -> a.getSeverity() == AllergySeverity.SEVERE
                || a.getSeverity() == AllergySeverity.LIFE_THREATENING);
        String summary = allergies.size() == 1
            ? "1 active allergy on file"
            : allergies.size() + " active allergies on file";
        String detail = allergies.stream()
            .map(this::renderAllergy)
            .collect(Collectors.joining("\n"));

        sink.add(new CdsCard(
            summary,
            detail,
            anyHigh ? CdsCard.Indicator.WARNING : CdsCard.Indicator.INFO,
            new Source(SOURCE_LABEL, null, null),
            null, null, null, java.util.UUID.randomUUID().toString()
        ));
    }

    private void addProblemCard(List<CdsCard> sink, UUID patientId) {
        List<PatientProblem> active = problemRepository.findByPatient_Id(patientId).stream()
            .filter(p -> p.getStatus() == null
                || p.getStatus() == ProblemStatus.ACTIVE
                || p.getStatus() == ProblemStatus.RECURRENCE)
            .toList();
        if (active.isEmpty()) return;

        String summary = active.size() == 1
            ? "1 active problem on the problem list"
            : active.size() + " active problems on the problem list";
        String detail = active.stream()
            .map(this::renderProblem)
            .collect(Collectors.joining("\n"));

        sink.add(new CdsCard(
            summary,
            detail,
            CdsCard.Indicator.INFO,
            new Source(SOURCE_LABEL, null, null),
            null, null, null, java.util.UUID.randomUUID().toString()
        ));
    }

    private String renderAllergy(PatientAllergy a) {
        StringBuilder sb = new StringBuilder("- ");
        sb.append(safe(a.getAllergenDisplay(), a.getAllergenCode()));
        if (a.getReaction() != null && !a.getReaction().isBlank()) {
            sb.append(" — reaction: ").append(a.getReaction().trim());
        }
        if (a.getSeverity() != null) {
            sb.append(" (").append(a.getSeverity().name().toLowerCase()).append(")");
        }
        return sb.toString();
    }

    private String renderProblem(PatientProblem p) {
        StringBuilder sb = new StringBuilder("- ");
        sb.append(safe(p.getProblemDisplay(), p.getProblemCode()));
        if (p.getSeverity() != null) {
            sb.append(" (").append(p.getSeverity().name().toLowerCase()).append(")");
        }
        if (p.isChronic()) sb.append(" — chronic");
        appendCodings(sb, p);
        return sb.toString();
    }

    /**
     * Appends the typed Coding annotations (ICD + LOINC) to a problem
     * line so external CDS consumers (Cerner / Epic / SMART-on-FHIR
     * sandboxes) and any downstream FHIR Condition mapper can recover
     * the canonical system URIs even from the human-readable card detail.
     *
     * <p>Format examples:
     * <pre>
     *   - Hypertension (moderate) — chronic [ICD-10: I10] [LOINC: 85354-9 Blood pressure panel]
     *   - Asthma — chronic [ICD-10: J45.9] [LOINC: 19868-9 FEV1/FVC.predicted]
     *   - Local-system problem [ICD-10: J45.9]
     * </pre>
     *
     * <p>LOINC source precedence: the entity's explicit {@code loincCode}
     * wins. Only when the entity carries no LOINC does the service fall
     * back to {@link ProblemLoincBindings} for the curated chronic-care
     * defaults. Invalid LOINC values are dropped silently — the regex
     * gate prevents malformed codes from leaking into the wire payload
     * while keeping the card itself useful.
     */
    private void appendCodings(StringBuilder sink, PatientProblem p) {
        String icd = trimToNull(p.getProblemCode());
        if (icd != null && TerminologyCodes.isValidIcd10(icd)) {
            sink.append(" [ICD-10: ").append(icd).append(']');
        }

        Optional<LoincCoding> loinc = resolveLoinc(p);
        loinc.ifPresent(coding -> {
            sink.append(" [LOINC: ").append(coding.code());
            if (coding.display() != null && !coding.display().isBlank()) {
                sink.append(' ').append(coding.display().trim());
            }
            sink.append(']');
        });
    }

    private Optional<LoincCoding> resolveLoinc(PatientProblem p) {
        // 1) Entity-specified LOINC takes priority.
        String explicit = trimToNull(p.getLoincCode());
        if (explicit != null && TerminologyCodes.isValidLoinc(explicit)) {
            return Optional.of(new LoincCoding(
                explicit, trimToNull(p.getLoincDisplay())));
        }
        // 2) Seed-table fallback for the chronic-care defaults.
        return ProblemLoincBindings.bindingFor(p.getProblemCode());
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safe(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred.trim();
        if (fallback != null && !fallback.isBlank()) return fallback.trim();
        return "(unspecified)";
    }
}
