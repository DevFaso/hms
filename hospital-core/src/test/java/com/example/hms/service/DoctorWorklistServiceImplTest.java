package com.example.hms.service;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationUrgency;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterUrgency;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.SignatureStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Appointment;
import com.example.hms.model.Consultation;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.CriticalStripDTO;
import com.example.hms.payload.dto.clinical.DoctorWorklistItemDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.DigitalSignatureRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @Mock private LabResultRepository labResultRepository;
    @Mock private DigitalSignatureRepository digitalSignatureRepository;
    @Mock private PatientVitalSignRepository patientVitalSignRepository;
    @Mock private AdmissionRepository admissionRepository;

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

        // Critical labs (results flagged CRITICAL)
        when(labResultRepository.countByLabOrder_OrderingStaff_IdAndAbnormalFlag(staffId, AbnormalFlag.CRITICAL)).thenReturn(5L);

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
        assertEquals(5, result.getActiveSafetyAlertsCount()); // mirrors criticalLabs
    }

    @Test
    void getCriticalStrip_consultationQueryFails_shouldDefaultToZero() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        when(labResultRepository.countByLabOrder_OrderingStaff_IdAndAbnormalFlag(eq(staffId), any())).thenReturn(0L);
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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, "IN_PROGRESS", null, null);

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

        // buildWorklistItem() assigns "ROUTINE" urgency â€” filter for EMERGENT should exclude it
        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, "EMERGENT", null);

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, "ALL", null, null);

        assertEquals(1, result.size());
    }

    // ========== mapEncounterStatus / mapAppointmentStatus / urgencyRank branches ==========

    @Test
    void getWorklist_arrivedEncounter_shouldMapToCheckedIn() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Arrived", "Patient");
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.ARRIVED, LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(List.of(enc));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("CHECKED_IN", result.get(0).getEncounterStatus());
    }

    @Test
    void getWorklist_scheduledEncounter_shouldMapToScheduled() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Sched", "Patient");
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.SCHEDULED, LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(List.of(enc));
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("SCHEDULED", result.get(0).getEncounterStatus());
    }

    @Test
    void getWorklist_appointmentWithConfirmedStatus_shouldMapToCheckedIn() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Confirmed", "Appt");
        Appointment appt = mock(Appointment.class);
        lenient().when(appt.getPatient()).thenReturn(p);
        lenient().when(appt.getReason()).thenReturn("Follow-up");
        when(appt.getStatus()).thenReturn(AppointmentStatus.CONFIRMED);
        lenient().when(appt.getUpdatedAt()).thenReturn(null);
        lenient().when(appt.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(List.of(appt));
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("CHECKED_IN", result.get(0).getEncounterStatus());
        assertEquals("Follow-up", result.get(0).getChiefComplaint());
    }

    @Test
    void getWorklist_appointmentWithInProgressStatus_shouldMapToInProgress() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "InProg", "Appt");
        Appointment appt = mock(Appointment.class);
        lenient().when(appt.getPatient()).thenReturn(p);
        lenient().when(appt.getReason()).thenReturn("Checkup");
        when(appt.getStatus()).thenReturn(AppointmentStatus.IN_PROGRESS);
        lenient().when(appt.getUpdatedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(List.of(appt));
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("IN_PROGRESS", result.get(0).getEncounterStatus());
    }

    @Test
    void getWorklist_appointmentWithCompletedStatus_shouldMapToCompleted() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Done", "Appt");
        Appointment appt = mock(Appointment.class);
        lenient().when(appt.getPatient()).thenReturn(p);
        lenient().when(appt.getReason()).thenReturn("Finished");
        when(appt.getStatus()).thenReturn(AppointmentStatus.COMPLETED);
        lenient().when(appt.getUpdatedAt()).thenReturn(null);
        lenient().when(appt.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(List.of(appt));
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("COMPLETED", result.get(0).getEncounterStatus());
    }

    @Test
    void getWorklist_appointmentWithDefaultStatus_shouldMapToScheduled() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Unknown", "Appt");
        Appointment appt = mock(Appointment.class);
        lenient().when(appt.getPatient()).thenReturn(p);
        lenient().when(appt.getReason()).thenReturn("Misc");
        when(appt.getStatus()).thenReturn(AppointmentStatus.NO_SHOW);
        lenient().when(appt.getUpdatedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(List.of(appt));
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("SCHEDULED", result.get(0).getEncounterStatus());
    }

    @Test
    void getWorklist_appointmentNullPatient_shouldSkip() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Appointment appt = mock(Appointment.class);
        when(appt.getPatient()).thenReturn(null);

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(List.of(appt));
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void getWorklist_appointmentStatusFilter_shouldFilterByMappedStatus() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Filter", "Me");
        Appointment appt = mock(Appointment.class);
        lenient().when(appt.getPatient()).thenReturn(p);
        lenient().when(appt.getReason()).thenReturn("Test");
        when(appt.getStatus()).thenReturn(AppointmentStatus.CONFIRMED);
        lenient().when(appt.getUpdatedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(List.of(appt));
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        // CHECKED_IN should match CONFIRMED appointment
        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, "CHECKED_IN", null, null);
        assertEquals(1, result.size());

        // IN_PROGRESS should NOT match CONFIRMED appointment
        List<DoctorWorklistItemDTO> filtered = service.getWorklist(userId, "IN_PROGRESS", null, null);
        assertTrue(filtered.isEmpty());
    }

    @Test
    void getWorklist_appointmentWithNullDateOfBirth_shouldSetAgeZero() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "NoDob", "Patient");
        lenient().when(p.getDateOfBirth()).thenReturn(null);
        Appointment appt = mock(Appointment.class);
        lenient().when(appt.getPatient()).thenReturn(p);
        lenient().when(appt.getReason()).thenReturn("Visit");
        when(appt.getStatus()).thenReturn(AppointmentStatus.SCHEDULED);
        lenient().when(appt.getUpdatedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(List.of(appt));
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).getAge());
    }

    // ========== Consults in worklist ==========

    @Test
    void getWorklist_withPendingConsults_shouldAddConsultItems() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Consult", "Patient");
        Consultation consult = mock(Consultation.class);
        lenient().when(consult.getPatient()).thenReturn(p);
        lenient().when(consult.getReasonForConsult()).thenReturn("Cardiac eval");
        lenient().when(consult.getUrgency()).thenReturn(ConsultationUrgency.URGENT);
        lenient().when(consult.getRequestedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(consult));

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("CONSULTATION", result.get(0).getEncounterStatus());
        assertEquals("Cardiac eval", result.get(0).getChiefComplaint());
        assertEquals("URGENT", result.get(0).getUrgency());
    }

    @Test
    void getWorklist_consultWithNullUrgency_shouldDefaultToRoutine() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "NoUrg", "Patient");
        Consultation consult = mock(Consultation.class);
        lenient().when(consult.getPatient()).thenReturn(p);
        lenient().when(consult.getReasonForConsult()).thenReturn("Routine");
        lenient().when(consult.getUrgency()).thenReturn(null);
        lenient().when(consult.getRequestedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(consult));

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("ROUTINE", result.get(0).getUrgency());
    }

    @Test
    void getWorklist_consultWithNullPatient_shouldSkip() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Consultation consult = mock(Consultation.class);
        when(consult.getPatient()).thenReturn(null);

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(consult));

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void getWorklist_consultQueryException_shouldReturnOtherItems() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Alice", "Enc");
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.IN_PROGRESS, LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenThrow(new RuntimeException("DB error"));

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("Alice Enc", result.get(0).getPatientName());
    }

    @Test
    void getWorklist_statusFilterCONSULTS_shouldOnlyIncludeConsults() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient encPatient = stubPatient(UUID.randomUUID(), "Enc", "Pat");
        Encounter enc = stubEncounter(UUID.randomUUID(), encPatient, EncounterStatus.IN_PROGRESS, LocalDateTime.now());

        Patient consultPatient = stubPatient(UUID.randomUUID(), "Consult", "Pat");
        Consultation consult = mock(Consultation.class);
        lenient().when(consult.getPatient()).thenReturn(consultPatient);
        lenient().when(consult.getReasonForConsult()).thenReturn("Eval");
        lenient().when(consult.getUrgency()).thenReturn(ConsultationUrgency.ROUTINE);
        lenient().when(consult.getRequestedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(consult));

        // Filter for CONSULTS only — encounter should be excluded, consult included
        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, "CONSULTS", null, null);

        assertEquals(1, result.size());
        assertEquals("CONSULTATION", result.get(0).getEncounterStatus());
    }

    @Test
    void getWorklist_triageEncounter_shouldMapToTriage() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Triage", "Patient");
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.TRIAGE, LocalDateTime.now());

        lenient().when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.TRIAGE))
                .thenReturn(List.of(enc));
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("TRIAGE", result.get(0).getEncounterStatus());
    }

    @Test
    void getWorklist_waitingForPhysicianEncounter_shouldMapToWaiting() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Waiting", "Patient");
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.WAITING_FOR_PHYSICIAN, LocalDateTime.now());

        lenient().when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.WAITING_FOR_PHYSICIAN))
                .thenReturn(List.of(enc));
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("WAITING", result.get(0).getEncounterStatus());
    }

    @Test
    void getWorklist_encounterWithNullDateOfBirth_shouldSetAgeZero() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "NoDob", "Enc");
        lenient().when(p.getDateOfBirth()).thenReturn(null);
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.IN_PROGRESS, LocalDateTime.now());

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(0, result.get(0).getAge());
    }

    @Test
    void getWorklist_encounterWithNullEncounterDate_shouldSetWaitZero() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "NoDate", "Enc");
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.IN_PROGRESS, null);
        lenient().when(enc.getEncounterDate()).thenReturn(null);

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(0, result.get(0).getWaitMinutes());
    }

    @Test
    void getWorklist_encounterWithUpdatedAtNull_shouldFallbackToCreatedAt() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Updated", "Enc");
        LocalDateTime createdAt = LocalDateTime.of(2026, 3, 14, 8, 0);
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.IN_PROGRESS, LocalDateTime.now());
        lenient().when(enc.getUpdatedAt()).thenReturn(null);
        lenient().when(enc.getCreatedAt()).thenReturn(createdAt);

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals(createdAt, result.get(0).getUpdatedAt());
    }

    @Test
    void getCriticalStrip_encounterWithNullDate_shouldNotCountAsLongWait() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        when(labOrderRepository.countByOrderingStaff_IdAndStatus(eq(staffId), any())).thenReturn(0L);

        Encounter nullDateEnc = mock(Encounter.class);
        when(nullDateEnc.getEncounterDate()).thenReturn(null);
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(nullDateEnc));

        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());
        when(digitalSignatureRepository.countBySignedBy_IdAndStatus(staffId, SignatureStatus.PENDING)).thenReturn(0L);

        CriticalStripDTO result = service.getCriticalStrip(userId);

        assertEquals(0, result.getWaitingLongCount());
    }

    @Test
    void getWorklist_consultWithNullDob_shouldSetAgeZero() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "NoDob", "Consult");
        lenient().when(p.getDateOfBirth()).thenReturn(null);
        Consultation consult = mock(Consultation.class);
        lenient().when(consult.getPatient()).thenReturn(p);
        lenient().when(consult.getReasonForConsult()).thenReturn("Eval");
        lenient().when(consult.getUrgency()).thenReturn(ConsultationUrgency.EMERGENCY);
        lenient().when(consult.getRequestedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(consult));

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(0, result.get(0).getAge());
        assertEquals("EMERGENCY", result.get(0).getUrgency());
    }

    // ========== Branch coverage: admission room/bed, department, vitals, date ==========

    @Test
    void getWorklist_withActiveAdmission_shouldPopulateRoomAndBedFromSlash() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        UUID patientId = UUID.randomUUID();
        Patient p = stubPatient(patientId, "Room", "Patient");

        Admission adm = mock(Admission.class);
        when(adm.getStatus()).thenReturn(AdmissionStatus.ACTIVE);
        when(adm.getPatient()).thenReturn(p);
        when(adm.getRoomBed()).thenReturn("Room3/BedA");
        when(admissionRepository.findByAdmittingProviderIdOrderByAdmissionDateTimeDesc(staffId))
                .thenReturn(List.of(adm));

        Department dept = mock(Department.class);
        when(dept.getName()).thenReturn("Emergency");

        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.IN_PROGRESS, LocalDateTime.now().minusMinutes(10));
        lenient().when(enc.getDepartment()).thenReturn(dept);
        lenient().when(enc.getUrgency()).thenReturn(null);

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        lenient().when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        lenient().when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("Room3", result.get(0).getRoom());
        assertEquals("BedA", result.get(0).getBed());
        assertEquals("Emergency", result.get(0).getLocation());
    }

    @Test
    void getWorklist_admissionRoomWithoutSlash_shouldSetRoomOnly() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        UUID patientId = UUID.randomUUID();
        Patient p = stubPatient(patientId, "Single", "Room");

        Admission adm = mock(Admission.class);
        when(adm.getStatus()).thenReturn(AdmissionStatus.ACTIVE);
        when(adm.getPatient()).thenReturn(p);
        when(adm.getRoomBed()).thenReturn("Room7");
        when(admissionRepository.findByAdmittingProviderIdOrderByAdmissionDateTimeDesc(staffId))
                .thenReturn(List.of(adm));

        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.IN_PROGRESS, LocalDateTime.now());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        lenient().when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        lenient().when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals("Room7", result.get(0).getRoom());
        assertTrue(result.get(0).getBed() == null || result.get(0).getBed().isEmpty());
    }

    @Test
    void getWorklist_encounterWithVitals_shouldBuildSummary() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        UUID patientId = UUID.randomUUID();
        Patient p = stubPatient(patientId, "Vital", "Pat");

        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.IN_PROGRESS, LocalDateTime.now().minusMinutes(5));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        lenient().when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        lenient().when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        PatientVitalSign vital = mock(PatientVitalSign.class);
        when(vital.getHeartRateBpm()).thenReturn(78);
        when(vital.getSystolicBpMmHg()).thenReturn(120);
        when(vital.getDiastolicBpMmHg()).thenReturn(80);
        when(vital.getSpo2Percent()).thenReturn(98);
        when(patientVitalSignRepository.findFirstByPatient_IdOrderByRecordedAtDesc(patientId))
                .thenReturn(Optional.of(vital));

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertNotNull(result.get(0).getLatestVitalsSummary());
        assertTrue(result.get(0).getLatestVitalsSummary().contains("HR: 78"));
        assertTrue(result.get(0).getLatestVitalsSummary().contains("BP: 120/80"));
        assertTrue(result.get(0).getLatestVitalsSummary().contains("SpO2: 98%"));
    }

    @Test
    void getWorklist_vitalsQueryFails_shouldContinueNormally() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "VErr", "Pat");
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.IN_PROGRESS, LocalDateTime.now());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        lenient().when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        lenient().when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        when(patientVitalSignRepository.findFirstByPatient_IdOrderByRecordedAtDesc(any()))
                .thenThrow(new RuntimeException("DB error"));

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertNull(result.get(0).getLatestVitalsSummary());
    }

    @Test
    void getWorklist_withExplicitDate_shouldUseProvidedDate() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        LocalDate targetDate = LocalDate.of(2026, 3, 20);
        Patient p = stubPatient(UUID.randomUUID(), "Date", "Pat");
        Appointment appt = mock(Appointment.class);
        lenient().when(appt.getPatient()).thenReturn(p);
        lenient().when(appt.getStatus()).thenReturn(AppointmentStatus.SCHEDULED);
        lenient().when(appt.getReason()).thenReturn("Follow-up");
        lenient().when(appt.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(encounterRepository.findByStaff_IdAndStatus(eq(staffId), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(staffId, targetDate))
                .thenReturn(List.of(appt));
        lenient().when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, targetDate);

        assertEquals(1, result.size());
        assertEquals("Date Pat", result.get(0).getPatientName());
    }

    @Test
    void getWorklist_encounterWithExplicitUrgency_shouldUseIt() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        Patient p = stubPatient(UUID.randomUUID(), "Urgent", "Pat");
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.IN_PROGRESS, LocalDateTime.now());
        lenient().when(enc.getUrgency()).thenReturn(EncounterUrgency.EMERGENT);

        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        lenient().when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        lenient().when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals("EMERGENT", result.get(0).getUrgency());
    }

    @Test
    void getWorklist_admissionQueryFails_shouldContinueNormally() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        when(admissionRepository.findByAdmittingProviderIdOrderByAdmissionDateTimeDesc(staffId))
                .thenThrow(new RuntimeException("admission error"));

        Patient p = stubPatient(UUID.randomUUID(), "Adm", "Err");
        Encounter enc = stubEncounter(UUID.randomUUID(), p, EncounterStatus.IN_PROGRESS, LocalDateTime.now());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.ARRIVED))
                .thenReturn(Collections.emptyList());
        lenient().when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.SCHEDULED))
                .thenReturn(Collections.emptyList());
        lenient().when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        lenient().when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
    }

    // ========== MRN tests ==========

    @Test
    void getWorklist_mrnIsResolvedFromHospitalRegistration_notPatientId() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = stubStaff(staffId);
        givenStaffFor(userId, staff);

        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        // Real Patient so getMrnForHospital() works
        Hospital hospital = mock(Hospital.class);
        when(hospital.getId()).thenReturn(hospitalId);

        PatientHospitalRegistration reg = mock(PatientHospitalRegistration.class);
        when(reg.getHospital()).thenReturn(hospital);
        when(reg.getMrn()).thenReturn("MRN-TEST-001");

        Patient patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Alice");
        patient.setLastName("Wong");
        patient.setDateOfBirth(LocalDate.of(1985, 6, 15));
        patient.setGender("F");
        patient.setHospitalRegistrations(new HashSet<>(Set.of(reg)));

        Encounter enc = mock(Encounter.class);
        lenient().when(enc.getId()).thenReturn(UUID.randomUUID());
        lenient().when(enc.getPatient()).thenReturn(patient);
        lenient().when(enc.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(10));
        lenient().when(enc.getNotes()).thenReturn("headache");
        // Provide hospital so encHospitalId is resolved
        when(enc.getHospital()).thenReturn(hospital);

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

        List<DoctorWorklistItemDTO> result = service.getWorklist(userId, null, null, null);

        assertEquals(1, result.size());
        assertEquals("MRN-TEST-001", result.get(0).getMrn(),
                "MRN should come from hospital registration, not patient ID");
        assertNotNull(result.get(0).getMrn());
        // Ensure it's NOT the patient UUID string
        assertNotEquals(patientId.toString(), result.get(0).getMrn(),
                "MRN must not equal patient ID string");
    }
}
