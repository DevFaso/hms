package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4220 (round 81), the guard of the class: a status the state machine lets nothing out of, or only a rebooking
 * (RESCHEDULED), is a visit that is over, and the edit path must refuse to move it. A status added to the machine as
 * terminal without being added to {@link AppointmentServiceImpl#CLOSED_TO_MOVES} fails here.
 */
class AppointmentClosedToMovesGuardTest {

    @Test
    void theClosedStatusesAreTheOnesTheStateMachineLetsNothingButARebookingOutOf() {
        Set<AppointmentStatus> closedByTheMachine = EnumSet.noneOf(AppointmentStatus.class);
        for (AppointmentStatus status : AppointmentStatus.values()) {
            Set<AppointmentStatus> next = AppointmentServiceImpl.VALID_TRANSITIONS.getOrDefault(status, Set.of());
            if (EnumSet.of(AppointmentStatus.RESCHEDULED).containsAll(next)) {
                closedByTheMachine.add(status);
            }
        }
        assertThat(AppointmentServiceImpl.CLOSED_TO_MOVES).containsExactlyInAnyOrderElementsOf(closedByTheMachine);
    }

    @Test
    void everyStatusHasAnEntryInTheStateMachine() {
        assertThat(AppointmentServiceImpl.VALID_TRANSITIONS.keySet()).containsExactlyInAnyOrder(AppointmentStatus.values());
    }
}
