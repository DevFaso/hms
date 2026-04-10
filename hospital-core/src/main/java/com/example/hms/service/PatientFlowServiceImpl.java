package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.PatientFlowItemDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatientFlowServiceImpl implements PatientFlowService {

    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final AdmissionRepository admissionRepository;

    /** Column order for the flow board (9 lanes matching frontend). */
    private static final List<String> FLOW_COLUMNS = List.of(
            "SCHEDULED", "ARRIVED", "TRIAGE", "WAITING_FOR_PHYSICIAN",
            "IN_PROGRESS", "AWAITING_RESULTS", "READY_FOR_DISCHARGE",
            "COMPLETED", "CANCELLED"
    );

    /** Admission status → flow lane mapping. */
    private static final Map<String, String> ADMISSION_STATUS_TO_COLUMN = Map.of(
            "ACTIVE",             "IN_PROGRESS",
            "ON_LEAVE",           "AWAITING_RESULTS",
            "AWAITING_DISCHARGE", "READY_FOR_DISCHARGE"
    );

    @Override
    public Map<String, List<PatientFlowItemDTO>> getPatientFlow(UUID userId) {
        log.debug("Building patient-flow board for user: {}", userId);

        Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        if (staffOpt.isEmpty()) {
            return Collections.emptyMap();
        }
        Staff staff = staffOpt.get();
        UUID staffId = staff.getId();

        // Initialise columns in display order
        Map<String, List<PatientFlowItemDTO>> flow = new LinkedHashMap<>();
        for (String col : FLOW_COLUMNS) {
            flow.put(col, new ArrayList<>());
        }

        // Populate from outpatient encounters (ENCOUNTER source)
        for (EncounterStatus es : EncounterStatus.values()) {
            String column = es.name();
            if (!flow.containsKey(column)) {
                flow.put(column, new ArrayList<>());
            }
            for (Encounter enc : encounterRepository.findByStaff_IdAndStatus(staffId, es)) {
                PatientFlowItemDTO item = buildEncounterFlowItem(enc, column);
                if (item != null) {
                    flow.get(column).add(item);
                }
            }
        }

        // Populate from inpatient admissions (ADMISSION source)
        for (Admission adm : admissionRepository.findActiveByAdmittingProvider(staffId)) {
            String column = ADMISSION_STATUS_TO_COLUMN.get(adm.getStatus().name());
            if (column == null) continue;
            PatientFlowItemDTO item = buildAdmissionFlowItem(adm, column);
            if (item != null) {
                flow.get(column).add(item);
            }
        }

        return flow;
    }

    private PatientFlowItemDTO buildEncounterFlowItem(Encounter enc, String column) {
        Patient p = enc.getPatient();
        if (p == null) return null;

        long elapsed = computeEncounterElapsedMinutes(enc);
        String derivedUrgency = deriveEncounterUrgency(enc, elapsed);

        return PatientFlowItemDTO.builder()
                .patientId(p.getId())
                .encounterId(enc.getId())
                .patientName(p.getFirstName() + " " + p.getLastName())
                .elapsedMinutes(elapsed)
                .urgency(derivedUrgency)
                .state(column)
                .flowSource("ENCOUNTER")
                .build();
    }

    private PatientFlowItemDTO buildAdmissionFlowItem(Admission adm, String column) {
        Patient p = adm.getPatient();
        if (p == null) return null;

        long elapsed = computeAdmissionElapsedMinutes(adm);
        String urgency = adm.getAcuityLevel() != null ? adm.getAcuityLevel().name() : "ROUTINE";

        return PatientFlowItemDTO.builder()
                .patientId(p.getId())
                .admissionId(adm.getId())
                .patientName(p.getFirstName() + " " + p.getLastName())
                .room(adm.getRoomBed())
                .elapsedMinutes(elapsed)
                .urgency(urgency)
                .state(column)
                .flowSource("ADMISSION")
                .build();
    }

    private long computeEncounterElapsedMinutes(Encounter enc) {
        if (enc.getEncounterDate() == null) return 0;
        return Math.max(Duration.between(enc.getEncounterDate(), LocalDateTime.now()).toMinutes(), 0);
    }

    private long computeAdmissionElapsedMinutes(Admission adm) {
        if (adm.getAdmissionDateTime() == null) return 0;
        return Math.max(Duration.between(adm.getAdmissionDateTime(), LocalDateTime.now()).toMinutes(), 0);
    }

    private String deriveEncounterUrgency(Encounter enc, long elapsedMinutes) {
        if (enc.getUrgency() != null) {
            return enc.getUrgency().name();
        }
        if (elapsedMinutes >= 60) return "EMERGENT";
        if (elapsedMinutes >= 30) return "URGENT";
        return "ROUTINE";
    }
}
