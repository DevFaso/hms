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
import com.example.hms.model.Patient;
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
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResultReviewServiceImpl implements ResultReviewService {

    private static final String URGENCY_NORMAL = "NORMAL";
    /** Inbox action for an item the clinician opens and reads. */
    private static final String ACTION_REVIEW = "REVIEW";

    /** Inbox category for a pharmacy outcome on the prescriber's own order (gap G6). */
    static final String CATEGORY_PHARMACY_EVENT = "PHARMACY_EVENT";
    /** {@code Notification.type} written by PrescriberPharmacyNotificationWriter. */
    static final String PHARMACY_EVENT_NOTIFICATION_TYPE = "PHARMACY_EVENT";

    // Sonar S1192 (Pattern 5 of docs/SonarQubeInstructions.md): the
    // severity / urgency string SEVERITY_CRITICAL appears 3x in this file.
    // Naming follows the existing URGENCY_NORMAL sibling above.
    private static final String SEVERITY_CRITICAL = "CRITICAL";

    /**
     * Order states whose released results belong on a doctor's review queue.
     *
     * <p>COMPLETED alone was wrong once orders could move again: a late or
     * corrected result re-opens a COMPLETED order to RESULTED
     * ({@code LabOrderLifecycle.statusAfterNewResult}), and every result of that
     * order — including the ones released days ago — dropped out of the queue
     * until the new one was released. An order with at least one released
     * result is reviewable whatever stage it is at; the released-only filter
     * below is what decides which of its results the doctor sees.
     */
    private static final java.util.Set<LabOrderStatus> REVIEWABLE_ORDER_STATUSES =
            java.util.EnumSet.of(
                    LabOrderStatus.RESULTED,
                    LabOrderStatus.VERIFIED,
                    LabOrderStatus.COMPLETED);

    private final StaffRepository staffRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ConsultationRepository consultationRepository;
    private final RefillRequestRepository refillRequestRepository;
    private final DigitalSignatureRepository digitalSignatureRepository;
    private final EncounterRepository encounterRepository;
    private final com.example.hms.repository.EncounterNoteRepository encounterNoteRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final com.example.hms.repository.NotificationRepository notificationRepository;
    private final MessageSource messageSource;

    @Override
    public List<DoctorResultQueueItemDTO> getResultReviewQueue(UUID userId) {
        log.info("Building result review queue for user: {}", userId);

        Optional<Staff> staffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        if (staffOpt.isEmpty()) {
            return Collections.emptyList();
        }
        Staff staff = staffOpt.get();
        UUID staffId = staff.getId();
        // The queue is built for the physician making the request.
        Locale locale = LocaleContextHolder.getLocale();

        // Get completed lab orders (results available) ordered by this physician
        List<LabOrder> completedOrders = labOrderRepository.findByOrderingStaff_Id(staffId);
        List<DoctorResultQueueItemDTO> queue = new ArrayList<>();

        // Sonar S135 — replaced the outer for-loop (which had two `continue`
        // statements) with a stream filter chain. The inner result loop still
        // iterates over every LabResult row for each retained order, but it
        // contains no jump statements (no break/continue), so it does not
        // re-trigger S135.
        //
        // KNOWN PRE-EXISTING N+1: `findByLabOrder_Id` runs once per retained
        // order. Predates this refactor (the original for-loop had the same
        // call site). Fixing requires a bulk repo method (e.g. `findByLabOrder_IdIn`
        // or a JOIN FETCH on LabOrder + ordering staff) plus tests; scoped out
        // of this PR. Tracked for a follow-up "perf: bulk-fetch result queue"
        // commit. For a single physician's queue this stays bounded by the
        // staff-scoped completed-order count, which is small in practice.
        completedOrders.stream()
                .filter(order -> REVIEWABLE_ORDER_STATUSES.contains(order.getStatus()))
                .filter(order -> order.getPatient() != null)
                .forEach(order -> {
                    List<LabResult> results = labResultRepository.findByLabOrder_Id(order.getId());
                    for (LabResult result : results) {
                        // Only what the laboratory has released is the
                        // doctor's to review: a result entered on an order
                        // that had already completed is not on the chart yet.
                        if (result.isReleased()) {
                            queue.add(toQueueItem(order, result, locale));
                        }
                    }
                });

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
        // The inbox is rendered for the clinician making the request, so
        // every label below follows the request locale.
        Locale locale = LocaleContextHolder.getLocale();

        // 1. Unread messages — count only (no list query available)
        try {
            long unreadCount = chatMessageRepository.countByRecipient_IdAndReadFalse(userId);
            if (unreadCount > 0) {
                items.add(ClinicalInboxItemDTO.builder()
                        .id(UUID.randomUUID())
                        .category("MESSAGE")
                        .source(text("inbox.source.chat", locale))
                        .subject(text("inbox.messages.unread", locale, unreadCount))
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
                                .source(consult.getRequestingProvider() != null
                                        ? consult.getRequestingProvider().getFullName()
                                        : text("inbox.source.unknown", locale))
                                .patientName(consult.getPatient() != null ? consult.getPatient().getFirstName() + " " + consult.getPatient().getLastName() : null)
                                .patientId(consult.getPatient() != null ? consult.getPatient().getId() : null)
                                .subject(consult.getReasonForConsult() != null
                                        ? truncate(consult.getReasonForConsult(), 80)
                                        : text("inbox.consult.defaultSubject", locale))
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
                        String docLabel = formatSignatureType(sig.getReportType(), locale);
                        items.add(ClinicalInboxItemDTO.builder()
                                .id(sig.getId())
                                .category("DOCUMENT_TO_SIGN")
                                .source(text("inbox.source.system", locale))
                                .subject(text("inbox.signature.awaiting", locale, docLabel))
                                .urgency(URGENCY_NORMAL)
                                .timestamp(sig.getCreatedAt())
                                .actionType("SIGN")
                                .build());
                    });
        } catch (Exception e) {
            log.debug("Signature inbox query error: {}", e.getMessage());
        }

        // 3b. Encounter notes awaiting an attending co-signature (P3 #20).
        // Role-based queue: no staff-to-staff supervision relation exists, so
        // every clinician at the hospital sees the pending notes, minus their
        // own (self-cosign is refused by the ceremony anyway).
        try {
            UUID hospitalId = staff.getHospital() != null ? staff.getHospital().getId() : null;
            if (hospitalId != null) {
                encounterNoteRepository
                        .findByHospital_IdAndRequiresCosignTrueAndCosignedAtIsNullAndSignedAtIsNotNullOrderBySignedAtAsc(hospitalId)
                        .stream()
                        .filter(note -> note.getAuthor() == null || !userId.equals(note.getAuthor().getId()))
                        .forEach(note -> items.add(ClinicalInboxItemDTO.builder()
                                .id(note.getId())
                                .category("DOCUMENT_TO_SIGN")
                                .source(note.getAuthorName() != null
                                        ? note.getAuthorName()
                                        : text("inbox.source.encounterNote", locale))
                                .patientName(note.getPatient() != null
                                        ? note.getPatient().getFirstName() + " " + note.getPatient().getLastName()
                                        : null)
                                .patientId(note.getPatient() != null ? note.getPatient().getId() : null)
                                .subject(text("inbox.note.awaitingCosign", locale))
                                .urgency(URGENCY_NORMAL)
                                .timestamp(note.getSignedAt())
                                .actionType("SIGN")
                                .build()));
            }
        } catch (Exception e) {
            log.debug("Note co-sign inbox query error: {}", e.getMessage());
        }

        // 4. Active encounters as tasks
        encounterRepository.findByStaff_IdAndStatus(staffId, EncounterStatus.IN_PROGRESS).forEach(enc -> {
            items.add(ClinicalInboxItemDTO.builder()
                    .id(enc.getId())
                    .category("TASK")
                    .source(text("inbox.source.encounter", locale))
                    .patientName(enc.getPatient() != null ? enc.getPatient().getFirstName() + " " + enc.getPatient().getLastName() : null)
                    .patientId(enc.getPatient() != null ? enc.getPatient().getId() : null)
                    .subject(text("inbox.encounter.active", locale))
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
                        .source(text("inbox.source.pharmacy", locale))
                        .subject(text("inbox.pharmacy.clarification", locale, clarificationCount))
                        .urgency("HIGH")
                        .timestamp(LocalDateTime.now())
                        .actionType(ACTION_REVIEW)
                        .build());
            }
        } catch (Exception e) {
            log.debug("Pharmacy clarification inbox query error: {}", e.getMessage());
        }

        // 5b. Pharmacy outcomes on this prescriber's orders (gap G6): the
        //     unread PHARMACY_EVENT notifications, one item each, so a fill,
        //     a back order or a partner's refusal reaches the inbox and not
        //     only the bell. The portal groups on the category, so this is
        //     a new category rather than a second PHARMACY_CLARIFICATION
        //     count; the portal lists it in its category order.
        try {
            String username = staff.getUser() != null ? staff.getUser().getUsername() : null;
            if (username != null) {
                notificationRepository
                        .findByRecipientUsernameAndTypeAndReadFalseOrderByCreatedAtDesc(
                                username, PHARMACY_EVENT_NOTIFICATION_TYPE)
                        .forEach(n -> items.add(ClinicalInboxItemDTO.builder()
                                .id(n.getId())
                                .category(CATEGORY_PHARMACY_EVENT)
                                .source(text("inbox.source.pharmacy", locale))
                                .subject(n.getMessage() != null ? truncate(n.getMessage(), 160) : null)
                                .urgency(URGENCY_NORMAL)
                                .timestamp(n.getCreatedAt())
                                .actionType(ACTION_REVIEW)
                                .build()));
            }
        } catch (Exception e) {
            log.debug("Pharmacy event inbox query error: {}", e.getMessage());
        }

        // 6. Patient-initiated medication refill requests awaiting this prescriber's decision.
        //    PAUSED requests are deliberately excluded — the prescriber has already
        //    triaged those, so re-listing them would make the inbox un-clearable.
        try {
            refillRequestRepository
                    .findByPrescription_Staff_IdAndStatusOrderByCreatedAtDesc(staffId, RefillStatus.REQUESTED)
                    .forEach(refill -> {
                        String medication = refill.getPrescription() != null
                                && refill.getPrescription().getMedicationName() != null
                                ? refill.getPrescription().getMedicationName()
                                : text("inbox.refill.medicationFallback", locale);
                        Patient patient = refill.getPatient();
                        items.add(ClinicalInboxItemDTO.builder()
                                .id(refill.getId())
                                .category("REFILL_REQUEST")
                                .source(text("inbox.source.patientPortal", locale))
                                .patientName(patient != null
                                        ? patient.getFirstName() + " " + patient.getLastName() : null)
                                .patientId(patient != null ? patient.getId() : null)
                                .subject(text("inbox.refill.requested", locale, truncate(medication, 80)))
                                .urgency(URGENCY_NORMAL)
                                .timestamp(refill.getCreatedAt())
                                .actionType(ACTION_REVIEW)
                                .build());
                    });
        } catch (Exception e) {
            log.debug("Refill request inbox query error: {}", e.getMessage());
        }

        // Sort by urgency desc then timestamp desc
        items.sort(Comparator
                .comparingInt((ClinicalInboxItemDTO i) -> inboxUrgencyRank(i.getUrgency())).reversed()
                .thenComparing(i -> i.getTimestamp() != null ? i.getTimestamp() : LocalDateTime.MIN, Comparator.reverseOrder()));

        return items;
    }

    private String text(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    private DoctorResultQueueItemDTO toQueueItem(LabOrder order, LabResult result, Locale locale) {
        String testName = order.getLabTestDefinition() != null
                ? order.getLabTestDefinition().getName()
                : text("lab.test.fallback", locale);
        // The queue exposes the three-value family: the portal buckets on
        // the exact strings NORMAL/ABNORMAL/CRITICAL, direction lives on the row.
        String abnormalFlag = result.getAbnormalFlag() != null
                ? result.getAbnormalFlag().severity().name()
                : (result.isAcknowledged() ? AbnormalFlag.NORMAL.name() : AbnormalFlag.ABNORMAL.name());
        return DoctorResultQueueItemDTO.builder()
                .id(result.getId())
                .abnormalDirection(result.getAbnormalFlag() != null ? result.getAbnormalFlag().direction() : null)
                .patientName(order.getPatient().getFirstName() + " " + order.getPatient().getLastName())
                .patientId(order.getPatient().getId())
                .testName(testName)
                .resultValue(result.getResultValue())
                .abnormalFlag(abnormalFlag)
                .resultedAt(result.getResultDate())
                .orderingContext(order.getClinicalIndication())
                .build();
    }

    private int abnormalityRank(String flag) {
        if (flag == null) return 0;
        return switch (flag.toUpperCase()) {
            case SEVERITY_CRITICAL -> 3;
            case "ABNORMAL" -> 2;
            case URGENCY_NORMAL -> 1;
            default -> 0;
        };
    }

    private int inboxUrgencyRank(String urgency) {
        if (urgency == null) return 0;
        return switch (urgency.toUpperCase()) {
            case SEVERITY_CRITICAL -> 4;
            case "HIGH" -> 3;
            case URGENCY_NORMAL -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private String mapConsultUrgency(String consultUrgency) {
        return switch (consultUrgency) {
            case "STAT", "EMERGENCY" -> SEVERITY_CRITICAL;
            case "URGENT" -> "HIGH";
            default -> URGENCY_NORMAL;
        };
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * Label for a document type. Types without a {@code signature.type.*}
     * entry fall back to the enum name, as before.
     */
    private String formatSignatureType(com.example.hms.enums.SignatureType type, Locale locale) {
        if (type == null) {
            return text("signature.type.document", locale);
        }
        return messageSource.getMessage(
                "signature.type." + type.name(), null, type.name().replace('_', ' '), locale);
    }
}
