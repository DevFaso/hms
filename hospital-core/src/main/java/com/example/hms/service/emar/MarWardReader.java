package com.example.hms.service.emar;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.pharmacy.PrescriptionControlledResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * QA D1314: what the ward's lists read, for the whole ward in one go. The MAR
 * list, the medication tiles, the vitals round and the workboard each looped
 * over every registered patient and read that patient's prescriptions, stays,
 * dose rows and allergies one by one: 10,812 statements and 608 MB allocated
 * for one read of the MAR at dev's scale, and the container was killed under
 * that load. Each method here is one statement per {@value #IN_LIST_CHUNK} ids,
 * whatever the size of the ward.
 *
 * <p>No row is capped. {@link #ordersByPatient} narrows in SQL only to the
 * orders that can be on the MAR, a superset of
 * {@code NurseTaskServiceImpl.onWardMar}, which still decides each row: an
 * order is on the MAR only if it is active, and either its patient has a stay
 * here or it was written on an open emergency visit (QA D514, D702).
 * {@code WardLoadPostgresIT} proves the two agree.
 */
@Component
@RequiredArgsConstructor
public class MarWardReader {

    /** One IN list's size: well under PostgreSQL's limit on bind parameters. */
    static final int IN_LIST_CHUNK = 1_000;

    /** Only prescriptions in these statuses are on the MAR. */
    public static final Set<PrescriptionStatus> ON_MAR_STATUSES =
        Collections.unmodifiableSet(EnumSet.of(PrescriptionStatus.SIGNED, PrescriptionStatus.TRANSMITTED));

    private final PrescriptionRepository prescriptionRepository;
    private final AdmissionRepository admissionRepository;
    private final MedicationAdministrationRecordRepository marRepository;
    private final PatientAllergyRepository patientAllergyRepository;
    private final PatientVitalSignRepository vitalSignRepository;
    private final PrescriptionControlledResolver controlledResolver;

    /**
     * The orders that can be on this hospital's MAR, by patient, for the
     * patients in {@code scope}. A patient with none has no entry.
     */
    public Map<UUID, List<Prescription>> ordersByPatient(UUID hospitalId, Collection<UUID> scope) {
        Map<UUID, List<Prescription>> byPatient = new HashMap<>();
        if (hospitalId == null || scope.isEmpty()) {
            return byPatient;
        }
        Set<UUID> wanted = new LinkedHashSet<>(scope);
        for (Prescription rx : prescriptionRepository.findWardMarOrders(hospitalId, ON_MAR_STATUSES,
                EncounterType.EMERGENCY, EncounterStatus.CLOSED)) {
            UUID patientId = rx.getPatient().getId();
            if (wanted.contains(patientId)) {
                byPatient.computeIfAbsent(patientId, id -> new ArrayList<>()).add(rx);
            }
        }
        return byPatient;
    }

    /** The patients' discharged stays at this hospital (QA D136), by patient. */
    public Map<UUID, List<Admission>> closedStaysByPatient(UUID hospitalId, Collection<UUID> patientIds) {
        Map<UUID, List<Admission>> byPatient = new HashMap<>();
        for (List<UUID> chunk : chunks(patientIds)) {
            for (Admission stay : admissionRepository.findDischargedAtHospital(hospitalId, chunk)) {
                byPatient.computeIfAbsent(stay.getPatient().getId(), id -> new ArrayList<>()).add(stay);
            }
        }
        return byPatient;
    }

    /** Every dose row of each order, PENDING included, newest slot first: what {@code MarNextDose} reads. */
    public Map<UUID, List<MedicationAdministrationRecord>> doseRowsByPrescription(Collection<UUID> prescriptionIds) {
        Map<UUID, List<MedicationAdministrationRecord>> byOrder = new HashMap<>();
        for (List<UUID> chunk : chunks(prescriptionIds)) {
            for (MedicationAdministrationRecord row : marRepository.findByPrescriptionIdsNewestSlotFirst(chunk)) {
                byOrder.computeIfAbsent(row.getPrescription().getId(), id -> new ArrayList<>()).add(row);
            }
        }
        return byOrder;
    }

    /**
     * Every allergy row of each patient, at any hospital, as for the prescriber.
     * Deactivated rows included: {@code CrossReactivityAdvisory.card} skips them.
     */
    public Map<UUID, List<PatientAllergy>> allergiesByPatient(Collection<UUID> patientIds) {
        Map<UUID, List<PatientAllergy>> byPatient = new HashMap<>();
        for (List<UUID> chunk : chunks(patientIds)) {
            for (PatientAllergy allergy : patientAllergyRepository.findByPatient_IdIn(chunk)) {
                byPatient.computeIfAbsent(allergy.getPatient().getId(), id -> new ArrayList<>()).add(allergy);
            }
        }
        return byPatient;
    }

    /** The patients' latest observation set here, by patient (QA D1181: what the vitals round's clock runs from). */
    public Map<UUID, PatientVitalSign> latestObservationSets(UUID hospitalId, Collection<UUID> patientIds) {
        Map<UUID, PatientVitalSign> byPatient = new HashMap<>();
        for (List<UUID> chunk : chunks(patientIds)) {
            for (PatientVitalSign vitals : vitalSignRepository.findLatestObservationSets(hospitalId, chunk)) {
                byPatient.putIfAbsent(vitals.getPatient().getId(), vitals);
            }
        }
        return byPatient;
    }

    /** The ids among {@code orders} that are controlled, on the rule of {@link PrescriptionControlledResolver}. */
    public Set<UUID> controlledOrders(Collection<Prescription> orders) {
        return controlledResolver.controlledAmong(orders);
    }

    private static List<List<UUID>> chunks(Collection<UUID> ids) {
        List<UUID> all = new ArrayList<>(new LinkedHashSet<>(ids));
        List<List<UUID>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += IN_LIST_CHUNK) {
            chunks.add(all.subList(from, Math.min(from + IN_LIST_CHUNK, all.size())));
        }
        return chunks;
    }
}
