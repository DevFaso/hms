package com.example.hms.service.notification;

import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.payload.dto.notification.PatientNotificationDeliveryDTO;
import com.example.hms.payload.dto.notification.PatientNotificationDeliveryPageDTO;
import com.example.hms.repository.PatientNotificationDeliveryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The hospital administrators' view of patient notification sends (V312, scorecard K3): what
 * failed, why, and a manual retry.
 *
 * <p>Pinned to one hospital ({@link ActingScopeResolver#requirePinned()}): a super-admin in global
 * view picks a hospital first. A row of another hospital answers exactly like a row that does not
 * exist (the same 404), so ids cannot be probed across tenants.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientNotificationDeliveryAdminService {

    static final String NOT_FOUND_KEY = "patientNotification.delivery.notFound";
    static final String NOT_RETRYABLE_KEY = "patientNotification.delivery.notRetryable";
    static final String STATUS_INVALID_KEY = "patientNotification.delivery.statusInvalid";
    static final String ALL = "ALL";
    private static final int MAX_PAGE_SIZE = 100;

    private final PatientNotificationDeliveryRepository repository;
    private final PatientRepository patientRepository;
    private final com.example.hms.repository.AppointmentRepository appointmentRepository;
    private final com.example.hms.repository.BillingInvoiceRepository invoiceRepository;
    private final com.example.hms.repository.LabOrderRepository labOrderRepository;
    private final ActingScopeResolver actingScopeResolver;
    private final RoleValidator roleValidator;
    private final Clock clock;

    /** @param status one status, or ALL; FAILED when blank */
    @Transactional(readOnly = true)
    public PatientNotificationDeliveryPageDTO search(String status, int page, int size) {
        UUID hospitalId = actingScopeResolver.requirePinned();
        Collection<NotificationDeliveryStatus> statuses = parseStatuses(status);
        Page<PatientNotificationDelivery> rows = repository.searchForHospital(hospitalId, statuses,
            PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt")));

        Set<UUID> patientIds = rows.getContent().stream().map(PatientNotificationDelivery::getPatientId)
            .collect(Collectors.toSet());
        Map<UUID, String> names = patientIds.isEmpty() ? Map.of()
            : patientRepository.findAllById(patientIds).stream()
                .collect(Collectors.toMap(Patient::getId, PatientNotificationDeliveryAdminService::nameOf,
                    (a, b) -> a));

        Map<String, Long> counts = new LinkedHashMap<>();
        for (NotificationDeliveryStatus s : NotificationDeliveryStatus.values()) {
            counts.put(s.name(), 0L);
        }
        for (Object[] row : repository.countByStatusForHospital(hospitalId)) {
            counts.put(((NotificationDeliveryStatus) row[0]).name(), (Long) row[1]);
        }

        List<PatientNotificationDeliveryDTO> content = rows.getContent().stream().map(row -> toDto(row, names))
            .toList();
        describeSubjects(rows.getContent(), content, hospitalId);
        return PatientNotificationDeliveryPageDTO.builder()
            .content(content)
            .page(rows.getNumber())
            .size(rows.getSize())
            .totalElements(rows.getTotalElements())
            .countsByStatus(counts)
            .build();
    }

    /**
     * Put a FAILED send of the caller's hospital back in the queue with a fresh attempt budget.
     * Only FAILED: a SENT row is never re-sent from here (that would text the patient twice), and an
     * open or SKIPPED one is refused with a 400.
     */
    @Transactional
    public PatientNotificationDeliveryDTO retry(UUID id) {
        UUID hospitalId = actingScopeResolver.requirePinned();
        PatientNotificationDelivery row = repository.findByIdAndHospitalId(id, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY));
        if (row.getStatus() != NotificationDeliveryStatus.FAILED) {
            throw new BusinessException(NOT_RETRYABLE_KEY);
        }
        int requeued = repository.requeueFailed(id, hospitalId, NotificationDeliveryStatus.FAILED,
            NotificationDeliveryStatus.PENDING, LocalDateTime.now(clock), roleValidator.getCurrentUserId());
        if (requeued != 1) {
            // Someone else requeued it between the read and the update.
            throw new BusinessException(NOT_RETRYABLE_KEY);
        }
        log.info("Patient notification {} ({} {}) requeued by an administrator", id, row.getEventType(),
            row.getChannel());
        PatientNotificationDelivery fresh = repository.findByIdAndHospitalId(id, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY));
        Map<UUID, String> names = patientRepository.findById(fresh.getPatientId())
            .map(p -> Map.of(p.getId(), nameOf(p)))
            .orElse(Map.of());
        return toDto(fresh, names);
    }

    /**
     * QA D2402: what each row was about, read in one query per kind for the page, at this hospital only: the
     * appointment's day and time, the invoice number, the lab order number. Never a test name or a result.
     */
    private void describeSubjects(List<PatientNotificationDelivery> rows, List<PatientNotificationDeliveryDTO> dtos,
                                  UUID hospitalId) {
        Set<UUID> appointments = new java.util.HashSet<>();
        Set<UUID> invoices = new java.util.HashSet<>();
        Set<UUID> labOrders = new java.util.HashSet<>();
        for (PatientNotificationDelivery row : rows) {
            if (row.getSubjectId() == null) {
                continue;
            }
            switch (subjectKind(row.getEventType())) {
                case APPOINTMENT -> appointments.add(row.getSubjectId());
                case INVOICE -> invoices.add(row.getSubjectId());
                case LAB_ORDER -> labOrders.add(row.getSubjectId());
                default -> { /* no reference shown */ }
            }
        }
        Map<UUID, com.example.hms.model.Appointment> appointmentById = appointments.isEmpty() ? Map.of()
            : appointmentRepository.findAllById(appointments).stream()
                .filter(a -> a.getHospital() != null && hospitalId.equals(a.getHospital().getId()))
                .collect(Collectors.toMap(com.example.hms.model.Appointment::getId, Function.identity()));
        Map<UUID, String> invoiceNumbers = invoices.isEmpty() ? Map.of()
            : invoiceRepository.findAllById(invoices).stream()
                .filter(i -> i.getHospital() != null && hospitalId.equals(i.getHospital().getId())
                    && i.getInvoiceNumber() != null)
                .collect(Collectors.toMap(com.example.hms.model.BillingInvoice::getId,
                    com.example.hms.model.BillingInvoice::getInvoiceNumber));
        Map<UUID, String> orderNumbers = labOrders.isEmpty() ? Map.of()
            : labOrderRepository.findAllById(labOrders).stream()
                .filter(o -> o.getHospital() != null && hospitalId.equals(o.getHospital().getId())
                    && o.getOrderNumber() != null)
                .collect(Collectors.toMap(com.example.hms.model.LabOrder::getId,
                    com.example.hms.model.LabOrder::getOrderNumber));
        for (int i = 0; i < rows.size(); i++) {
            PatientNotificationDelivery row = rows.get(i);
            PatientNotificationDeliveryDTO dto = dtos.get(i);
            if (row.getSubjectId() == null) {
                continue;
            }
            com.example.hms.model.Appointment appointment = appointmentById.get(row.getSubjectId());
            if (appointment != null) {
                dto.setSubjectDate(appointment.getAppointmentDate());
                dto.setSubjectTime(appointment.getStartTime());
            }
            String reference = invoiceNumbers.getOrDefault(row.getSubjectId(), orderNumbers.get(row.getSubjectId()));
            dto.setSubjectReference(reference);
        }
    }

    enum SubjectKind { APPOINTMENT, INVOICE, LAB_ORDER, OTHER }

    /** The subject a notice names, by its event (PatientMessageGate.Notice, the ledger's queued events). */
    static SubjectKind subjectKind(com.example.hms.enums.PatientNotificationEvent event) {
        if (event == null) {
            return SubjectKind.OTHER;
        }
        return switch (event) {
            case APPOINTMENT_CONFIRMATION, APPOINTMENT_REMINDER, APPOINTMENT_RESCHEDULED, APPOINTMENT_CANCELLED,
                 APPOINTMENT_COMPLETED, APPOINTMENT_NO_SHOW -> SubjectKind.APPOINTMENT;
            case INVOICE -> SubjectKind.INVOICE;
            case LAB_RESULT_AVAILABLE -> SubjectKind.LAB_ORDER;
            default -> SubjectKind.OTHER;
        };
    }

    private static String nameOf(Patient patient) {
        return patient.getFullName() == null ? "" : patient.getFullName();
    }

    static Collection<NotificationDeliveryStatus> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of(NotificationDeliveryStatus.FAILED);
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (ALL.equals(normalized)) {
            return EnumSet.allOf(NotificationDeliveryStatus.class);
        }
        Map<String, NotificationDeliveryStatus> byName = Arrays.stream(NotificationDeliveryStatus.values())
            .collect(Collectors.toMap(Enum::name, Function.identity()));
        NotificationDeliveryStatus parsed = byName.get(normalized);
        if (parsed == null) {
            throw new BusinessException(STATUS_INVALID_KEY);
        }
        return List.of(parsed);
    }

    private static PatientNotificationDeliveryDTO toDto(PatientNotificationDelivery row, Map<UUID, String> names) {
        return PatientNotificationDeliveryDTO.builder()
            .id(row.getId())
            .patientId(row.getPatientId())
            .patientName(names.getOrDefault(row.getPatientId(), ""))
            .eventType(row.getEventType().name())
            .channel(row.getChannel().name())
            .status(row.getStatus().name())
            .attempts(row.getAttempts())
            .errorCode(row.getErrorCode())
            .retryable(row.getStatus() == NotificationDeliveryStatus.FAILED)
            .manualRetries(row.getManualRetries())
            .createdAt(row.getCreatedAt())
            .lastAttemptAt(row.getLastAttemptAt())
            .nextAttemptAt(row.getNextAttemptAt())
            .sentAt(row.getSentAt())
            .lastRetriedAt(row.getLastRetriedAt())
            .build();
    }
}
