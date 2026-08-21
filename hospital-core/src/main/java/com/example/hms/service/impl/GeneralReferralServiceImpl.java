package com.example.hms.service.impl;

import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.GeneralReferral;
import com.example.hms.model.GeneralReferralAttachment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.ReferralAttachmentResponseDTO;
import com.example.hms.payload.dto.referral.ReferralEventResponseDTO;
import com.example.hms.payload.dto.referral.RejectReferralRequestDTO;
import com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO;
import com.example.hms.persistence.JpaProxyUtils;
import com.example.hms.enums.ReferralEventType;
import com.example.hms.model.ReferralEvent;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ReferralEventRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.service.ReferralEventRecorder;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service implementation for multi-specialty general referrals
 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
@Transactional(readOnly = true)
public class GeneralReferralServiceImpl implements GeneralReferralService {

    private static final int REFERRAL_APPOINTMENT_MINUTES = 30;

    private final GeneralReferralRepository referralRepository;
    private final com.example.hms.repository.AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleValidator roleValidator;
    private final ReferralEventRecorder eventRecorder;
    private final ReferralEventRepository eventRepository;

    @Override
    @Transactional
    public GeneralReferralResponseDTO createReferral(GeneralReferralRequestDTO request) {
        Patient patient = patientRepository.findByIdUnscoped(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        Staff referringProvider = staffRepository.findById(request.getReferringProviderId())
            .orElseThrow(() -> new ResourceNotFoundException("Referring provider not found"));

        Staff receivingProvider = null;
        if (request.getReceivingProviderId() != null) {
            receivingProvider = staffRepository.findById(request.getReceivingProviderId())
                .orElseThrow(() -> new ResourceNotFoundException("Receiving provider not found"));
        }

        Department targetDepartment = null;
        if (request.getTargetDepartmentId() != null) {
            targetDepartment = departmentRepository.findById(request.getTargetDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Target department not found"));
        }

        Hospital receivingHospital = null;
        if (request.getReceivingHospitalId() != null) {
            receivingHospital = hospitalRepository.findById(request.getReceivingHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Receiving hospital not found"));
        }

        Department sourceDepartment = null;
        if (request.getSourceDepartmentId() != null) {
            sourceDepartment = departmentRepository.findById(request.getSourceDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Source department not found"));
        }

        GeneralReferral referral = new GeneralReferral();
        referral.setPatient(patient);
        referral.setHospital(hospital);
        referral.setReceivingHospital(receivingHospital);
        referral.setSourceDepartment(sourceDepartment);
        referral.setReferringProvider(referringProvider);
        referral.setReceivingProvider(receivingProvider);
        referral.setTargetSpecialty(request.getTargetSpecialty());
        referral.setTargetDepartment(targetDepartment);
        referral.setTargetFacilityName(request.getTargetFacilityName());
        referral.setReferralType(request.getReferralType());
        referral.setStatus(ReferralStatus.DRAFT);
        referral.setUrgency(request.getUrgency());
        referral.setReferralReason(request.getReferralReason());
        referral.setClinicalIndication(request.getClinicalIndication());
        referral.setClinicalSummary(request.getClinicalSummary());
        referral.setCurrentMedications(copyList(request.getCurrentMedications()));
        referral.setDiagnoses(copyList(request.getDiagnoses()));
        referral.setClinicalQuestion(request.getClinicalQuestion());
        referral.setAnticipatedTreatment(request.getAnticipatedTreatment());
        referral.setInsuranceAuthNumber(request.getInsuranceAuthNumber());
        referral.setMetadata(request.getMetadata());
        referral.setPriorityScore(calculatePriorityScore(request.getUrgency()));

        referral = referralRepository.save(referral);
        return toResponse(referral);
    }

    @Override
    public GeneralReferralResponseDTO getReferral(UUID referralId) {
        return toResponse(findReferral(referralId));
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO submitReferral(UUID referralId) {
        GeneralReferral referral = findReferral(referralId);
        ReferralStatus before = referral.getStatus();
        referral.submit();
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.SUBMIT, before, null);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO acknowledgeReferral(UUID referralId, String notes, UUID receivingProviderId) {
        GeneralReferral referral = findReferral(referralId);
        Staff receivingProvider = staffRepository.findById(receivingProviderId)
            .orElseThrow(() -> new ResourceNotFoundException("Receiving provider not found"));
        ReferralStatus before = referral.getStatus();
        referral.acknowledge(notes, receivingProvider);
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.ACKNOWLEDGE, before, notes);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO scheduleReferral(UUID referralId, ScheduleReferralRequestDTO request) {
        GeneralReferral referral = findReferral(referralId);
        ReferralStatus before = referral.getStatus();
        referral.schedule(request.getAppointmentTime(), request.getLocation());

        com.example.hms.model.Appointment appointment =
            createAppointmentForReferral(referral, request.getAppointmentTime());
        if (appointment != null) {
            referral.setAppointment(appointment);
        }

        referral = referralRepository.save(referral);
        String note = "appointmentTime=" + request.getAppointmentTime()
            + (request.getLocation() == null ? "" : ", location=" + request.getLocation())
            + (appointment == null ? ", appointment=none" : ", appointmentId=" + appointment.getId());
        eventRecorder.recordUserEvent(referral, ReferralEventType.SCHEDULE, before, note);
        return toResponse(referral);
    }

    /**
     * Create the appointment a scheduled referral implies.
     *
     * <p>Scheduling used to store {@code scheduledAppointmentAt} plus a free-text
     * location and stop. No Appointment row existed, so a "booked" referral was
     * invisible to everything that works off appointments — the receiving
     * provider's calendar, reception check-in, the V112 reminder sweep,
     * utilisation reporting. The referral said booked and the schedule
     * disagreed.
     *
     * <p>Returns null when the referral cannot produce one, which is a NORMAL
     * outcome rather than an error: Appointment requires staff, department and
     * assignment (all NOT NULL), and a referral to an external facility has no
     * receiving provider. Those referrals keep the old behaviour. Failing the
     * schedule instead would break the commonest referral there is.
     *
     * <p>Never propagates. A referral must still schedule if appointment
     * creation fails — the referral is the clinical record, the appointment is
     * the convenience built on top of it.
     */
    private com.example.hms.model.Appointment createAppointmentForReferral(
            GeneralReferral referral, java.time.LocalDateTime appointmentTime) {
        if (appointmentTime == null) {
            return null;
        }
        com.example.hms.model.Staff provider = referral.getReceivingProvider();
        com.example.hms.model.Department department = referral.getTargetDepartment();
        com.example.hms.model.Hospital hospital = referral.getReceivingHospital() != null
            ? referral.getReceivingHospital()
            : referral.getHospital();

        if (provider == null || department == null || hospital == null
            || provider.getAssignment() == null) {
            log.debug("Referral {} scheduled without an appointment: "
                    + "provider={}, department={}, hospital={}, assignment={}",
                referral.getId(), provider != null, department != null, hospital != null,
                provider != null && provider.getAssignment() != null);
            return null;
        }

        try {
            com.example.hms.model.Appointment appointment = com.example.hms.model.Appointment.builder()
                .patient(referral.getPatient())
                .staff(provider)
                .assignment(provider.getAssignment())
                .hospital(hospital)
                .department(department)
                .appointmentDate(appointmentTime.toLocalDate())
                .startTime(appointmentTime.toLocalTime())
                .endTime(appointmentTime.toLocalTime().plusMinutes(REFERRAL_APPOINTMENT_MINUTES))
                .status(com.example.hms.enums.AppointmentStatus.SCHEDULED)
                .reason(buildAppointmentReason(referral))
                .build();
            return appointmentRepository.save(appointment);
        } catch (RuntimeException ex) {
            log.warn("Could not create an appointment for referral {}: {}",
                referral.getId(), ex.getMessage());
            return null;
        }
    }

    private String buildAppointmentReason(GeneralReferral referral) {
        String reason = referral.getReferralReason();
        String prefix = "Referral";
        return (reason == null || reason.isBlank()) ? prefix : prefix + ": " + reason;
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO startReferral(UUID referralId) {
        GeneralReferral referral = findReferral(referralId);
        ReferralStatus before = referral.getStatus();
        referral.start();
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.START, before, null);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO completeReferral(UUID referralId, String summary, String followUp) {
        GeneralReferral referral = findReferral(referralId);
        ReferralStatus before = referral.getStatus();
        referral.complete(summary, followUp);
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.COMPLETE, before, summary);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO rejectReferral(UUID referralId, RejectReferralRequestDTO request) {
        GeneralReferral referral = findReferral(referralId);
        ReferralStatus before = referral.getStatus();
        referral.reject(request.getReason());
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.REJECT, before, request.getReason());
        return toResponse(referral);
    }

    @Override
    @Transactional
    public void cancelReferral(UUID referralId, String reason) {
        GeneralReferral referral = findReferral(referralId);
        ReferralStatus before = referral.getStatus();
        referral.cancel(reason);
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.CANCEL, before, reason);
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByPatient(UUID patientId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patientId, activeHospitalId);
        } else {
            referrals = referralRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByReferringProvider(UUID providerId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findByReferringProviderIdAndHospitalIdOrderByCreatedAtDesc(providerId, activeHospitalId);
        } else {
            referrals = referralRepository.findByReferringProviderIdOrderByCreatedAtDesc(providerId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByReceivingProvider(UUID providerId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findByReceivingProviderIdAndHospitalIdOrderByCreatedAtDesc(providerId, activeHospitalId);
        } else {
            referrals = referralRepository.findByReceivingProviderIdOrderByCreatedAtDesc(providerId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByHospital(UUID hospitalId, String status) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<GeneralReferral> outgoing;
        List<GeneralReferral> incoming;
        if (status != null && !status.isBlank()) {
            ReferralStatus referralStatus = ReferralStatus.valueOf(status.toUpperCase());
            outgoing = referralRepository.findByHospitalIdAndStatusOrderByCreatedAtDesc(effectiveHospitalId, referralStatus);
            incoming = referralRepository.findByReceivingHospitalIdAndStatusOrderByCreatedAtDesc(effectiveHospitalId, referralStatus);
        } else {
            outgoing = referralRepository.findByHospitalIdOrderByCreatedAtDesc(effectiveHospitalId);
            incoming = referralRepository.findByReceivingHospitalIdOrderByCreatedAtDesc(effectiveHospitalId);
        }
        // Exclude DRAFT incoming referrals — drafts are unsent and only visible to the sender
        incoming = incoming.stream()
            .filter(r -> r.getStatus() != ReferralStatus.DRAFT)
            .toList();
        // Merge outgoing and incoming, dedup by ID, sort newest-first
        Map<UUID, GeneralReferral> merged = new LinkedHashMap<>();
        outgoing.forEach(r -> merged.put(r.getId(), r));
        incoming.forEach(r -> merged.putIfAbsent(r.getId(), r));
        return merged.values().stream()
            .sorted(Comparator.comparing(GeneralReferral::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getAllReferrals(String status) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            return getReferralsByHospital(activeHospitalId, status);
        }
        List<GeneralReferral> referrals;
        if (status != null && !status.isBlank()) {
            ReferralStatus referralStatus = ReferralStatus.valueOf(status.toUpperCase());
            referrals = referralRepository.findByStatusOrderByCreatedAtDesc(referralStatus);
        } else {
            referrals = referralRepository.findAllByOrderByCreatedAtDesc();
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getRecentForSuperAdmin(Pageable pageable) {
        return referralRepository.findAll(pageable)
            .map(this::toResponse)
            .getContent();
    }

    @Override
    public List<GeneralReferralResponseDTO> getOverdueReferrals() {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findOverdueReferralsByHospital(activeHospitalId, LocalDateTime.now());
        } else {
            referrals = referralRepository.findOverdueReferrals(LocalDateTime.now());
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<ReferralEventResponseDTO> getReferralEvents(UUID referralId) {
        // Validate the referral exists in the caller's scope before exposing its history.
        findReferral(referralId);
        return eventRepository.findByReferralIdOrderByRecordedAtAsc(referralId).stream()
            .map(GeneralReferralServiceImpl::toEventResponse)
            .toList();
    }

    private static ReferralEventResponseDTO toEventResponse(ReferralEvent event) {
        return ReferralEventResponseDTO.builder()
            .id(event.getId())
            .referralId(event.getReferralId())
            .eventType(event.getEventType())
            .fromStatus(event.getFromStatus())
            .toStatus(event.getToStatus())
            .actorUsername(event.getActorUsername())
            .actorLabel(event.getActorLabel())
            .note(event.getNote())
            .recordedAt(event.getRecordedAt())
            .build();
    }

    private static final String REFERRAL_NOT_FOUND = "generalReferral.notFound";

    private GeneralReferral findReferral(UUID referralId) {
        GeneralReferral referral = referralRepository.findById(referralId)
            .orElseThrow(() -> new ResourceNotFoundException(REFERRAL_NOT_FOUND));
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            boolean isSendingHospital = referral.getHospital() != null
                && activeHospitalId.equals(referral.getHospital().getId());
            boolean isReceivingHospital = referral.getReceivingHospital() != null
                && activeHospitalId.equals(referral.getReceivingHospital().getId());
            if (!isSendingHospital && !isReceivingHospital) {
                throw new ResourceNotFoundException(REFERRAL_NOT_FOUND);
            }
        }
        return referral;
    }

    private static final String OWNER = "GeneralReferral";

    private GeneralReferralResponseDTO toResponse(GeneralReferral referral) {
        GeneralReferralResponseDTO dto = new GeneralReferralResponseDTO();
        java.util.UUID referralId = referral.getId();
        dto.setId(referralId);

        // Force-initialise each lazy association up front and substitute null
        // when the referenced row was hard-deleted (dangling FK). Without this
        // defence a bare `referral.getPatient().getFirstName()` blows up the
        // whole list response with a 500 the moment a single referenced row
        // is missing — visible on dev's /super-admin/recent-activity endpoint.
        Patient patient = JpaProxyUtils.safeInit(referral.getPatient(), OWNER, referralId, "patient");
        Hospital hospital = JpaProxyUtils.safeInit(referral.getHospital(), OWNER, referralId, "hospital");
        Hospital receivingHospital = JpaProxyUtils.safeInit(
            referral.getReceivingHospital(), OWNER, referralId, "receivingHospital");
        Department sourceDepartment = JpaProxyUtils.safeInit(
            referral.getSourceDepartment(), OWNER, referralId, "sourceDepartment");
        Staff referringProvider = JpaProxyUtils.safeInit(
            referral.getReferringProvider(), OWNER, referralId, "referringProvider");
        Staff receivingProvider = JpaProxyUtils.safeInit(
            referral.getReceivingProvider(), OWNER, referralId, "receivingProvider");
        Department targetDepartment = JpaProxyUtils.safeInit(
            referral.getTargetDepartment(), OWNER, referralId, "targetDepartment");

        dto.setPatientId(patient != null ? patient.getId() : null);
        dto.setPatientName(extractPatientName(patient));
        dto.setHospitalId(hospital != null ? hospital.getId() : null);
        dto.setHospitalName(hospital != null ? hospital.getName() : null);
        dto.setReceivingHospitalId(receivingHospital != null ? receivingHospital.getId() : null);
        dto.setReceivingHospitalName(receivingHospital != null ? receivingHospital.getName() : null);
        dto.setSourceDepartmentId(sourceDepartment != null ? sourceDepartment.getId() : null);
        dto.setSourceDepartmentName(sourceDepartment != null ? sourceDepartment.getName() : null);
        dto.setReferringProviderId(referringProvider != null ? referringProvider.getId() : null);
        dto.setReferringProviderName(extractStaffName(referringProvider));
        dto.setReceivingProviderId(receivingProvider != null ? receivingProvider.getId() : null);
        dto.setReceivingProviderName(extractStaffName(receivingProvider));
        dto.setTargetSpecialty(referral.getTargetSpecialty());
        dto.setTargetDepartmentId(targetDepartment != null ? targetDepartment.getId() : null);
        dto.setTargetDepartmentName(targetDepartment != null ? targetDepartment.getName() : null);
        dto.setTargetFacilityName(referral.getTargetFacilityName());
        dto.setReferralType(referral.getReferralType());
        dto.setStatus(referral.getStatus());
        dto.setUrgency(referral.getUrgency());
        dto.setReferralReason(referral.getReferralReason());
        dto.setClinicalIndication(referral.getClinicalIndication());
        dto.setClinicalSummary(referral.getClinicalSummary());
        dto.setCurrentMedications(referral.getCurrentMedications());
        dto.setDiagnoses(referral.getDiagnoses());
        dto.setClinicalQuestion(referral.getClinicalQuestion());
        dto.setAnticipatedTreatment(referral.getAnticipatedTreatment());
        dto.setSubmittedAt(referral.getSubmittedAt());
        dto.setSlaDueAt(referral.getSlaDueAt());
        dto.setAcknowledgedAt(referral.getAcknowledgedAt());
        dto.setAcknowledgementNotes(referral.getAcknowledgementNotes());
        dto.setScheduledAppointmentAt(referral.getScheduledAppointmentAt());
        dto.setAppointmentLocation(referral.getAppointmentLocation());
        dto.setAppointmentId(referral.getAppointment() != null ? referral.getAppointment().getId() : null);
        dto.setStartedAt(referral.getStartedAt());
        dto.setCompletedAt(referral.getCompletedAt());
        dto.setCompletionSummary(referral.getCompletionSummary());
        dto.setFollowUpRecommendations(referral.getFollowUpRecommendations());
        dto.setCancellationReason(referral.getCancellationReason());
        dto.setInsuranceAuthNumber(referral.getInsuranceAuthNumber());
        dto.setPriorityScore(referral.getPriorityScore());
        dto.setMetadata(referral.getMetadata());
        dto.setAttachments(referral.getAttachments() == null ? List.of() :
            referral.getAttachments().stream()
                .map(this::toAttachmentResponse)
                .toList());
        dto.setIsOverdue(referral.isOverdue());
        dto.setCreatedAt(referral.getCreatedAt());
        dto.setUpdatedAt(referral.getUpdatedAt());
        return dto;
    }

    private ReferralAttachmentResponseDTO toAttachmentResponse(GeneralReferralAttachment attachment) {
        ReferralAttachmentResponseDTO dto = new ReferralAttachmentResponseDTO();
        dto.setId(attachment.getId());
        dto.setReferralId(attachment.getReferral() != null ? attachment.getReferral().getId() : null);
        dto.setStorageKey(attachment.getStorageKey());
        dto.setDisplayName(attachment.getDisplayName());
        dto.setCategory(attachment.getCategory());
        dto.setContentType(attachment.getContentType());
        dto.setSizeBytes(attachment.getSizeBytes());
        dto.setUploadedById(attachment.getUploadedBy() != null ? attachment.getUploadedBy().getId() : null);
        dto.setUploadedByName(extractStaffName(attachment.getUploadedBy()));
        dto.setUploadedAt(attachment.getUploadedAt());
        dto.setDescription(attachment.getDescription());
        return dto;
    }

    private List<Map<String, String>> copyList(List<Map<String, String>> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(source);
    }

    private int calculatePriorityScore(ReferralUrgency urgency) {
        if (urgency == null) {
            return 0;
        }
        return switch (urgency) {
            case EMERGENCY -> 100;
            case URGENT -> 75;
            case PRIORITY -> 50;
            case ROUTINE -> 25;
        };
    }

    private String extractStaffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        if (staff.getName() != null && !staff.getName().isBlank()) {
            return staff.getName();
        }
        return staff.getFullName();
    }

    private String extractPatientName(Patient patient) {
        if (patient == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        if (patient.getFirstName() != null) {
            builder.append(patient.getFirstName().trim());
        }
        if (patient.getLastName() != null) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(patient.getLastName().trim());
        }
        return builder.isEmpty() ? null : builder.toString();
    }
}
