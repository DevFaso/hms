package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.enums.SignatureStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.ClinicalAlertDTO;
import com.example.hms.payload.dto.clinical.ClinicalDashboardResponseDTO;
import com.example.hms.payload.dto.clinical.DashboardKPI;
import com.example.hms.payload.dto.clinical.InboxCountsDTO;
import com.example.hms.payload.dto.clinical.OnCallStatusDTO;
import com.example.hms.payload.dto.clinical.RoomedPatientDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.DigitalSignatureRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ClinicalDashboardServiceImpl.
 * All repository dependencies are mocked — no database required.
 */
@ExtendWith(MockitoExtension.class)
class ClinicalDashboardServiceImplTest {

    @Mock private StaffRepository staffRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private RefillRequestRepository refillRequestRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private DigitalSignatureRepository digitalSignatureRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private OnCallScheduleRepository onCallScheduleRepository;

    @InjectMocks
    private ClinicalDashboardServiceImpl service;

    // ========== Helpers ==========

    private UUID newUserId() {
        return UUID.randomUUID();
    }

    /** Returns a minimal Staff stub with a fixed id. */
    private Staff stubStaff(UUID staffId) {
        Staff staff = mock(Staff.class);
        when(staff.getId()).thenReturn(staffId);
        return staff;
    }

