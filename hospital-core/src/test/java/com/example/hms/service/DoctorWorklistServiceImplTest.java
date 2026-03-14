package com.example.hms.service;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.SignatureStatus;
import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.CriticalStripDTO;
import com.example.hms.payload.dto.clinical.DoctorWorklistItemDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.DigitalSignatureRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S100")
class DoctorWorklistServiceImplTest {

    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private ConsultationRepository consultationRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private DigitalSignatureRepository digitalSignatureRepository;

    @InjectMocks
    private DoctorWorklistServiceImpl service;

    // ========== Helpers ==========

    private Staff stubStaff(UUID staffId) {
        Staff staff = mock(Staff.class);
        when(staff.getId()).thenReturn(staffId);
        return staff;
    }

    private void givenStaffFor(UUID userId, Staff staff) {
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId))
                .thenReturn(Optional.of(staff));
    }

    private void givenNoStaffFor(UUID userId) {
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId))
                .thenReturn(Optional.empty());
    }

    private Patient stubPatient(UUID patientId, String firstName, String lastName) {
        Patient p = mock(Patient.class);
        lenient().when(p.getId()).thenReturn(patientId);
        lenient().when(p.getFirstName()).thenReturn(firstName);
        lenient().when(p.getLastName()).thenReturn(lastName);
        lenient().when(p.getDateOfBirth()).thenReturn(LocalDate.of(1985, 6, 15));
        lenient().when(p.getGender()).thenReturn("M");
        return p;
    }

    private Encounter stubEncounter(UUID encId, Patient patient, EncounterStatus status, LocalDateTime encounterDate) {
        Encounter enc = mock(Encounter.class);
        lenient().when(enc.getId()).thenReturn(encId);
        lenient().when(enc.getPatient()).thenReturn(patient);
        lenient().when(enc.getEncounterDate()).thenReturn(encounterDate);
        lenient().when(enc.getNotes()).thenReturn("Chief complaint");
        return enc;
    }

    // ========== getCriticalStrip() ==========

    @Test
    void getCriticalStrip_noStaff_shouldReturnEmptyDTO() {
        UUID userId = UUID.randomUUID();
        givenNoStaffFor(userId);

        CriticalStripDTO result = service.getCriticalStrip(userId);

        assertNotNull(result);
        assertEquals(0, result.getCriticalLabsCount());
        assertEquals(0, result.getWaitingLongCount());
        assertEquals(0, result.getPendingConsultsCount());
        assertEquals(0, result.getUnsignedNotesCount());
        assertEquals(0, result.getPendingOrderReviewCount());
        assertEquals(0, result.getActiveSafetyAlertsCount());
    }

    @Test
    void getCriticalStrip_withStaff_shouldAggregateCounts() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        // Critical labs (completed orders)
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.COMPLETED)).thenReturn(5L);

        // Waiting long: 2 encounters, 1 > 30 min
        Encounter longWait = mock(Encounter.class);
        when(longWait.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(45));
        Encounter shortWait = mock(Encounter.class);
        when(shortWait.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(10));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(longWait, shortWait));

        // Pending consults
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(mock(com.example.hms.model.Consultation.class), mock(com.example.hms.model.Consultation.class)));

        // Unsigned notes
        when(digitalSignatureRepository.countBySignedBy_IdAndStatus(staffId, SignatureStatus.PENDING)).thenReturn(3L);

        // Pending order review
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.PENDING)).thenReturn(2L);
        when(labOrderRepository.countByOrderingStaff_IdAndStatus(staffId, LabOrderStatus.IN_PROGRESS)).thenReturn(1L);

        CriticalStripDTO result = service.getCriticalStrip(userId);

        assertEquals(5, result.getCriticalLabsCount());
        assertEquals(1, result.getWaitingLongCount());
        assertEquals(2, result.getPendingConsultsCount());
        assertEquals(3, result.getUnsignedNotesCount());
        assertEquals(3, result.getPendingOrderReviewCount());
        assertEquals(0, result.getActiveSafetyAlertsCount());
    }

    @Test
    void getCriticalStrip_consultationQueryFails_shouldDefaultToZero() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        when(labOrderRepository.countByOrderingStaff_IdAndStatus(eq(staffId), any())).thenReturn(0L);
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenThrow(new RuntimeException("DB error"));
        when(digitalSignatureRepository.countBySignedBy_IdAndStatus(staffId, SignatureStatus.PENDING)).thenReturn(0L);

        CriticalStripDTO result = service.getCriticalStrip(userId);

        assertEquals(0, result.getPendingConsultsCount());
    }

    // ========== getWorklist() ==========

    @Test
    void getWorklist_noStaff_shouldReturnEmptyList() {
        UUID userId = UUID.randomUUID();
        givenNoStaffFor(userId);

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getWorklist_withActiveEncounters_shouldReturnItems() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId, "Alice", "Wong");
        Encounter enc = stubEncounter(UUID.randomUUID(), patient, EncounterStatus.IN_PROGRESS, LocalDateTime.now().minusMinutes(20));

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null);

        assertEquals(1, result.size());
        DoctorWorklistItemDTO item = result.get(0);
        assertEquals("Alice Wong", item.getPatientName());
        assertEquals(patientId, item.getPatientId());
        assertEquals("IN_PROGRESS", item.getEncounterStatus());
        assertNotNull(item.getWaitMinutes());
        assertTrue(item.getWaitMinutes() >= 0);
    }

    @Test
    void getWorklist_deduplicatesPatients() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        UUID patientId = UUID.randomUUID();
        Patient patient = stubPatient(patientId, "Bob", "Smith");

        Encounter enc1 = stubEncounter(UUID.randomUUID(), patient, EncounterStatus.IN_PROGRESS, LocalDateTime.now());
        Encounter enc2 = stubEncounter(UUID.randomUUID(), patient, EncounterStatus.ARRIVED, LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc1));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(List.of(enc2));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null);

        assertEquals(1, result.size(), "Same patient should appear only once");
    }

    @Test
    void getWorklist_filtersbyStatus() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p1 = stubPatient(UUID.randomUUID(), "Alice", "A");
        Patient p2 = stubPatient(UUID.randomUUID(), "Bob", "B");

        Encounter inProgress = stubEncounter(UUID.randomUUID(), p1, EncounterStatus.IN_PROGRESS, LocalDateTime.now());
        Encounter arrived = stubEncounter(UUID.randomUUID(), p2, EncounterStatus.ARRIVED, LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(inProgress));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(List.of(arrived));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, "IN_PROGRESS", null);

        assertEquals(1, result.size());
        assertEquals("IN_PROGRESS", result.get(0).getEncounterStatus());
    }

    @Test
    void getWorklist_filterByUrgency() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p1 = stubPatient(UUID.randomUUID(), "Urgent", "Patient");
        Encounter enc = stubEncounter(UUID.randomUUID(), p1, EncounterStatus.IN_PROGRESS, LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        // buildWorklistItem() assigns "ROUTINE" urgency — filter for EMERGENT should exclude it
        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, "EMERGENT");

        assertTrue(result.isEmpty(), "ROUTINE items should not appear when filtering by EMERGENT");
    }

    @Test
    void getWorklist_withAppointments_shouldIncludeScheduledPatients() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient patient = stubPatient(UUID.randomUUID(), "Carol", "Lee");
        Appointment appt = mock(Appointment.class);
        when(appt.getPatient()).thenReturn(patient);
        when(appt.getReason()).thenReturn("Annual checkup");
        when(appt.getStatus()).thenReturn(null);

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(List.of(appt));
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null);

        assertEquals(1, result.size());
        assertEquals("Annual checkup", result.get(0).getChiefComplaint());
    }

    @Test
    void getWorklist_skipsNullPatientEncounters() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Encounter enc = mock(Encounter.class);
        when(enc.getPatient()).thenReturn(null);

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void getWorklist_sortsByUrgencyDescThenWaitDesc() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        // Two encounters with same ROUTINE urgency but different wait times
        Patient p1 = stubPatient(UUID.randomUUID(), "Short", "Wait");
        Patient p2 = stubPatient(UUID.randomUUID(), "Long", "Wait");

        Encounter e1 = stubEncounter(UUID.randomUUID(), p1, EncounterStatus.IN_PROGRESS, LocalDateTime.now().minusMinutes(5));
        Encounter e2 = stubEncounter(UUID.randomUUID(), p2, EncounterStatus.IN_PROGRESS, LocalDateTime.now().minusMinutes(60));

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(e1, e2));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null);

        assertEquals(2, result.size());
        assertTrue(result.get(0).getWaitMinutes() >= result.get(1).getWaitMinutes(),
                "Longer wait should appear first");
    }

    @Test
    void getWorklist_statusFilterALL_shouldIncludeAllItems() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p1 = stubPatient(UUID.randomUUID(), "Alice", "A");
        Encounter enc = stubEncounter(UUID.randomUUID(), p1, EncounterStatus.IN_PROGRESS, LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, "ALL", null);

        assertEquals(1, result.size());
    }
}
