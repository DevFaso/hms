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

    /** Column order for the flow board. */
    private static final List<String> FLOW_COLUMNS = List.of(
            "ARRIVED", "IN_PROGRESS", "COMPLETED", "CANCELLED"
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
            String column = es.name(); // use enum name directly
            if (!flow.containsKey(column)) {
                flow.put(column, new ArrayList<>());
            }
            for (Encounter enc : encounterRepository.findByStaff_IdAndStatus(staffId, es)) {
                Patient p = enc.getPatient();
                if (p == null) continue;

                long elapsedMinutes = enc.getEncounterDate() != null
                        ? Duration.between(enc.getEncounterDate(), LocalDateTime.now()).toMinutes()
                        : 0;

                flow.get(column).add(PatientFlowItemDTO.builder()
                        .patientId(p.getId())
                        .encounterId(enc.getId())
                        .patientName(p.getFirstName() + " " + p.getLastName())
                        .elapsedMinutes(Math.max(elapsedMinutes, 0))
                        .urgency("ROUTINE")
                        .state(column)
                        .build());
            }
        }

        return flow;
    }
}
