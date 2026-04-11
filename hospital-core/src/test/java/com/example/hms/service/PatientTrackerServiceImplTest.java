package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterUrgency;
import com.example.hms.mapper.PatientTrackerMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.PatientTrackerBoardDTO;
import com.example.hms.payload.dto.clinical.PatientTrackerItemDTO;
import com.example.hms.repository.EncounterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PatientTrackerServiceImpl (MVP 5).
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S100")
class PatientTrackerServiceImplTest {

    @Mock
    private EncounterRepository encounterRepository;

    private PatientTrackerMapper trackerMapper;
    private PatientTrackerServiceImpl service;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID DEPT_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void setUp() {
        trackerMapper = new PatientTrackerMapper(); // real mapper (no external deps)
        service = new PatientTrackerServiceImpl(encounterRepository, trackerMapper);
    }

    // ── Helpers ─────────────────────────────────────────────

    private Encounter buildEncounter(UUID encId, EncounterStatus status,
                                     String firstName, String lastName,
                                     Department dept, UUID appointmentId,
                                     Boolean preCheckedIn) {
        Patient patient = mock(Patient.class);
        lenient().when(patient.getId()).thenReturn(UUID.randomUUID());
        lenient().when(patient.getFirstName()).thenReturn(firstName);
        lenient().when(patient.getLastName()).thenReturn(lastName);
        lenient().when(patient.getMrnForHospital(any(UUID.class))).thenReturn("MRN-" + firstName);

        User staffUser = mock(User.class);
        lenient().when(staffUser.getFirstName()).thenReturn("Dr");
        lenient().when(staffUser.getLastName()).thenReturn("Smith");

        Staff staff = mock(Staff.class);
        lenient().when(staff.getUser()).thenReturn(staffUser);

        Encounter enc = mock(Encounter.class);
        lenient().when(enc.getId()).thenReturn(encId);
        lenient().when(enc.getStatus()).thenReturn(status);
        lenient().when(enc.getPatient()).thenReturn(patient);
        lenient().when(enc.getStaff()).thenReturn(staff);
        lenient().when(enc.getDepartment()).thenReturn(dept);
        lenient().when(enc.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(20));
        lenient().when(enc.getArrivalTimestamp()).thenReturn(LocalDateTime.now().minusMinutes(15));
        lenient().when(enc.getEsiScore()).thenReturn(null);
        lenient().when(enc.getUrgency()).thenReturn(null);
        lenient().when(enc.getRoomAssignment()).thenReturn(null);

        if (appointmentId != null) {
            Appointment appt = mock(Appointment.class);
            lenient().when(appt.getId()).thenReturn(appointmentId);
            lenient().when(appt.getPreCheckedIn()).thenReturn(preCheckedIn);
            lenient().when(enc.getAppointment()).thenReturn(appt);
        }

        return enc;
    }

    private Department buildDepartment(UUID deptId, String name) {
        Department dept = mock(Department.class);
        lenient().when(dept.getId()).thenReturn(deptId);
        lenient().when(dept.getName()).thenReturn(name);
        return dept;
    }

    // ── Tests ───────────────────────────────────────────────

    @Test
    void getTrackerBoard_noEncounters_shouldReturnEmptyBoard() {
        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(Collections.emptyList());

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, null, TODAY);

