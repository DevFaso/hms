package com.example.hms.service.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.RhFactor;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.payload.dto.BloodGroupStatusDTO;
import com.example.hms.payload.dto.transfusion.AntibodyStatusResponseDTO;
import com.example.hms.repository.PatientBloodGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D3901 (r77, High): the one place that says what the patient's blood group IS on a screen.
 *
 * <p>Before it, the record showed {@code Patient.bloodType} (typed at registration or by a doctor's PATCH) in red
 * bold as if verified, never the laboratory's grouping, so a patient grouped B+ by the laboratory read « A+ » in the
 * header, the safety strip, the Dossier médical, the nurse station and the printed summary. Now every response that
 * carries the typed group carries a {@link BloodGroupStatusDTO} beside it, built here:
 * <ul>
 *   <li>the CONFIRMED group is read, never copied: the blood bank's current (not superseded) grouping at the acting
 *       hospital. A new grouping, a correction (which supersedes) and a repeat are therefore on the record the
 *       moment the laboratory saves them, and nothing typed on the chart can overwrite them;</li>
 *   <li>the DECLARED group is {@code Patient.bloodType}, unchanged and still editable by a doctor, nurse or midwife
 *       (a patient's card is useful information before the laboratory has grouped them), always labelled
 *       « déclaré (non vérifié) »;</li>
 *   <li>a disagreement is flagged, and the confirmed group wins on screen.</li>
 * </ul>
 * The allo-immunisation flag ({@link PositiveAntibodyScreenService}) is added on the reads of one chart only: it
 * follows the patient to every hospital, and each time another hospital's record is shown the read is accounted.
 */
@Service
@RequiredArgsConstructor
public class PatientBloodGroupStatusService {

    private final PatientBloodGroupRepository bloodGroupRepository;
    /** Lazy: the antibody service reaches the in-basket and the users; the mappers that call this must not. */
    private final ObjectProvider<PositiveAntibodyScreenService> antibodyScreen;

    /** Declared and confirmed, for a read at {@code hospitalId}. No hospital (an unscoped read): declared only. */
    public BloodGroupStatusDTO of(Patient patient, UUID hospitalId) {
        if (patient == null) {
            return null;
        }
        Optional<PatientBloodGroup> current = patient.getId() == null || hospitalId == null
            ? Optional.empty()
            : bloodGroupRepository.findCurrentWithHospital(patient.getId(), hospitalId);
        return build(patient.getBloodType(), current.orElse(null));
    }

    /**
     * {@link #of} with the allo-immunisation flag and the antibody identified, for the reads of one chart (the
     * patient record, the safety strip). The flag is read for the patient at every hospital.
     */
    public BloodGroupStatusDTO withAntibodies(Patient patient, UUID hospitalId) {
        BloodGroupStatusDTO status = of(patient, hospitalId);
        if (status == null || patient.getId() == null) {
            return status;
        }
        PositiveAntibodyScreenService screens = antibodyScreen.getIfAvailable();
        if (screens == null) {
            return status;
        }
        AntibodyStatusResponseDTO antibodies = screens.status(patient.getId(), hospitalId);
        status.setAlloImmunised(antibodies != null && antibodies.isAlloImmunised());
        if (antibodies != null && antibodies.isIdentified()) {
            status.setAntibodySpecificities(antibodies.getAntibodySpecificities());
        }
        return status;
    }

    /**
     * The patient's OWN record (patient portal), which belongs to no single hospital: the newest current grouping
     * of any hospital that grouped them. It is their own result.
     */
    public BloodGroupStatusDTO ofOwnRecord(Patient patient) {
        if (patient == null) {
            return null;
        }
        PatientBloodGroup newest = patient.getId() == null ? null
            : bloodGroupRepository.findCurrentAtEveryHospital(patient.getId()).stream().findFirst().orElse(null);
        return build(patient.getBloodType(), newest);
    }

    /** The declared group alone: the shape of a response built where no grouping can be looked up. */
    public static BloodGroupStatusDTO declaredOnly(String declared) {
        return build(declared, null);
    }

    private static BloodGroupStatusDTO build(String declaredRaw, PatientBloodGroup current) {
        String declared = normalise(declaredRaw);
        String confirmed = current == null ? null : label(current.getAboGroup(), current.getRhFactor());
        Hospital lab = current == null ? null : current.getHospital();
        return BloodGroupStatusDTO.builder()
            .declared(declared)
            .confirmed(confirmed)
            .confirmedAt(current == null ? null : current.getPerformedAt())
            .confirmedHospitalName(lab == null ? null : lab.getName())
            .discrepancy(declared != null && confirmed != null && !declared.equals(confirmed))
            .build();
    }

    /** « B+ », « O- »: the chart's notation, so a declared and a confirmed group compare as text. */
    public static String label(AboGroup abo, RhFactor rh) {
        if (abo == null || rh == null) {
            return null;
        }
        return abo.name() + (rh == RhFactor.POSITIVE ? "+" : "-");
    }

    private static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
