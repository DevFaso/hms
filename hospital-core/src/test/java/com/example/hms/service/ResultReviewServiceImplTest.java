package com.example.hms.service;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationUrgency;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.SignatureStatus;
import com.example.hms.model.Consultation;
import com.example.hms.model.signature.DigitalSignature;
import com.example.hms.model.Encounter;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.ClinicalInboxItemDTO;
import com.example.hms.payload.dto.clinical.DoctorResultQueueItemDTO;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.repository.DigitalSignatureRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S100")
class ResultReviewServiceImplTest {

    @Mock private StaffRepository staffRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private ConsultationRepository consultationRepository;
    @Mock private RefillRequestRepository refillRequestRepository;
    @Mock private DigitalSignatureRepository digitalSignatureRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private PrescriptionRepository prescriptionRepository;

    @InjectMocks
    private ResultReviewServiceImpl service;

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

    // ========== getResultReviewQueue() ==========

    @Test
    void getResultReviewQueue_noStaff_shouldReturnEmptyList() {
        UUID userId = UUID.randomUUID();
        givenNoStaffFor(userId);

        List<DoctorResultQueueItemDTO> result = service.getResultReviewQueue(userId);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getResultReviewQueue_withCompletedOrders_shouldMapResults() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        UUID patientId = UUID.randomUUID();
        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(patientId);
        when(patient.getFirstName()).thenReturn("Alice");
        when(patient.getLastName()).thenReturn("Wong");

        LabTestDefinition testDef = mock(LabTestDefinition.class);
        when(testDef.getName()).thenReturn("CBC");

        UUID orderId = UUID.randomUUID();
        LabOrder order = mock(LabOrder.class);
        when(order.getId()).thenReturn(orderId);
        when(order.getStatus()).thenReturn(LabOrderStatus.COMPLETED);
        when(order.getPatient()).thenReturn(patient);
        when(order.getLabTestDefinition()).thenReturn(testDef);
        when(order.getClinicalIndication()).thenReturn("Routine screening");

        UUID resultId = UUID.randomUUID();
        LabResult labResult = mock(LabResult.class);
        when(labResult.getId()).thenReturn(resultId);
        when(labResult.getResultValue()).thenReturn("12.5 g/dL");
        when(labResult.getAbnormalFlag()).thenReturn(AbnormalFlag.ABNORMAL);
        when(labResult.getResultDate()).thenReturn(LocalDateTime.now().minusHours(2));

        when(labOrderRepository.findByOrderingStaff_Id(staffId)).thenReturn(List.of(order));
        when(labResultRepository.findByLabOrder_Id(orderId)).thenReturn(List.of(labResult));

        List<DoctorResultQueueItemDTO> result = service.getResultReviewQueue(userId);

        assertEquals(1, result.size());
        DoctorResultQueueItemDTO item = result.get(0);
        assertEquals("Alice Wong", item.getPatientName());
        assertEquals(patientId, item.getPatientId());
        assertEquals("CBC", item.getTestName());
        assertEquals("12.5 g/dL", item.getResultValue());
        assertEquals("ABNORMAL", item.getAbnormalFlag());
        assertEquals("Routine screening", item.getOrderingContext());
    }

    @Test
    void getResultReviewQueue_acknowledgedResult_shouldFlagAsNormal() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        when(patient.getFirstName()).thenReturn("Bob");
        when(patient.getLastName()).thenReturn("Smith");

        LabOrder order = mock(LabOrder.class);
        UUID orderId = UUID.randomUUID();
        when(order.getId()).thenReturn(orderId);
        when(order.getStatus()).thenReturn(LabOrderStatus.COMPLETED);
        when(order.getPatient()).thenReturn(patient);
        when(order.getLabTestDefinition()).thenReturn(null);

        LabResult labResult = mock(LabResult.class);
        when(labResult.getId()).thenReturn(UUID.randomUUID());
        when(labResult.getResultValue()).thenReturn("Normal");
        when(labResult.getResultDate()).thenReturn(LocalDateTime.now());

