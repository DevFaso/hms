package com.example.hms.service;

import com.example.hms.enums.MedicationReconciliationAction;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Prescription;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.model.discharge.MedicationReconciliationEntry;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D2553 (round 65): the medicines a patient goes home with are the stay's take-home orders
 * (« Traitement de sortie »), and the discharge summary is where the patient, the ward and the next
 * clinician read them. They were copied once, only when the discharge itself wrote the summary: a
 * summary written beforehand on the discharge screen, and a take-home order signed after the
 * discharge, left {@code medicationReconciliation} empty and the flag without effect.
 *
 * <p>This is the one place that carries them, every time one of them can change: when the stay is
 * discharged, when a summary is created for a stay, and when a take-home order is signed. Each order is
 * listed once (by its id; an entry typed by hand with the same drug name counts as listed). A finalized
 * (signed) summary is never changed.
 *
 * <p>An order written after the stay closed has no stay to sit on: it gets an outpatient anchor. A
 * take-home order of the same patient, at the same hospital, written within
 * {@link #AFTER_DISCHARGE_HOURS} hours of the discharge and before any later admission, belongs to
 * that stay's summary. The window is an operational choice (the discharge round and the pharmacy's
 * hand-over), not a clinical rule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DischargeTakeHomeMedicines {

    /** How long after a discharge a take-home order still belongs to that stay's summary. */
    static final long AFTER_DISCHARGE_HOURS = 24;

    private final PrescriptionRepository prescriptionRepository;
    private final DischargeSummaryRepository dischargeSummaryRepository;
    private final EncounterRepository encounterRepository;
    private final AdmissionRepository admissionRepository;

    /**
     * Adds to {@code summary} every take-home order of its stay not listed yet. The caller saves.
     *
     * @return whether an entry was added
     */
    public boolean carryInto(DischargeSummary summary) {
        if (summary == null || Boolean.TRUE.equals(summary.getIsFinalized()) || summary.getEncounter() == null
            || summary.getEncounter().getId() == null) {
            return false;
        }
        Encounter stay = summary.getEncounter();
        org.springframework.data.domain.Page<Prescription> onStay = prescriptionRepository
            .findByEncounter_Id(stay.getId(), PrescriptionRepository.EVERY_ORDER_OF_THE_VISIT);
        List<Prescription> candidates = new ArrayList<>(onStay == null ? List.of() : onStay.getContent());
        stayOf(stay).ifPresent(admission -> candidates.addAll(writtenJustAfter(admission)));
        boolean added = false;
        for (Prescription rx : candidates) {
            if (goesHome(rx) && !listed(summary, rx)) {
                summary.addMedicationReconciliation(entryFor(rx));
                added = true;
            }
        }
        return added;
    }

    /**
     * A take-home order was just signed: it joins its stay's summary, if one is written and still open.
     * Never fails the signature: the summary is the record of it, not its condition.
     */
    public void onSigned(Prescription rx) {
        if (rx == null || !rx.isTakeHome() || dischargeSummaryRepository == null) {
            return;
        }
        try {
            summaryFor(rx).filter(summary -> !Boolean.TRUE.equals(summary.getIsFinalized()))
                .ifPresent(summary -> {
                    if (carryInto(summary)) {
                        dischargeSummaryRepository.save(summary);
                    }
                });
        } catch (RuntimeException ex) {
            log.warn("Take-home order {} not carried into its discharge summary: {}", rx.getId(), ex.getMessage());
        }
    }

    /** The summary of the stay the order belongs to: its own encounter's, or the stay it was written just after. */
    private Optional<DischargeSummary> summaryFor(Prescription rx) {
        Encounter own = rx.getEncounter();
        if (own != null && own.getId() != null) {
            Optional<DischargeSummary> onOwn = dischargeSummaryRepository.findByEncounter_Id(own.getId());
            if (onOwn.isPresent() && own.getAdmissionId() != null) {
                return onOwn;
            }
        }
        return stayEndedJustBefore(rx)
            .flatMap(admission -> encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .filter(enc -> enc.getId() != null)
            .flatMap(enc -> dischargeSummaryRepository.findByEncounter_Id(enc.getId()));
    }

    /** The patient's stay at the order's hospital discharged at most {@link #AFTER_DISCHARGE_HOURS} before it was written. */
    private Optional<Admission> stayEndedJustBefore(Prescription rx) {
        UUID patientId = rx.getPatient() != null ? rx.getPatient().getId() : null;
        UUID hospitalId = rx.getHospital() != null ? rx.getHospital().getId() : null;
        LocalDateTime written = rx.getCreatedAt();
        if (patientId == null || hospitalId == null || written == null) {
            return Optional.empty();
        }
        List<Admission> stays = admissionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId);
        boolean admittedSince = stays.stream().anyMatch(a -> a.getActualDischargeDateTime() == null
            && a.getAdmissionDateTime() != null && !a.getAdmissionDateTime().isAfter(written));
        if (admittedSince) {
            return Optional.empty();
        }
        return stays.stream()
            .filter(a -> a.getActualDischargeDateTime() != null && within(a.getActualDischargeDateTime(), written))
            .max(Comparator.comparing(Admission::getActualDischargeDateTime));
    }

    /** The take-home orders of the patient at the stay's hospital written in the hours after its discharge. */
    private List<Prescription> writtenJustAfter(Admission admission) {
        LocalDateTime discharged = admission.getActualDischargeDateTime();
        if (discharged == null || admission.getPatient() == null || admission.getHospital() == null) {
            return List.of();
        }
        return prescriptionRepository.findByPatient_IdAndHospital_Id(admission.getPatient().getId(),
                admission.getHospital().getId()).stream()
            .filter(Prescription::isTakeHome)
            .filter(rx -> rx.getCreatedAt() != null && within(discharged, rx.getCreatedAt()))
            .filter(rx -> stayEndedJustBefore(rx).map(a -> Objects.equals(a.getId(), admission.getId())).orElse(false))
            .toList();
    }

    private Optional<Admission> stayOf(Encounter stay) {
        return stay.getAdmissionId() == null ? Optional.empty() : admissionRepository.findById(stay.getAdmissionId());
    }

    private static boolean within(LocalDateTime discharged, LocalDateTime written) {
        return !written.isBefore(discharged) && !written.isAfter(discharged.plusHours(AFTER_DISCHARGE_HOURS));
    }

    /** As at checkout (QA D152): a take-home order that is signed and not withdrawn. */
    static boolean goesHome(Prescription rx) {
        if (rx == null || !rx.isTakeHome()) {
            return false;
        }
        PrescriptionStatus status = rx.getStatus();
        return status != PrescriptionStatus.DRAFT && status != PrescriptionStatus.PENDING_SIGNATURE
            && (status == null || !status.isWithdrawn());
    }

    private static boolean listed(DischargeSummary summary, Prescription rx) {
        if (summary.getMedicationReconciliation() == null) {
            return false;
        }
        String name = folded(rx.getMedicationName());
        return summary.getMedicationReconciliation().stream().anyMatch(e -> e.getPrescriptionId() != null
            ? e.getPrescriptionId().equals(rx.getId())
            : !name.isEmpty() && name.equals(folded(e.getMedicationName())));
    }

    private static MedicationReconciliationEntry entryFor(Prescription rx) {
        return MedicationReconciliationEntry.builder()
            .medicationName(rx.getMedicationName())
            .dosage(rx.getDosage())
            .route(rx.getRoute())
            .frequency(rx.getFrequency())
            .reconciliationAction(MedicationReconciliationAction.CONTINUED)
            .continueAtDischarge(true)
            .prescriptionId(rx.getId())
            .patientInstructions(rx.getInstructions())
            .build();
    }

    private static String folded(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
