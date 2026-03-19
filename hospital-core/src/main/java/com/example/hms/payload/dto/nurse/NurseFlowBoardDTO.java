package com.example.hms.payload.dto.nurse;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Kanban-style patient flow board for the nurse unit.
 * Each list holds the patients currently in that flow stage.
 */
@Getter
@Builder
public class NurseFlowBoardDTO {

    /** Patients just admitted – pending initial nursing assessment. */
    private List<NurseFlowPatientCardDTO> pending;

    /** Active / stable in-patients under nursing care. */
    private List<NurseFlowPatientCardDTO> active;

    /** Acuity LEVEL_4 / LEVEL_5 – needs close watch. */
    private List<NurseFlowPatientCardDTO> critical;

    /** Waiting for discharge orders to be finalized. */
    private List<NurseFlowPatientCardDTO> awaitingDischarge;
}
