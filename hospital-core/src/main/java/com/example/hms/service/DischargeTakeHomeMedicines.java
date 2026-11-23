package com.example.hms.service;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
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
 * discharged, when a summary is created for a stay, when a take-home order is signed, and when one is
 * withdrawn. Each order is listed once (by its id; an entry typed by hand with the same drug name counts
 * as listed). A finalized (signed) summary is never changed.
 *
 * <p>Review of #1276: the list follows the orders both ways. A take-home order stopped or cancelled
 * after it was listed leaves the (unfinalized) summary, so a stopped drug and its replacement are not
 * both read as going home. An entry that records the stop itself ({@code DISCONTINUED}) and an entry
 * typed by hand (no order id) are the clinician's, and stay.
 *
 * <p>An order written after the stay closed has no stay to sit on: it gets an outpatient anchor. A
 * take-home order of the same patient, at the same hospital, written within
 * {@link #AFTER_DISCHARGE_HOURS} hours of the discharge and before any later admission (open or already
 * closed), and not filed on another stay's encounter, belongs to that stay's summary. The window is an
 * operational choice (the discharge round and the pharmacy's hand-over), not a clinical rule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.TAKE_HOME_ORDER_WINDOW)
public class DischargeTakeHomeMedicines {

    /** How long after a discharge a take-home order still belongs to that stay's summary. */
    static final long AFTER_DISCHARGE_HOURS = 24;

    private final PrescriptionRepository prescriptionRepository;
    private final DischargeSummaryRepository dischargeSummaryRepository;
    private final EncounterRepository encounterRepository;
    private final AdmissionRepository admissionRepository;

    /**
     * Adds to {@code summary} every take-home order of its stay not listed yet, and removes the entry of
     * every take-home order of the stay that no longer goes home. The caller saves.
     *
     * @return whether the list changed
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
        boolean changed = false;
        for (Prescription rx : candidates) {
            if (goesHome(rx)) {
                if (!listed(summary, rx)) {
                    summary.addMedicationReconciliation(entryFor(rx));
                    changed = true;
                }
            } else if (rx != null && rx.isTakeHome()) {
                changed |= unlist(summary, rx);
            }
        }
        return changed;
    }

    /**
     * Review of #1276: the entry listing a take-home order that no longer goes home (stopped, cancelled)
     * is taken off the summary. An entry recording the stop ({@code DISCONTINUED}) is the clinician's
     * statement of it and stays; so does an entry with no order id.
     */
    private static boolean unlist(DischargeSummary summary, Prescription rx) {
        if (summary.getMedicationReconciliation() == null || rx.getId() == null) {
            return false;
        }
        return summary.getMedicationReconciliation().removeIf(e -> rx.getId().equals(e.getPrescriptionId())
            && e.getReconciliationAction() != MedicationReconciliationAction.DISCONTINUED);
    }

    /**
     * A take-home order was just signed: it joins its stay's summary, if one is written and still open.
     * Never fails the signature: the summary is the record of it, not its condition.
     */
    public void onSigned(Prescription rx) {
        refresh(rx, "signed");
    }

    /**
     * Review of #1276: a take-home order was stopped or cancelled: it leaves its stay's summary, if one is
     * written and still open. Never fails the withdrawal.
     */
    public void onWithdrawn(Prescription rx) {
        refresh(rx, "withdrawn");
    }

    private void refresh(Prescription rx, String event) {
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
            log.warn("Take-home order {} ({}) not carried into its discharge summary: {}", rx.getId(), event,
                ex.getMessage());
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

    /**
     * The patient's stay at the order's hospital discharged at most {@link #AFTER_DISCHARGE_HOURS} before
     * it was written. None when the patient was admitted again between that discharge and the order (the
     * later stay open or already closed: the order belongs to the later stay, not to the earlier one), or
     * when the order is filed on the encounter of another stay.
     */
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
        UUID filedOnStay = rx.getEncounter() != null ? rx.getEncounter().getAdmissionId() : null;
        return stays.stream()
            .filter(a -> a.getActualDischargeDateTime() != null && within(a.getActualDischargeDateTime(), written))
            .max(Comparator.comparing(Admission::getActualDischargeDateTime))
            .filter(ended -> !admittedAgainBetween(stays, ended, written))
            .filter(ended -> filedOnStay == null || filedOnStay.equals(ended.getId()));
    }

    /** Another admission began after {@code ended}'s discharge and no later than {@code written}. */
    private static boolean admittedAgainBetween(List<Admission> stays, Admission ended, LocalDateTime written) {
        LocalDateTime discharged = ended.getActualDischargeDateTime();
        return stays.stream().anyMatch(a -> !Objects.equals(a.getId(), ended.getId())
            && a.getAdmissionDateTime() != null && a.getAdmissionDateTime().isAfter(discharged)
            && !a.getAdmissionDateTime().isAfter(written));
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

    /**
     * The stay's admission, read among the patient's admissions at the encounter's own hospital, never
     * by its id alone: the encounter came from a tenant-checked flow, and its hospital scopes this read.
     */
    private Optional<Admission> stayOf(Encounter stay) {
        UUID admissionId = stay.getAdmissionId();
        if (admissionId == null || stay.getPatient() == null || stay.getHospital() == null) {
            return Optional.empty();
        }
        return admissionRepository.findByPatient_IdAndHospital_Id(stay.getPatient().getId(), stay.getHospital().getId())
            .stream().filter(a -> admissionId.equals(a.getId())).findFirst();
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