        assertNotNull(board);
        assertEquals(0, board.getTotalPatients());
        assertEquals(0, board.getAverageWaitMinutes());
        assertTrue(board.getArrived().isEmpty());
        assertTrue(board.getTriage().isEmpty());
        assertTrue(board.getWaitingForPhysician().isEmpty());
        assertTrue(board.getInProgress().isEmpty());
        assertTrue(board.getAwaitingResults().isEmpty());
        assertTrue(board.getReadyForDischarge().isEmpty());
    }

    @Test
    void getTrackerBoard_shouldExcludeCompletedAndCancelled() {
        Encounter completed = buildEncounter(UUID.randomUUID(), EncounterStatus.COMPLETED,
                "Alice", "Comp", null, null, null);
        Encounter cancelled = buildEncounter(UUID.randomUUID(), EncounterStatus.CANCELLED,
                "Bob", "Cancel", null, null, null);
        Encounter active = buildEncounter(UUID.randomUUID(), EncounterStatus.ARRIVED,
                "Carol", "Active", null, null, null);

        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(List.of(completed, cancelled, active));

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, null, TODAY);

        assertEquals(1, board.getTotalPatients());
        assertEquals(1, board.getArrived().size());
        assertEquals("Carol Active", board.getArrived().get(0).getPatientName());
    }

    @Test
    void getTrackerBoard_shouldGroupByStatusLanes() {
        Department dept = buildDepartment(DEPT_ID, "Emergency");
        Encounter arrived = buildEncounter(UUID.randomUUID(), EncounterStatus.ARRIVED,
                "Arrived", "Patient", dept, null, null);
        Encounter triage = buildEncounter(UUID.randomUUID(), EncounterStatus.TRIAGE,
                "Triage", "Patient", dept, null, null);
        Encounter waiting = buildEncounter(UUID.randomUUID(), EncounterStatus.WAITING_FOR_PHYSICIAN,
                "Waiting", "Patient", dept, null, null);
        Encounter inProgress = buildEncounter(UUID.randomUUID(), EncounterStatus.IN_PROGRESS,
                "InProgress", "Patient", dept, null, null);
        Encounter awaitingResults = buildEncounter(UUID.randomUUID(), EncounterStatus.AWAITING_RESULTS,
                "Awaiting", "Patient", dept, null, null);
        Encounter readyForDischarge = buildEncounter(UUID.randomUUID(), EncounterStatus.READY_FOR_DISCHARGE,
                "Discharge", "Patient", dept, null, null);

        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(List.of(arrived, triage, waiting, inProgress, awaitingResults, readyForDischarge));

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, null, TODAY);

        assertEquals(6, board.getTotalPatients());
        assertEquals(1, board.getArrived().size());
        assertEquals(1, board.getTriage().size());
        assertEquals(1, board.getWaitingForPhysician().size());
        assertEquals(1, board.getInProgress().size());
        assertEquals(1, board.getAwaitingResults().size());
        assertEquals(1, board.getReadyForDischarge().size());
    }

    @Test
    void getTrackerBoard_shouldFilterByDepartment() {
        Department deptA = buildDepartment(DEPT_ID, "Cardiology");
        Department deptB = buildDepartment(UUID.randomUUID(), "Neurology");

        Encounter encA = buildEncounter(UUID.randomUUID(), EncounterStatus.IN_PROGRESS,
                "Alice", "Cardio", deptA, null, null);
        Encounter encB = buildEncounter(UUID.randomUUID(), EncounterStatus.IN_PROGRESS,
                "Bob", "Neuro", deptB, null, null);

        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(List.of(encA, encB));

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, DEPT_ID, TODAY);

        assertEquals(1, board.getTotalPatients());
        assertEquals("Alice Cardio", board.getInProgress().get(0).getPatientName());
    }

    @Test
    void getTrackerBoard_shouldDefaultToTodayWhenDateNull() {
        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(Collections.emptyList());

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, null, null);

        assertNotNull(board);
        assertEquals(0, board.getTotalPatients());
    }

    @Test
    void getTrackerBoard_shouldPopulateItemFieldsCorrectly() {
        Department dept = buildDepartment(DEPT_ID, "ER");
        UUID apptId = UUID.randomUUID();
        UUID encId = UUID.randomUUID();
        Encounter enc = buildEncounter(encId, EncounterStatus.TRIAGE,
                "Jane", "Doe", dept, apptId, true);
        // Override defaults for this specific test
        lenient().when(enc.getEsiScore()).thenReturn(3);
        lenient().when(enc.getRoomAssignment()).thenReturn("Room-5A");

        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(List.of(enc));

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, null, TODAY);

        assertEquals(1, board.getTriage().size());
        PatientTrackerItemDTO item = board.getTriage().get(0);
        assertEquals("Jane Doe", item.getPatientName());
        assertEquals("MRN-Jane", item.getMrn());
        assertEquals(encId, item.getEncounterId());
        assertEquals(apptId, item.getAppointmentId());
        assertEquals("TRIAGE", item.getCurrentStatus());
        assertEquals("Room-5A", item.getRoomAssignment());
        assertEquals("Dr Smith", item.getAssignedProvider());
        assertEquals("ER", item.getDepartmentName());
        assertEquals("ESI-3", item.getAcuityLevel());
        assertTrue(item.getPreCheckedIn());
        assertTrue(item.getCurrentWaitMinutes() >= 0);
    }

    @Test
    void getTrackerBoard_shouldComputeAverageWaitMinutes() {
        // Two patients with ~15 min wait each
        Encounter enc1 = buildEncounter(UUID.randomUUID(), EncounterStatus.ARRIVED,
                "P1", "Wait", null, null, null);
        Encounter enc2 = buildEncounter(UUID.randomUUID(), EncounterStatus.ARRIVED,
                "P2", "Wait", null, null, null);

        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(List.of(enc1, enc2));

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, null, TODAY);

        assertEquals(2, board.getTotalPatients());
        // Both have ~15 min wait (from mock), average should be ~15
        assertTrue(board.getAverageWaitMinutes() >= 10);
    }

    @Test
    void getTrackerBoard_urgencyFallbackWhenNoEsiScore() {
        Encounter enc = buildEncounter(UUID.randomUUID(), EncounterStatus.IN_PROGRESS,
                "Sam", "Urgent", null, null, null);
        // Override: no ESI score, but explicit urgency
        lenient().when(enc.getEsiScore()).thenReturn(null);
        when(enc.getUrgency()).thenReturn(EncounterUrgency.EMERGENT);

        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(List.of(enc));

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, null, TODAY);

        assertEquals("EMERGENT", board.getInProgress().get(0).getAcuityLevel());
    }

    @Test
    void getTrackerBoard_scheduledStatusGoesToArrivedLane() {
        Encounter enc = buildEncounter(UUID.randomUUID(), EncounterStatus.SCHEDULED,
                "Sched", "Pat", null, null, null);

        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(List.of(enc));

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, null, TODAY);

        assertEquals(1, board.getArrived().size());
        assertEquals("SCHEDULED", board.getArrived().get(0).getCurrentStatus());
    }

    @Test
    void getTrackerBoard_encounterWithNullDepartmentNotFilteredWhenNoDeptFilter() {
        Encounter enc = buildEncounter(UUID.randomUUID(), EncounterStatus.IN_PROGRESS,
                "NoDept", "Patient", null, null, null);

        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(List.of(enc));

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, null, TODAY);

        assertEquals(1, board.getTotalPatients());
    }

    @Test
    void getTrackerBoard_encounterWithNullDepartmentExcludedWhenDeptFilter() {
        Encounter enc = buildEncounter(UUID.randomUUID(), EncounterStatus.IN_PROGRESS,
                "NoDept", "Patient", null, null, null);

        when(encounterRepository.findAllByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any()))
                .thenReturn(List.of(enc));

        PatientTrackerBoardDTO board = service.getTrackerBoard(HOSPITAL_ID, DEPT_ID, TODAY);

        assertEquals(0, board.getTotalPatients());
    }
}
