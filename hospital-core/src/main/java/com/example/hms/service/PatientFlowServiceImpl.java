package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.PatientFlowItemDTO;
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

    /** Column order for the flow board (7 lanes matching frontend). */
    private static final List<String> FLOW_COLUMNS = List.of(
            "ARRIVED", "IN_PROGRESS", "WAITING_FOR_PHYSICIAN",
            "AWAITING_RESULTS", "READY_FOR_DISCHARGE", "COMPLETED", "CANCELLED"
    );

    @Override
    public Map<String, List<PatientFlowItemDTO>> getPatientFlow(UUID userId) {
        log.info("Building patient-flow board for user: {}", userId);

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

        // Populate from encounters by status
        for (EncounterStatus es : EncounterStatus.values()) {
            String column = es.name();
            if (!flow.containsKey(column)) {
                flow.put(column, new ArrayList<>());
            }
            for (Encounter enc : encounterRepository.findByStaff_IdAndStatus(staffId, es)) {
                PatientFlowItemDTO item = buildFlowItem(enc, column);
                if (item != null) {
                    flow.get(column).add(item);
                }
            }
        }

        return flow;
    }

    private PatientFlowItemDTO buildFlowItem(Encounter enc, String column) {
        Patient p = enc.getPatient();
        if (p == null) return null;

        long elapsed = computeElapsedMinutes(enc);
        String derivedUrgency = deriveUrgency(enc, elapsed);

        return PatientFlowItemDTO.builder()
                .patientId(p.getId())
                .encounterId(enc.getId())
                .patientName(p.getFirstName() + " " + p.getLastName())
                .elapsedMinutes(elapsed)
                .urgency(derivedUrgency)
                .state(column)
                .build();
    }

    private long computeElapsedMinutes(Encounter enc) {
        if (enc.getEncounterDate() == null) return 0;
        return Math.max(Duration.between(enc.getEncounterDate(), LocalDateTime.now()).toMinutes(), 0);
    }

    private String deriveUrgency(Encounter enc, long elapsedMinutes) {
        if (enc.getUrgency() != null) {
            return enc.getUrgency().name();
        }
        if (elapsedMinutes >= 60) return "EMERGENT";
        if (elapsedMinutes >= 30) return "URGENT";
        return "ROUTINE";
    }
}
