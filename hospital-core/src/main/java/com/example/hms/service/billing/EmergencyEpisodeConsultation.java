package com.example.hms.service.billing;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.EncounterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Policy rule 6 (provisional, owner decision 2026-11-20): one consultation per episode. An emergency, urgent or
 * observation admission that follows an emergency visit already billed in the same episode gets NO second
 * consultation line on the stay's invoice: the consultation that decided the admission is the one the emergency
 * visit was billed for. The policy report finds no source for a second automatic consultation on admission.
 *
 * <p><b>The same episode.</b> The emergency visit of the same patient at the same hospital that led to the stay: not
 * a stay visit itself, begun no later than the admission, and ended on the day of the admission (the admission closes
 * the open emergency visit, {@code EncounterAutoCompletionService#completeVisitsThatLedToAdmission}; a visit checked
 * out by hand that day counts too). <b>Already billed:</b> that visit has a visit invoice that is not cancelled (its
 * check-in copay alone is not one); the visit invoice always carries the visit's recorded services or its default
 * consultation line ({@link ConsultationChargeSource}).
 *
 * <p>A direct emergency admission with no emergency visit before it keeps its one consultation line.
 */
@Component
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.EMERGENCY_EPISODE_CONSULTATION)
public class EmergencyEpisodeConsultation {

    /** The admissions a doctor first sees in the emergency department; the planned ones never get the line. */
    static final Set<AdmissionType> AFTER_AN_EMERGENCY_VISIT =
        EnumSet.of(AdmissionType.EMERGENCY, AdmissionType.URGENT, AdmissionType.OBSERVATION);

    private final EncounterRepository encounterRepository;
    private final BillingInvoiceRepository invoiceRepository;

    /** Whether the consultation of this stay's episode was already billed on the emergency visit that led to it. */
    public boolean alreadyBilledOnTheEmergencyVisit(Admission stay) {
        if (stay == null || stay.getAdmissionType() == null
            || !AFTER_AN_EMERGENCY_VISIT.contains(stay.getAdmissionType())
            || stay.getPatient() == null || stay.getHospital() == null || stay.getAdmissionDateTime() == null) {
            return false;
        }
        LocalDateTime admittedAt = stay.getAdmissionDateTime();
        // The admission time is the one the physician enters; it may be a little earlier than its recording (D760).
        LocalDateTime recordedAt = stay.getCreatedAt() != null ? stay.getCreatedAt() : admittedAt;
        LocalDateTime cutoff = admittedAt.isAfter(recordedAt) ? admittedAt : recordedAt;
        UUID patientId = stay.getPatient().getId();
        UUID hospitalId = stay.getHospital().getId();
        // A prescription written outside a visit hangs on an anchor row: never a visit, never a consultation.
        return encounterRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
            .filter(visit -> !visit.isPrescriptionAnchor())
            .anyMatch(visit -> ledToTheStay(visit, admittedAt, cutoff) && billed(visit));
    }

    /**
     * An emergency visit, not a stay visit, begun no later than the admission and ended on its day. A visit cancelled
     * or left before anybody saw the patient led to nothing.
     */
    static boolean ledToTheStay(Encounter visit, LocalDateTime admittedAt, LocalDateTime cutoff) {
        if (visit.getAdmissionId() != null || visit.getEncounterType() != EncounterType.EMERGENCY
            || EncounterStatus.ENDED_UNSEEN.contains(visit.getStatus())) {
            return false;
        }
        LocalDateTime began = visit.getEncounterDate();
        LocalDateTime ended = visit.getCheckoutTimestamp();
        if (began == null || ended == null || began.isAfter(cutoff)) {
            return false;
        }
        LocalDate day = ended.toLocalDate();
        return day.equals(admittedAt.toLocalDate()) || day.equals(cutoff.toLocalDate());
    }

    private boolean billed(Encounter visit) {
        return invoiceRepository.findByEncounter_IdAndStatusNot(visit.getId(), InvoiceStatus.CANCELLED).stream()
            .anyMatch(invoice -> !invoice.isCheckInCopay());
    }
}
