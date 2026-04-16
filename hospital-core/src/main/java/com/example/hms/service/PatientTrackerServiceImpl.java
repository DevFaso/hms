package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.mapper.PatientTrackerMapper;
import com.example.hms.model.Encounter;
import com.example.hms.payload.dto.clinical.PatientTrackerBoardDTO;
import com.example.hms.payload.dto.clinical.PatientTrackerItemDTO;
import com.example.hms.repository.EncounterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregation service for the hospital-wide patient tracker board (MVP 5).
 * <p>
 * Queries today's encounters, excludes terminal statuses, groups into status lanes,
 * and computes wait-time metrics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatientTrackerServiceImpl implements PatientTrackerService {

    private final EncounterRepository encounterRepository;
    private final PatientTrackerMapper trackerMapper;

    /** Terminal statuses excluded from the active tracker board. */
    private static final Set<EncounterStatus> TERMINAL_STATUSES = EnumSet.of(
            EncounterStatus.COMPLETED,
            EncounterStatus.CANCELLED
    );

    @Override
    public PatientTrackerBoardDTO getTrackerBoard(UUID hospitalId, UUID departmentId, LocalDate date) {
        LocalDate queryDate = date != null ? date : LocalDate.now();
        LocalDateTime from = queryDate.atStartOfDay();
        LocalDateTime to = queryDate.atTime(LocalTime.MAX);
        LocalDateTime now = LocalDateTime.now();

        log.debug("Building tracker board for hospital={}, department={}, date={}",
                hospitalId, departmentId, queryDate);

        List<Encounter> allEncounters = new ArrayList<>(encounterRepository.findAllByHospitalAndDateRange(
                hospitalId, from, to));

        // Include carry-over encounters (active encounters from prior days)
        List<Encounter> carryOvers = encounterRepository.findCarryOverEncounters(
                hospitalId, from, TERMINAL_STATUSES);
        for (Encounter co : carryOvers) {
            if (allEncounters.stream().noneMatch(e -> e.getId().equals(co.getId()))) {
                allEncounters.add(co);
            }
        }

        // Filter: exclude terminal statuses, optionally filter by department
        List<PatientTrackerItemDTO> activeItems = new ArrayList<>();
        for (Encounter enc : allEncounters) {
            if (enc.getStatus() == null || TERMINAL_STATUSES.contains(enc.getStatus())
                    || (departmentId != null && (enc.getDepartment() == null
                            || !departmentId.equals(enc.getDepartment().getId())))) {
                continue;
            }
            PatientTrackerItemDTO item = trackerMapper.toTrackerItem(enc, hospitalId, now);
            if (item != null) {
                activeItems.add(item);
            }
        }

        // Group into status lanes
        List<PatientTrackerItemDTO> arrived = new ArrayList<>();
        List<PatientTrackerItemDTO> triage = new ArrayList<>();
        List<PatientTrackerItemDTO> waitingForPhysician = new ArrayList<>();
        List<PatientTrackerItemDTO> inProgress = new ArrayList<>();
        List<PatientTrackerItemDTO> awaitingResults = new ArrayList<>();
        List<PatientTrackerItemDTO> readyForDischarge = new ArrayList<>();

        for (PatientTrackerItemDTO item : activeItems) {
            switch (item.getCurrentStatus()) {
                case "ARRIVED" -> arrived.add(item);
                case "TRIAGE" -> triage.add(item);
                case "WAITING_FOR_PHYSICIAN" -> waitingForPhysician.add(item);
                case "IN_PROGRESS" -> inProgress.add(item);
                case "AWAITING_RESULTS" -> awaitingResults.add(item);
                case "READY_FOR_DISCHARGE" -> readyForDischarge.add(item);
                // SCHEDULED or any other non-terminal status goes to arrived lane
                default -> arrived.add(item);
            }
        }

        int total = activeItems.size();
        long avgWait = total > 0
                ? activeItems.stream().mapToLong(PatientTrackerItemDTO::getCurrentWaitMinutes).sum() / total
                : 0;

        return PatientTrackerBoardDTO.builder()
                .arrived(arrived)
                .triage(triage)
                .waitingForPhysician(waitingForPhysician)
                .inProgress(inProgress)
                .awaitingResults(awaitingResults)
                .readyForDischarge(readyForDischarge)
                .totalPatients(total)
                .averageWaitMinutes(avgWait)
                .build();
    }
}