        when(labOrderRepository.findByOrderingStaff_Id(staffId)).thenReturn(List.of(order));
        when(labResultRepository.findByLabOrder_Id(orderId)).thenReturn(List.of(labResult));

        List<DoctorResultQueueItemDTO> result = service.getResultReviewQueue(userId);

        assertEquals("NORMAL", result.get(0).getAbnormalFlag());
        assertEquals("Lab Test", result.get(0).getTestName()); // fallback when testDef is null
    }

    @Test
    void getResultReviewQueue_skipsNonCompletedOrders() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        LabOrder pendingOrder = mock(LabOrder.class);
        when(pendingOrder.getStatus()).thenReturn(LabOrderStatus.PENDING);

        when(labOrderRepository.findByOrderingStaff_Id(staffId)).thenReturn(List.of(pendingOrder));

        List<DoctorResultQueueItemDTO> result = service.getResultReviewQueue(userId);

        assertTrue(result.isEmpty());
    }

    @Test
    void getResultReviewQueue_skipsOrdersWithNullPatient() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        LabOrder order = mock(LabOrder.class);
        when(order.getStatus()).thenReturn(LabOrderStatus.COMPLETED);
        when(order.getPatient()).thenReturn(null);

        when(labOrderRepository.findByOrderingStaff_Id(staffId)).thenReturn(List.of(order));

        List<DoctorResultQueueItemDTO> result = service.getResultReviewQueue(userId);

        assertTrue(result.isEmpty());
    }

    @Test
    void getResultReviewQueue_sortsByAbnormalityDescThenDateDesc() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        when(patient.getFirstName()).thenReturn("Jane");
        when(patient.getLastName()).thenReturn("Doe");

        UUID orderId = UUID.randomUUID();
        LabOrder order = mock(LabOrder.class);
        when(order.getId()).thenReturn(orderId);
        when(order.getStatus()).thenReturn(LabOrderStatus.COMPLETED);
        when(order.getPatient()).thenReturn(patient);
        when(order.getLabTestDefinition()).thenReturn(null);

        // Two results: one NORMAL, one ABNORMAL
        LabResult normalResult = mock(LabResult.class);
        when(normalResult.getId()).thenReturn(UUID.randomUUID());
        when(normalResult.getResultValue()).thenReturn("Normal");
        when(normalResult.getAbnormalFlag()).thenReturn(AbnormalFlag.NORMAL);
        when(normalResult.getResultDate()).thenReturn(LocalDateTime.now());

        LabResult abnormalResult = mock(LabResult.class);
        when(abnormalResult.getId()).thenReturn(UUID.randomUUID());
        when(abnormalResult.getResultValue()).thenReturn("Critical");
        when(abnormalResult.getAbnormalFlag()).thenReturn(AbnormalFlag.ABNORMAL);
        when(abnormalResult.getResultDate()).thenReturn(LocalDateTime.now().minusHours(1));

        when(labOrderRepository.findByOrderingStaff_Id(staffId)).thenReturn(List.of(order));
        when(labResultRepository.findByLabOrder_Id(orderId)).thenReturn(List.of(normalResult, abnormalResult));

        List<DoctorResultQueueItemDTO> result = service.getResultReviewQueue(userId);

        assertEquals(2, result.size());
        assertEquals("ABNORMAL", result.get(0).getAbnormalFlag(), "Abnormal should sort first");
        assertEquals("NORMAL", result.get(1).getAbnormalFlag());
    }

    // ========== getInboxItems() ==========

    @Test
    void getInboxItems_noStaff_shouldReturnEmptyList() {
        UUID userId = UUID.randomUUID();
        givenNoStaffFor(userId);

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getInboxItems_withUnreadMessages_shouldAddMessageItem() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(3L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        assertFalse(result.isEmpty());
        ClinicalInboxItemDTO messageItem = result.stream()
                .filter(i -> "MESSAGE".equals(i.getCategory()))
                .findFirst()
                .orElse(null);
        assertNotNull(messageItem);
        assertEquals("3 unread messages", messageItem.getSubject());
        assertEquals("REPLY", messageItem.getActionType());
    }

    @Test
    void getInboxItems_withSingleUnreadMessage_shouldUseSingularLabel() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(1L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO messageItem = result.stream()
                .filter(i -> "MESSAGE".equals(i.getCategory()))
                .findFirst()
                .orElse(null);
        assertNotNull(messageItem);
        assertEquals("1 unread message", messageItem.getSubject());
    }

    @Test
    void getInboxItems_withPendingConsults_shouldAddConsultItems() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        when(patient.getFirstName()).thenReturn("Alice");
        when(patient.getLastName()).thenReturn("Wong");

        Staff requestingDoc = mock(Staff.class);
        when(requestingDoc.getFullName()).thenReturn("Dr. Smith");

        Consultation consult = mock(Consultation.class);
        when(consult.getId()).thenReturn(UUID.randomUUID());
        when(consult.getPatient()).thenReturn(patient);
        when(consult.getRequestingProvider()).thenReturn(requestingDoc);
        when(consult.getReasonForConsult()).thenReturn("Cardiac evaluation");
        when(consult.getUrgency()).thenReturn(ConsultationUrgency.URGENT);
        when(consult.getRequestedAt()).thenReturn(LocalDateTime.now().minusHours(1));

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(consult));
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO consultItem = result.stream()
                .filter(i -> "CONSULT_REQUEST".equals(i.getCategory()))
                .findFirst()
                .orElse(null);
        assertNotNull(consultItem);
        assertEquals("Alice Wong", consultItem.getPatientName());
        assertEquals("Dr. Smith", consultItem.getSource());
        assertEquals("Cardiac evaluation", consultItem.getSubject());
        assertEquals("HIGH", consultItem.getUrgency());
        assertEquals("ACCEPT", consultItem.getActionType());
    }

    @Test
    void getInboxItems_withPendingSignatures_shouldAddDocumentItems() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        DigitalSignature sig = mock(DigitalSignature.class);
        when(sig.getId()).thenReturn(UUID.randomUUID());
        when(sig.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(List.of(sig));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO sigItem = result.stream()
                .filter(i -> "DOCUMENT_TO_SIGN".equals(i.getCategory()))
                .findFirst()
                .orElse(null);
        assertNotNull(sigItem);
        assertEquals("SIGN", sigItem.getActionType());
    }

    @Test
    void getInboxItems_withActiveEncounters_shouldAddTaskItems() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        when(patient.getFirstName()).thenReturn("Bob");
        when(patient.getLastName()).thenReturn("Lee");

        Encounter enc = mock(Encounter.class);
        when(enc.getId()).thenReturn(UUID.randomUUID());
        when(enc.getPatient()).thenReturn(patient);
        when(enc.getEncounterDate()).thenReturn(LocalDateTime.now());

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO taskItem = result.stream()
                .filter(i -> "TASK".equals(i.getCategory()))
                .findFirst()
                .orElse(null);
        assertNotNull(taskItem);
        assertEquals("Bob Lee", taskItem.getPatientName());
        assertEquals("OPEN_CHART", taskItem.getActionType());
    }

    @Test
    void getInboxItems_chatQueryFails_shouldStillReturnOtherItems() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId))
                .thenThrow(new RuntimeException("DB error"));
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        assertNotNull(result);
    }

    @Test
    void getInboxItems_zeroUnreadMessages_shouldNotAddMessageItem() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        assertTrue(result.stream().noneMatch(i -> "MESSAGE".equals(i.getCategory())));
    }

    @Test
    void getInboxItems_consultWithStatUrgency_shouldMapToCritical() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Consultation consult = mock(Consultation.class);
        when(consult.getId()).thenReturn(UUID.randomUUID());
        when(consult.getPatient()).thenReturn(null);
        when(consult.getRequestingProvider()).thenReturn(null);
        when(consult.getReasonForConsult()).thenReturn(null);
        when(consult.getUrgency()).thenReturn(ConsultationUrgency.STAT);
        when(consult.getRequestedAt()).thenReturn(LocalDateTime.now());

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(consult));
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO consultItem = result.stream()
                .filter(i -> "CONSULT_REQUEST".equals(i.getCategory()))
                .findFirst()
                .orElse(null);
        assertNotNull(consultItem);
        assertEquals("CRITICAL", consultItem.getUrgency());
    }

    // ========== Additional coverage for branches ==========

    @Test
    void getInboxItems_consultWithEmergencyUrgency_shouldMapToCritical() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Consultation consult = mock(Consultation.class);
        when(consult.getId()).thenReturn(UUID.randomUUID());
        when(consult.getPatient()).thenReturn(null);
        when(consult.getRequestingProvider()).thenReturn(null);
        when(consult.getReasonForConsult()).thenReturn(null);
        when(consult.getUrgency()).thenReturn(ConsultationUrgency.EMERGENCY);
        when(consult.getRequestedAt()).thenReturn(LocalDateTime.now());

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(consult));
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO item = result.stream()
                .filter(i -> "CONSULT_REQUEST".equals(i.getCategory()))
                .findFirst().orElse(null);
        assertNotNull(item);
        assertEquals("CRITICAL", item.getUrgency());
        // null requestingProvider → "Unknown"
        assertEquals("Unknown", item.getSource());
        // null reason → "Consultation Request"
        assertEquals("Consultation Request", item.getSubject());
    }

    @Test
    void getInboxItems_consultWithRoutineUrgency_shouldMapToNormal() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        when(patient.getFirstName()).thenReturn("R");
        when(patient.getLastName()).thenReturn("P");

        Staff reqDoc = mock(Staff.class);
        when(reqDoc.getFullName()).thenReturn("Dr. Routine");

        Consultation consult = mock(Consultation.class);
        when(consult.getId()).thenReturn(UUID.randomUUID());
        when(consult.getPatient()).thenReturn(patient);
        when(consult.getRequestingProvider()).thenReturn(reqDoc);
        // Reason longer than 80 chars to test truncation
        String longReason = "A".repeat(100);
        when(consult.getReasonForConsult()).thenReturn(longReason);
        when(consult.getUrgency()).thenReturn(ConsultationUrgency.ROUTINE);
        when(consult.getRequestedAt()).thenReturn(LocalDateTime.now());

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(consult));
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO item = result.stream()
                .filter(i -> "CONSULT_REQUEST".equals(i.getCategory()))
                .findFirst().orElse(null);
        assertNotNull(item);
        assertEquals("NORMAL", item.getUrgency());
        // truncated subject
        assertTrue(item.getSubject().endsWith("..."));
        assertEquals(83, item.getSubject().length());
    }

    @Test
    void getInboxItems_consultWithNullUrgency_shouldDefaultToNormal() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Consultation consult = mock(Consultation.class);
        when(consult.getId()).thenReturn(UUID.randomUUID());
        when(consult.getPatient()).thenReturn(null);
        when(consult.getRequestingProvider()).thenReturn(null);
        when(consult.getReasonForConsult()).thenReturn("Short");
        when(consult.getUrgency()).thenReturn(null);
        when(consult.getRequestedAt()).thenReturn(LocalDateTime.now());

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(List.of(consult));
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO item = result.stream()
                .filter(i -> "CONSULT_REQUEST".equals(i.getCategory()))
                .findFirst().orElse(null);
        assertNotNull(item);
        assertEquals("NORMAL", item.getUrgency());
        assertEquals("Short", item.getSubject());
    }

    @Test
    void getInboxItems_consultQueryFails_shouldStillReturnOtherItems() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenThrow(new RuntimeException("DB error"));
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        assertNotNull(result);
    }

    @Test
    void getInboxItems_signatureQueryFails_shouldStillReturnOtherItems() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenThrow(new RuntimeException("DB error"));
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(Collections.emptyList());

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        assertNotNull(result);
    }

    @Test
    void getInboxItems_encounterWithNullPatient_shouldHandleGracefully() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Encounter enc = mock(Encounter.class);
        when(enc.getId()).thenReturn(UUID.randomUUID());
        when(enc.getPatient()).thenReturn(null);
        when(enc.getEncounterDate()).thenReturn(LocalDateTime.now());

        when(chatMessageRepository.countByRecipient_IdAndReadFalse(userId)).thenReturn(0L);
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED))
                .thenReturn(Collections.emptyList());
        when(digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS))
                .thenReturn(List.of(enc));

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO task = result.stream()
                .filter(i -> "TASK".equals(i.getCategory()))
                .findFirst().orElse(null);
        assertNotNull(task);
        // patientName should be null when patient is null
        assertEquals(null, task.getPatientName());
    }

    @Test
    void getResultReviewQueue_resultWithNullResultedAt_shouldSortLast() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        when(patient.getFirstName()).thenReturn("T");
        when(patient.getLastName()).thenReturn("P");

        UUID orderId = UUID.randomUUID();
        LabOrder order = mock(LabOrder.class);
        when(order.getId()).thenReturn(orderId);
        when(order.getStatus()).thenReturn(LabOrderStatus.COMPLETED);
        when(order.getPatient()).thenReturn(patient);
        when(order.getLabTestDefinition()).thenReturn(null);
        when(order.getClinicalIndication()).thenReturn(null);

        LabResult result1 = mock(LabResult.class);
        when(result1.getId()).thenReturn(UUID.randomUUID());
        when(result1.getResultValue()).thenReturn("5");
        when(result1.getResultDate()).thenReturn(null);

        LabResult result2 = mock(LabResult.class);
        when(result2.getId()).thenReturn(UUID.randomUUID());
        when(result2.getResultValue()).thenReturn("10");
        when(result2.getResultDate()).thenReturn(LocalDateTime.now());

        when(labOrderRepository.findByOrderingStaff_Id(staffId)).thenReturn(List.of(order));
        when(labResultRepository.findByLabOrder_Id(orderId)).thenReturn(List.of(result1, result2));

        List<DoctorResultQueueItemDTO> results = service.getResultReviewQueue(userId);

        assertEquals(2, results.size());
        // result with date should come first, null date last
        assertNotNull(results.get(0).getResultedAt());
    }

    @Test
    void getInboxItems_withPharmacyClarifications_shouldAddHighUrgencyItem() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        when(prescriptionRepository.countByStaff_IdAndStatus(staffId, PrescriptionStatus.PENDING_CLARIFICATION))
                .thenReturn(2L);

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO pharmItem = result.stream()
                .filter(i -> "PHARMACY_CLARIFICATION".equals(i.getCategory()))
                .findFirst().orElse(null);
        assertNotNull(pharmItem);
        assertEquals("HIGH", pharmItem.getUrgency());
        assertEquals("2 prescriptions need clarification", pharmItem.getSubject());
        assertEquals("REVIEW", pharmItem.getActionType());
        assertEquals("Pharmacy", pharmItem.getSource());
    }

    @Test
    void getInboxItems_withSinglePharmacyClarification_shouldUseSingularLabel() {
        UUID userId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        givenStaffFor(userId, stubStaff(staffId));

        when(prescriptionRepository.countByStaff_IdAndStatus(staffId, PrescriptionStatus.PENDING_CLARIFICATION))
                .thenReturn(1L);

        List<ClinicalInboxItemDTO> result = service.getInboxItems(userId);

        ClinicalInboxItemDTO pharmItem = result.stream()
                .filter(i -> "PHARMACY_CLARIFICATION".equals(i.getCategory()))
                .findFirst().orElse(null);
        assertNotNull(pharmItem);
        assertEquals("1 prescription need clarification", pharmItem.getSubject());
    }
}
