package com.example.hms.service;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.enums.SignatureStatus;
import com.example.hms.model.ChatMessage;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.ClinicalInboxItemDTO;
import com.example.hms.payload.dto.clinical.DoctorResultQueueItemDTO;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.DigitalSignatureRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResultReviewServiceImpl implements ResultReviewService {

    private static final String URGENCY_NORMAL = "NORMAL";

    private final StaffRepository staffRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ConsultationRepository consultationRepository;
    private final RefillRequestRepository refillRequestRepository;
    private final DigitalSignatureRepository digitalSignatureRepository;
    private final EncounterRepository encounterRepository;
    private final PrescriptionRepository prescriptionRepository;

    @Override
    public List<DoctorResultQueueItemDTO> getResultReviewQueue(UUID userId) {
        log.info("Building result review queue for user: {}", userId);

        Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        if (staffOpt.isEmpty()) {
            return Collections.emptyList();
        }
        Staff staff = staffOpt.get();
        UUID staffId = staff.getId();

        // Get completed lab orders (results available) ordered by this physician
        List<LabOrder> completedOrders = labOrderRepository.findByOrderingStaff_Id(staffId);
        List<DoctorResultQueueItemDTO> queue = new ArrayList<>();

        for (LabOrder order : completedOrders) {
            if (order.getStatus() != LabOrderStatus.COMPLETED) continue;
            if (order.getPatient() == null) continue;

            // Get results for this order
            List<LabResult> results = labResultRepository.findByLabOrder_Id(order.getId());
            for (LabResult result : results) {
                String testName = order.getLabTestDefinition() != null
                        ? order.getLabTestDefinition().getName()
                        : "Lab Test";

                String abnormalFlag;
                if (result.getAbnormalFlag() != null) {
                    abnormalFlag = result.getAbnormalFlag().name();
                } else {
                    abnormalFlag = result.isAcknowledged() ? AbnormalFlag.NORMAL.name() : AbnormalFlag.ABNORMAL.name();
                }

                queue.add(DoctorResultQueueItemDTO.builder()
                        .id(result.getId())
                        .patientName(order.getPatient().getFirstName() + " " + order.getPatient().getLastName())
                        .patientId(order.getPatient().getId())
                        .testName(testName)
                        .resultValue(result.getResultValue())
                        .abnormalFlag(abnormalFlag)
                        .resultedAt(result.getResultDate())
                        .orderingContext(order.getClinicalIndication())
                        .build());
            }
        }

        // Sort: CRITICAL → ABNORMAL → NORMAL, then by date desc
        queue.sort(Comparator
                .comparingInt((DoctorResultQueueItemDTO r) -> abnormalityRank(r.getAbnormalFlag())).reversed()
                .thenComparing(r -> r.getResultedAt() != null ? r.getResultedAt() : LocalDateTime.MIN, Comparator.reverseOrder()));

        return queue;
    }

    @Override
    public List<ClinicalInboxItemDTO> getInboxItems(UUID userId) {
        log.info("Building clinical inbox items for user: {}", userId);

        Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        if (staffOpt.isEmpty()) {
            return Collections.emptyList();
        }
        Staff staff = staffOpt.get();
        UUID staffId = staff.getId();
        List<ClinicalInboxItemDTO> items = new ArrayList<>();

        // 1. Unread messages — count only (no list query available)
        try {
            long unreadCount = chatMessageRepository.countByRecipient_IdAndReadFalse(userId);
            if (unreadCount > 0) {
                items.add(ClinicalInboxItemDTO.builder()
                        .id(UUID.randomUUID())
                        .category("MESSAGE")
                        .source("Chat")
                        .subject(unreadCount + " unread message" + (unreadCount > 1 ? "s" : ""))
                        .urgency(URGENCY_NORMAL)
                        .timestamp(LocalDateTime.now())
                        .actionType("REPLY")
                        .build());
            }
        } catch (Exception e) {
            log.debug("Chat message inbox query error: {}", e.getMessage());
        }

        // 2. Pending consult requests (where this doctor is consultant)
        try {
            consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED)
                    .forEach(consult -> {
                        items.add(ClinicalInboxItemDTO.builder()
                                .id(consult.getId())
                                .category("CONSULT_REQUEST")
                                .source(consult.getRequestingProvider() != null ? consult.getRequestingProvider().getFullName() : "Unknown")
                                .patientName(consult.getPatient() != null ? consult.getPatient().getFirstName() + " " + consult.getPatient().getLastName() : null)
                                .patientId(consult.getPatient() != null ? consult.getPatient().getId() : null)
                                .subject(consult.getReasonForConsult() != null ? truncate(consult.getReasonForConsult(), 80) : "Consultation Request")
                                .urgency(consult.getUrgency() != null ? mapConsultUrgency(consult.getUrgency().name()) : URGENCY_NORMAL)
                                .timestamp(consult.getRequestedAt())
                                .actionType("ACCEPT")
                                .build());
                    });
        } catch (Exception e) {
            log.debug("Consultation inbox query error: {}", e.getMessage());
        }

        // 3. Documents to sign — one inbox item per pending signature with document-type label
        try {
            digitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staffId, SignatureStatus.PENDING)
                    .forEach(sig -> {
                        String docLabel = formatSignatureType(sig.getReportType());
                        items.add(ClinicalInboxItemDTO.builder()
                                .id(sig.getId())
                                .category("DOCUMENT_TO_SIGN")
                                .source("System")
                                .subject(docLabel + " – awaiting your signature")
                                .urgency(URGENCY_NORMAL)
                                .timestamp(sig.getCreatedAt())
                                .actionType("SIGN")
                                .build());
                    });
        } catch (Exception e) {
            log.debug("Signature inbox query error: {}", e.getMessage());
        }

        // 4. Active encounters as tasks
        encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS).forEach(enc -> {
            items.add(ClinicalInboxItemDTO.builder()
                    .id(enc.getId())
                    .category("TASK")
                    .source("Encounter")
                    .patientName(enc.getPatient() != null ? enc.getPatient().getFirstName() + " " + enc.getPatient().getLastName() : null)
                    .patientId(enc.getPatient() != null ? enc.getPatient().getId() : null)
                    .subject("Active encounter in progress")
                    .urgency(URGENCY_NORMAL)
                    .timestamp(enc.getEncounterDate())
                    .actionType("OPEN_CHART")
                    .build());
        });

        // 5. Pharmacy clarification requests — prescriptions awaiting physician response
        try {
            long clarificationCount = prescriptionRepository.countByStaff_IdAndStatus(staffId, PrescriptionStatus.PENDING_CLARIFICATION);
            if (clarificationCount > 0) {
                items.add(ClinicalInboxItemDTO.builder()
                        .id(UUID.randomUUID())
                        .category("PHARMACY_CLARIFICATION")
                        .source("Pharmacy")
                        .subject(clarificationCount + " prescription" + (clarificationCount > 1 ? "s" : "") + (clarificationCount == 1 ? " needs clarification" : " need clarification"))
                        .urgency("HIGH")
                        .timestamp(LocalDateTime.now())
                        .actionType("REVIEW")
                        .build());
            }
        } catch (Exception e) {
            log.debug("Pharmacy clarification inbox query error: {}", e.getMessage());
        }

        // Sort by urgency desc then timestamp desc
        items.sort(Comparator
                .comparingInt((ClinicalInboxItemDTO i) -> inboxUrgencyRank(i.getUrgency())).reversed()
                .thenComparing(i -> i.getTimestamp() != null ? i.getTimestamp() : LocalDateTime.MIN, Comparator.reverseOrder()));

        return items;
    }

    private int abnormalityRank(String flag) {
        if (flag == null) return 0;
        return switch (flag.toUpperCase()) {
            case "CRITICAL" -> 3;
            case "ABNORMAL" -> 2;
            case URGENCY_NORMAL -> 1;
            default -> 0;
        };
    }

    private int inboxUrgencyRank(String urgency) {
        if (urgency == null) return 0;
        return switch (urgency.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case URGENCY_NORMAL -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private String mapConsultUrgency(String consultUrgency) {
        return switch (consultUrgency) {
            case "STAT", "EMERGENCY" -> "CRITICAL";
            case "URGENT" -> "HIGH";
            default -> URGENCY_NORMAL;
        };
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private String formatSignatureType(com.example.hms.enums.SignatureType type) {
        if (type == null) return "Document";
        return switch (type) {
            case DISCHARGE_SUMMARY  -> "Discharge Summary";
            case LAB_RESULT         -> "Lab Result";
            case IMAGING_REPORT     -> "Imaging Report";
            case OPERATIVE_NOTE     -> "Operative Note";
            case CONSULTATION_NOTE  -> "Consultation Note";
            case PROGRESS_NOTE      -> "Progress Note";
            case PROCEDURE_REPORT   -> "Procedure Report";
            case PATHOLOGY_REPORT   -> "Pathology Report";
            case ED_NOTE            -> "ED Note";
            case MEDICATION_ORDER   -> "Medication Order";
            default -> type.name().replace('_', ' ');
        };
    }
}
