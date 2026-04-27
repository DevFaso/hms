package com.example.hms.service.pharmacy;

import com.example.hms.enums.CdsAlertSeverity;
import com.example.hms.enums.InteractionSeverity;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.payload.dto.pharmacy.CdsAlertResult;
import com.example.hms.repository.DrugInteractionRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * P-08: Prospective CDS at dispense time.
 *
 * <p>Two checks are performed:
 * <ol>
 *   <li><b>Drug-drug interaction</b> — looks up known interactions between the
 *       prescription's drug code and every active prescription for the patient.
 *       The highest {@link InteractionSeverity} found drives the alert level.</li>
 *   <li><b>Therapeutic overlap</b> — flags WARNING when the patient already has
 *       an active prescription for the exact same drug code (likely double-dose).</li>
 * </ol>
 *
 * <p>The retrospective timeline view in {@code MedicationHistoryServiceImpl}
 * still produces the same kinds of findings — that retains its value for chart
 * review, but no longer needs to be the only line of defence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CdsCheckServiceImpl implements CdsCheckService {

    private static final Set<PrescriptionStatus> ACTIVE_STATUSES = Set.of(
            PrescriptionStatus.SIGNED,
            PrescriptionStatus.TRANSMITTED,
            PrescriptionStatus.PARTIALLY_FILLED,
            PrescriptionStatus.DISPENSED
    );

    private final DrugInteractionRepository drugInteractionRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final RoleValidator roleValidator;

    @Override
    public CdsAlertResult checkAtDispense(Prescription prescription, UUID patientId) {
        if (prescription == null || patientId == null) {
            return CdsAlertResult.clear();
        }

        String prescribedCode = normalize(prescription.getMedicationCode());
        if (prescribedCode == null) {
            // Cannot evaluate without a coded medication; surface as INFO so the
            // pharmacist is aware their software has no prospective check in this case.
            log.debug("Skipping CDS for prescription {} — no medication code", prescription.getId());
            return new CdsAlertResult(
                    CdsAlertSeverity.INFO,
                    List.of("Aucun code médicament — vérification CDS non disponible"),
                    false
            );
        }

        UUID hospitalId = prescription.getHospital() != null
                ? prescription.getHospital().getId()
                : roleValidator.requireActiveHospitalId();

        // Patient's currently active prescription set (excluding the one being dispensed).
        List<Prescription> active = prescriptionRepository
                .findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
                .filter(p -> p.getId() == null || !p.getId().equals(prescription.getId()))
                .filter(p -> p.getStatus() != null && ACTIVE_STATUSES.contains(p.getStatus()))
                .toList();

        Set<String> activeCodes = new HashSet<>();
        for (Prescription p : active) {
            String c = normalize(p.getMedicationCode());
            if (c != null) {
                activeCodes.add(c);
            }
        }

        List<String> alerts = new ArrayList<>();
        CdsAlertSeverity worst = CdsAlertSeverity.NONE;

        // Therapeutic overlap (same drug code already active).
        if (activeCodes.contains(prescribedCode)) {
            alerts.add("Chevauchement thérapeutique : "
                    + prescription.getMedicationName()
                    + " est déjà actif pour ce patient");
            worst = escalate(worst, CdsAlertSeverity.WARNING);
        }

        // Drug-drug interactions.
        Set<String> codesToCheck = new HashSet<>(activeCodes);
        codesToCheck.add(prescribedCode);
        if (codesToCheck.size() >= 2) {
            List<DrugInteraction> interactions = drugInteractionRepository
                    .findInteractionsAmongDrugs(new ArrayList<>(codesToCheck));
            for (DrugInteraction interaction : interactions) {
                // Only surface interactions involving the prescribed drug.
                if (!involvesPrescribed(interaction, prescribedCode)) {
                    continue;
                }
                alerts.add(formatInteractionAlert(interaction));
                worst = escalate(worst, mapSeverity(interaction.getSeverity()));
            }
        }

        boolean requiresOverride = worst == CdsAlertSeverity.CRITICAL;
        if (worst != CdsAlertSeverity.NONE) {
            log.info("CDS alert (severity={}) for prescription {} of patient {}",
                    worst, prescription.getId(), patientId);
        }
        return new CdsAlertResult(worst, List.copyOf(alerts), requiresOverride);
    }

    /** Normalises drug codes so case / whitespace differences don't break matches. */
    private static String normalize(String code) {
        if (code == null) return null;
        String trimmed = code.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase();
    }

    private static boolean involvesPrescribed(DrugInteraction interaction, String prescribedCode) {
        return prescribedCode.equals(normalize(interaction.getDrug1Code()))
                || prescribedCode.equals(normalize(interaction.getDrug2Code()));
    }

    private static String formatInteractionAlert(DrugInteraction interaction) {
        String desc = interaction.getDescription() != null && !interaction.getDescription().isBlank()
                ? interaction.getDescription()
                : "Interaction médicamenteuse détectée";
        return String.format("[%s] %s ↔ %s : %s",
                interaction.getSeverity(),
                interaction.getDrug1Name(),
                interaction.getDrug2Name(),
                desc);
    }

    private static CdsAlertSeverity mapSeverity(InteractionSeverity sev) {
        if (sev == null) return CdsAlertSeverity.INFO;
        return switch (sev) {
            case CONTRAINDICATED, MAJOR -> CdsAlertSeverity.CRITICAL;
            case MODERATE -> CdsAlertSeverity.WARNING;
            case MINOR, UNKNOWN -> CdsAlertSeverity.INFO;
        };
    }

    private static CdsAlertSeverity escalate(CdsAlertSeverity current, CdsAlertSeverity candidate) {
        return current.ordinal() >= candidate.ordinal() ? current : candidate;
    }
}