    /** Stubs staffRepository so the given userId resolves to the given staff. */
    private void givenStaffFor(UUID userId, Staff staff) {
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId))
                .thenReturn(Optional.of(staff));
    }

    /** Stubs staffRepository to return empty (no staff record for this user). */
    private void givenNoStaffFor(UUID userId) {
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId))
                .thenReturn(Optional.empty());
    }

    // ========== getClinicalDashboard() Tests ==========

    @Test
    void getClinicalDashboard_shouldReturnCompleteAggregatedData() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);

        ClinicalDashboardResponseDTO result = service.getClinicalDashboard(userId);

        assertNotNull(result, "Dashboard response should not be null");
        assertNotNull(result.getKpis(), "KPIs should not be null");
        assertNotNull(result.getAlerts(), "Alerts should not be null");
        assertNotNull(result.getInboxCounts(), "Inbox counts should not be null");
        assertNotNull(result.getOnCallStatus(), "On-call status should not be null");
        assertNotNull(result.getRoomedPatients(), "Roomed patients should not be null");
    }

    @Test
    void getClinicalDashboard_shouldIncludeKPIsWithExpectedStructure() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.PENDING)).thenReturn(2L);
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.IN_PROGRESS)).thenReturn(1L);
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        when(refillRequestRepository.countByPrescription_Staff_IdAndStatus(staffId, RefillStatus.REQUESTED)).thenReturn(3L);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(digitalSignatureRepository.countBySignedBy_IdAndStatus(staffId, SignatureStatus.PENDING)).thenReturn(0L);

        ClinicalDashboardResponseDTO result = service.getClinicalDashboard(userId);

        List<DashboardKPI> kpis = result.getKpis();
        assertFalse(kpis.isEmpty(), "Should have at least one KPI");
        DashboardKPI first = kpis.get(0);
        assertNotNull(first.getLabel(), "KPI should have a label");
        assertNotNull(first.getValue(), "KPI should have a value");
    }

    @Test
    void getClinicalDashboard_alertsAreEmptyListWhenNoAlertSystem() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);

        ClinicalDashboardResponseDTO result = service.getClinicalDashboard(userId);

        assertNotNull(result.getAlerts());
        // Real implementation returns empty list — no fake data
        assertTrue(result.getAlerts().isEmpty(), "No alert subsystem yet — list must be empty");
    }

    // ========== getCriticalAlerts() Tests ==========

    @Test
    void getCriticalAlerts_returnsEmptyListWhenNoAlertSystem() {
        UUID userId = newUserId();
        List<ClinicalAlertDTO> alerts = service.getCriticalAlerts(userId, 24);

        assertNotNull(alerts);
        assertTrue(alerts.isEmpty(), "No alert persistence layer — should return empty list");
    }

    @Test
    void getCriticalAlerts_withDifferentHoursParameter_alwaysReturnsEmptyList() {
        UUID userId = newUserId();
        assertTrue(service.getCriticalAlerts(userId, 1).isEmpty());
        assertTrue(service.getCriticalAlerts(userId, 72).isEmpty());
    }

    // ========== acknowledgeAlert() Tests ==========

    @Test
    void acknowledgeAlert_shouldNotThrowException() {
        UUID alertId = UUID.randomUUID();
        UUID userId = newUserId();

        assertDoesNotThrow(() -> service.acknowledgeAlert(alertId, userId),
                "Acknowledging alert should not throw exception");
    }

    @Test
    void acknowledgeAlert_withMultipleAlerts_shouldHandleAll() {
        UUID userId = newUserId();

        assertDoesNotThrow(() -> {
            service.acknowledgeAlert(UUID.randomUUID(), userId);
            service.acknowledgeAlert(UUID.randomUUID(), userId);
            service.acknowledgeAlert(UUID.randomUUID(), userId);
        }, "Should handle multiple acknowledgments without exception");
    }

    // ========== getInboxCounts() Tests ==========

    @Test
    void getInboxCounts_shouldReturnAllCountFields() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);

        InboxCountsDTO counts = service.getInboxCounts(userId);

        assertNotNull(counts);
        assertNotNull(counts.getUnreadMessages(), "Unread messages count should not be null");
        assertNotNull(counts.getPendingRefills(), "Pending refills count should not be null");
        assertNotNull(counts.getPendingResults(), "Pending results count should not be null");
        assertNotNull(counts.getTasksToComplete(), "Tasks to complete count should not be null");
        assertNotNull(counts.getDocumentsToSign(), "Documents to sign count should not be null");
    }

    @Test
    void getInboxCounts_shouldReturnNonNegativeValues() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);

        InboxCountsDTO counts = service.getInboxCounts(userId);

        assertTrue(counts.getUnreadMessages() >= 0, "Unread messages should be non-negative");
        assertTrue(counts.getPendingRefills() >= 0, "Pending refills should be non-negative");
        assertTrue(counts.getPendingResults() >= 0, "Pending results should be non-negative");
        assertTrue(counts.getTasksToComplete() >= 0, "Tasks to complete should be non-negative");
        assertTrue(counts.getDocumentsToSign() >= 0, "Documents to sign should be non-negative");
    }

    @Test
    void getInboxCounts_shouldReflectRealRepositoryValues() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(5L);
        when(refillRequestRepository.countByPrescription_Staff_IdAndStatus(staffId, RefillStatus.REQUESTED)).thenReturn(3L);
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.PENDING)).thenReturn(2L);
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.IN_PROGRESS)).thenReturn(1L);
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(mock(Encounter.class), mock(Encounter.class)));
        when(digitalSignatureRepository.countBySignedBy_IdAndStatus(staffId, SignatureStatus.PENDING)).thenReturn(4L);

        InboxCountsDTO counts = service.getInboxCounts(userId);

        assertEquals(5, counts.getUnreadMessages());
        assertEquals(3, counts.getPendingRefills());
        assertEquals(3, counts.getPendingResults());  // 2 PENDING + 1 IN_PROGRESS
        assertEquals(2, counts.getTasksToComplete()); // 2 in-progress encounters
        assertEquals(4, counts.getDocumentsToSign());
    }

    @Test
    void getInboxCounts_whenNoStaffRecord_allCountsAreZero() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);

        InboxCountsDTO counts = service.getInboxCounts(userId);

        assertEquals(0, counts.getUnreadMessages());
        assertEquals(0, counts.getPendingRefills());
        assertEquals(0, counts.getPendingResults());
        assertEquals(0, counts.getTasksToComplete());
        assertEquals(0, counts.getDocumentsToSign());
    }

    // ========== getRoomedPatients() Tests ==========

    @Test
    void getRoomedPatients_whenNoStaffRecord_returnsEmptyList() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);

        List<RoomedPatientDTO> patients = service.getRoomedPatients(userId);

        assertNotNull(patients);
        assertTrue(patients.isEmpty(), "No staff record => no roomed patients");
    }

    @Test
    void getRoomedPatients_whenNoActiveEncounters_returnsEmptyList() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<RoomedPatientDTO> patients = service.getRoomedPatients(userId);

        assertNotNull(patients);
        assertTrue(patients.isEmpty());
    }

    @Test
    void getRoomedPatients_mapsEncounterToDTO() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient patient = mock(Patient.class);
        UUID patientId = UUID.randomUUID();
        when(patient.getId()).thenReturn(patientId);
        when(patient.getFirstName()).thenReturn("Alice");
        when(patient.getLastName()).thenReturn("Smith");
        when(patient.getDateOfBirth()).thenReturn(LocalDate.of(1990, 1, 1));
        when(patient.getGender()).thenReturn("F");

        Encounter encounter = mock(Encounter.class);
        when(encounter.getId()).thenReturn(UUID.randomUUID());
        when(encounter.getPatient()).thenReturn(patient);
        when(encounter.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(30));
        when(encounter.getNotes()).thenReturn("Chest pain");

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(encounter));

        List<RoomedPatientDTO> patients = service.getRoomedPatients(userId);

        assertFalse(patients.isEmpty());
        RoomedPatientDTO dto = patients.get(0);
        assertEquals("Alice Smith", dto.getPatientName());
        assertEquals("Chest pain", dto.getChiefComplaint());
        assertTrue(dto.getWaitTimeMinutes() >= 0, "Wait time should be non-negative");
        assertTrue(dto.getWaitTimeMinutes() < 24 * 60, "Wait time should be < 24 h");
        assertNotNull(dto.getPrepStatus(), "PrepStatus should be set");
        assertNotNull(dto.getFlags(), "Flags should be non-null");
    }

    @Test
    void getRoomedPatients_skipsEncountersWithNullPatient() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Encounter enc = mock(Encounter.class);
        when(enc.getPatient()).thenReturn(null);

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));

        List<RoomedPatientDTO> patients = service.getRoomedPatients(userId);
        assertTrue(patients.isEmpty(), "Encounters with null patient should be skipped");
    }

    // ========== getOnCallStatus() Tests ==========

    @Test
    void getOnCallStatus_shouldReturnStatusObject() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);

        OnCallStatusDTO status = service.getOnCallStatus(userId);

        assertNotNull(status);
        assertNotNull(status.getIsOnCall(), "On-call flag should not be null");
    }

    @Test
    void getOnCallStatus_whenNoStaffRecord_shouldReturnNotOnCall() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);

        OnCallStatusDTO status = service.getOnCallStatus(userId);

        assertFalse(status.getIsOnCall(), "No staff record => not on call");
    }

    @Test
    void getOnCallStatus_whenNoActiveSchedule_shouldReturnNotOnCall() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));
        when(onCallScheduleRepository.findActiveByStaffIdAt(eq(staffId), any(java.time.OffsetDateTime.class)))
                .thenReturn(Collections.emptyList());

        OnCallStatusDTO status = service.getOnCallStatus(userId);

        assertFalse(status.getIsOnCall(), "No active schedule => not on call");
    }

    @Test
    void getOnCallStatus_whenActiveScheduleExists_shouldReturnOnCallWithTimes() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        com.example.hms.model.OnCallSchedule schedule = mock(com.example.hms.model.OnCallSchedule.class);
        java.time.OffsetDateTime start = java.time.OffsetDateTime.now().minusHours(1);
        java.time.OffsetDateTime end = java.time.OffsetDateTime.now().plusHours(7);
        when(schedule.getStartTime()).thenReturn(start);
        when(schedule.getEndTime()).thenReturn(end);
        when(onCallScheduleRepository.findActiveByStaffIdAt(eq(staffId), any(java.time.OffsetDateTime.class)))
                .thenReturn(List.of(schedule));

        OnCallStatusDTO status = service.getOnCallStatus(userId);

        assertTrue(status.getIsOnCall());
        assertEquals(start, status.getShiftStart());
        assertEquals(end, status.getShiftEnd());
    }

    // ========== generateKPIs() (via getClinicalDashboard) Tests ==========

    @Test
    void generateKPIs_returnsExactlyFourKPIs() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.PENDING)).thenReturn(0L);
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.IN_PROGRESS)).thenReturn(0L);
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        when(refillRequestRepository.countByPrescription_Staff_IdAndStatus(staffId, RefillStatus.REQUESTED)).thenReturn(0L);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(digitalSignatureRepository.countBySignedBy_IdAndStatus(staffId, SignatureStatus.PENDING)).thenReturn(0L);

        List<DashboardKPI> kpis = service.getClinicalDashboard(userId).getKpis();

        assertEquals(4, kpis.size(), "Should produce exactly 4 KPIs");
        for (DashboardKPI kpi : kpis) {
            assertNotNull(kpi.getLabel());
            assertNotNull(kpi.getValue());
            assertNotNull(kpi.getKey());
        }
    }

    @Test
    void generateKPIs_pendingResultsTrendUpWhenNonZero() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.PENDING)).thenReturn(2L);
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.IN_PROGRESS)).thenReturn(0L);
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        when(refillRequestRepository.countByPrescription_Staff_IdAndStatus(staffId, RefillStatus.REQUESTED)).thenReturn(0L);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(digitalSignatureRepository.countBySignedBy_IdAndStatus(staffId, SignatureStatus.PENDING)).thenReturn(0L);

        List<DashboardKPI> kpis = service.getClinicalDashboard(userId).getKpis();
        DashboardKPI pendingResults = kpis.stream()
                .filter(k -> "pending_results".equals(k.getKey()))
                .findFirst()
                .orElseThrow();

        assertEquals(2, pendingResults.getValue());
        assertEquals("up", pendingResults.getTrend());
    }

    // ========== Integration/Consistency Tests ==========

    @Test
    void getClinicalDashboard_shouldReturnConsistentData() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);

        ClinicalDashboardResponseDTO d1 = service.getClinicalDashboard(userId);
        ClinicalDashboardResponseDTO d2 = service.getClinicalDashboard(userId);

        assertEquals(d1.getKpis().size(), d2.getKpis().size(), "KPI count should be consistent");
        assertEquals(d1.getAlerts().size(), d2.getAlerts().size(), "Alert count should be consistent");
    }

    @Test
    void getClinicalDashboard_allComponents_shouldBeIndividuallyAccessible() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);

        assertNotNull(service.getClinicalDashboard(userId).getKpis());
        assertNotNull(service.getCriticalAlerts(userId, 24));
        assertNotNull(service.getInboxCounts(userId));
        assertNotNull(service.getRoomedPatients(userId));
        assertNotNull(service.getOnCallStatus(userId));
    }

    @Test
    void getClinicalDashboard_shouldNotReturnNullValues() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);
        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);

        ClinicalDashboardResponseDTO dashboard = service.getClinicalDashboard(userId);

        assertNotNull(dashboard.getKpis());
        assertNotNull(dashboard.getAlerts());
        assertNotNull(dashboard.getInboxCounts());
        assertNotNull(dashboard.getOnCallStatus());
        assertNotNull(dashboard.getRoomedPatients());

        for (DashboardKPI kpi : dashboard.getKpis()) {
            assertNotNull(kpi.getLabel());
            assertNotNull(kpi.getValue());
        }
    }

    // ========== getRecentPatients() Tests ==========

    @Test
    void getRecentPatients_whenNoStaffRecord_returnsEmptyList() {
        UUID userId = newUserId();
        givenNoStaffFor(userId);

        List<com.example.hms.patient.dto.PatientResponse> patients = service.getRecentPatients(userId);

        assertNotNull(patients);
        assertTrue(patients.isEmpty());
    }

    @Test
    void getRecentPatients_whenNoEncounters_returnsEmptyList() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));
        when(encounterRepository.findByStaff_Id(staffId)).thenReturn(Collections.emptyList());

        List<com.example.hms.patient.dto.PatientResponse> patients = service.getRecentPatients(userId);

        assertTrue(patients.isEmpty());
    }

    @Test
    void getRecentPatients_mapsEncounterToPatientResponse() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient patient = mock(Patient.class);
        UUID patientId = UUID.randomUUID();
        when(patient.getId()).thenReturn(patientId);
        when(patient.getFirstName()).thenReturn("Bob");
        when(patient.getLastName()).thenReturn("Jones");
        when(patient.getDateOfBirth()).thenReturn(LocalDate.of(1980, 5, 20));
        when(patient.getGender()).thenReturn("M");
        when(patient.getPhoneNumberPrimary()).thenReturn("555-4321");

        com.example.hms.model.Department dept = mock(com.example.hms.model.Department.class);
        when(dept.getName()).thenReturn("Cardiology");

        Encounter encounter = mock(Encounter.class);
        when(encounter.getPatient()).thenReturn(patient);
        when(encounter.getEncounterDate()).thenReturn(LocalDateTime.now().minusHours(2));
        when(encounter.getDepartment()).thenReturn(dept);

        when(encounterRepository.findByStaff_Id(staffId)).thenReturn(List.of(encounter));

        List<com.example.hms.patient.dto.PatientResponse> result = service.getRecentPatients(userId);

        assertFalse(result.isEmpty());
        assertEquals("Bob", result.get(0).getFirstName());
        assertEquals("Jones", result.get(0).getLastName());
        assertEquals("Cardiology", result.get(0).getLastLocation());
    }

    @Test
    void getRecentPatients_deduplicatesAndLimitsToSix() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        // Create 8 encounters with same patient — should deduplicate to 1
        Patient p = mock(Patient.class);
        UUID pid = UUID.randomUUID();
        when(p.getId()).thenReturn(pid);
        when(p.getFirstName()).thenReturn("Alice");
        when(p.getLastName()).thenReturn("Smith");

        List<Encounter> encounters = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Encounter enc = mock(Encounter.class);
            when(enc.getPatient()).thenReturn(p);
            when(enc.getEncounterDate()).thenReturn(LocalDateTime.now().minusDays(i));
            encounters.add(enc);
        }
        when(encounterRepository.findByStaff_Id(staffId)).thenReturn(encounters);

        List<com.example.hms.patient.dto.PatientResponse> result = service.getRecentPatients(userId);

        assertEquals(1, result.size(), "Same patient should appear only once");
    }

    @Test
    void getRecentPatients_skipsEncountersWithNullPatient() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Encounter enc = mock(Encounter.class);
        when(enc.getPatient()).thenReturn(null);
        lenient().when(enc.getEncounterDate()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_Id(staffId)).thenReturn(List.of(enc));

        List<com.example.hms.patient.dto.PatientResponse> result = service.getRecentPatients(userId);

        assertTrue(result.isEmpty());
    }

    @Test
    void getRecentPatients_handlesNullDepartmentGracefully() {
        UUID userId = newUserId();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        when(patient.getFirstName()).thenReturn("Eve");
        when(patient.getLastName()).thenReturn("Brown");

        Encounter encounter = mock(Encounter.class);
        when(encounter.getPatient()).thenReturn(patient);
        when(encounter.getEncounterDate()).thenReturn(LocalDateTime.now());
        when(encounter.getDepartment()).thenReturn(null);

        when(encounterRepository.findByStaff_Id(staffId)).thenReturn(List.of(encounter));

        List<com.example.hms.patient.dto.PatientResponse> result = service.getRecentPatients(userId);

        assertFalse(result.isEmpty());
        assertNull(result.get(0).getLastLocation());
    }
}
