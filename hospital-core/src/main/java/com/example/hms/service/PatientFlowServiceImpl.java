package com.example.hms.service;

import com.example.hms.utility.ElapsedTime;
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

    /** Column order for the flow board (10 lanes matching frontend). */
    private static final List<String> FLOW_COLUMNS = List.of(
            "SCHEDULED", "ARRIVED", "TRIAGE", "WAITING_FOR_PHYSICIAN",
            "IN_PROGRESS", "AWAITING_RESULTS", "READY_FOR_DISCHARGE",
            "COMPLETED", "CANCELLED", "LEFT_WITHOUT_BEING_SEEN"
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
        addEncounterItems(flow, staffId);

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

    private void addEncounterItems(Map<String, List<PatientFlowItemDTO>> flow, UUID staffId) {
        for (EncounterStatus es : EncounterStatus.values()) {
            String column = es.name();
            List<PatientFlowItemDTO> lane = flow.computeIfAbsent(column, k -> new ArrayList<>());
            for (Encounter enc : encounterRepository.findByStaff_IdAndStatus(staffId, es)) {
                // QA D791: a prescription's anchor (COMPLETED) is not a visit on the board.
                if (enc.isPrescriptionAnchor()) {
                    continue;
                }
                PatientFlowItemDTO item = buildEncounterFlowItem(enc, column);
                if (item != null) {
                    lane.add(item);
                }
            }
        }
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
                .encounterType(enc.getEncounterType() != null ? enc.getEncounterType().name() : null)
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
        // QA D990: the wait runs from the patient's arrival; a visit day's
        // midnight encounterDate showed "1128m" on the board.
        // Review of #971: with no arrival (a clinician's own IN_PROGRESS visit),
        // a visit entered after its day's midnight waits from its entry.
        LocalDateTime from = enc.getArrivalTimestamp() != null ? enc.getArrivalTimestamp()
            : laterOf(enc.getEncounterDate(), enc.getCreatedAt());
        if (from == null) return 0;
        return Math.max(ElapsedTime.minutesBetween(from, LocalDateTime.now(java.time.ZoneId.systemDefault())), 0);
    }

    private static LocalDateTime laterOf(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    private long computeAdmissionElapsedMinutes(Admission adm) {
        if (adm.getAdmissionDateTime() == null) return 0;
        return Math.max(ElapsedTime.minutesBetween(adm.getAdmissionDateTime(), LocalDateTime.now()), 0);
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
