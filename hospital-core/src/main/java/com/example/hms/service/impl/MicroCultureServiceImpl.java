package com.example.hms.service.impl;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.enums.MicroGrowthResult;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.model.MicroCultureResult;
import com.example.hms.model.MicroIsolate;
import com.example.hms.model.MicroSusceptibility;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.MicroCultureRequestDTO;
import com.example.hms.payload.dto.MicroCultureResponseDTO;
import com.example.hms.payload.dto.MicroCultureUpdateDTO;
import com.example.hms.payload.dto.MicroIsolateRequestDTO;
import com.example.hms.payload.dto.MicroSusceptibilityRequestDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.repository.MicroCultureResultRepository;
import com.example.hms.repository.MicroIsolateRepository;
import com.example.hms.repository.MicroSusceptibilityRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.MicroCultureService;
import com.example.hms.service.NotificationService;
import com.example.hms.service.support.PatientChartAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Culture report lifecycle (P3 #19): PRELIMINARY (freely editable by the
 * lab) -> FINAL (locked; finalize requires a growth result, and growth
 * requires at least one isolate) -> CORRECTED (any later mutation demands a
 * correction reason and stamps the report CORRECTED — it never silently
 * reverts to editable).
 *
 * <p>Tenancy is the 404-not-403 idiom on every read AND write — the
 * lab-results acknowledge/read-back endpoints skipped it and shipped a
 * cross-tenant hole; this service does not copy that shape.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MicroCultureServiceImpl implements MicroCultureService {

    private static final String MSG_CULTURE_NOT_FOUND = "Culture report not found with ID: ";
    private static final String MSG_ORDER_NOT_FOUND = "Lab order not found with ID: ";
    private static final String MSG_ISOLATE_NOT_FOUND = "Isolate not found with ID: ";
    private static final String POSITIVE_CULTURE_TYPE = "POSITIVE_CULTURE_RESULT";

    private final MicroCultureResultRepository cultureRepository;
    private final MicroIsolateRepository isolateRepository;
    private final MicroSusceptibilityRepository susceptibilityRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabSpecimenRepository specimenRepository;
    private final PatientChartAccess patientChartAccess;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Override
    public MicroCultureResponseDTO createCulture(UUID hospitalId, UUID actorUserId,
                                                 MicroCultureRequestDTO request) {
        if (hospitalId == null) {
            throw new BusinessException("An active hospital is required to record a culture.");
        }
        LabOrder order = labOrderRepository.findById(request.getLabOrderId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ORDER_NOT_FOUND + request.getLabOrderId()));
        // 404-not-403: a scoped caller resulting another hospital's order
        // learns nothing, not "exists elsewhere".
        if (order.getHospital() == null || !Objects.equals(order.getHospital().getId(), hospitalId)) {
            throw new ResourceNotFoundException(MSG_ORDER_NOT_FOUND + request.getLabOrderId());
        }
        if (order.getStatus() == LabOrderStatus.CANCELLED) {
            throw new BusinessException("A cancelled lab order cannot receive a culture report.");
        }
        Patient patient = order.getPatient();
        if (patient == null) {
            throw new BusinessException("The lab order has no patient attached.");
        }

        LabSpecimen specimen = null;
        if (request.getSpecimenId() != null) {
            specimen = specimenRepository.findById(request.getSpecimenId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Specimen not found with ID: " + request.getSpecimenId()));
            if (specimen.getLabOrder() == null
                || !Objects.equals(specimen.getLabOrder().getId(), order.getId())) {
                throw new BusinessException("The specimen belongs to a different lab order.");
            }
        }

        MicroCultureResult culture = MicroCultureResult.builder()
            .labOrder(order)
            .specimen(specimen)
            .patient(patient)
            .hospital(order.getHospital())
            .specimenSource(request.getSpecimenSource())
            .collectedAt(request.getCollectedAt())
            .gramStain(request.getGramStain())
            .growthResult(request.getGrowthResult())
            .notes(request.getNotes())
            .build();

        if (actorUserId != null) {
            userRepository.findById(actorUserId).ifPresent(culture::setDocumentedBy);
            staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)
                .ifPresent(culture::setReportedByStaff);
        }
        return toDto(cultureRepository.save(culture));
    }

    @Override
    @Transactional(readOnly = true)
    public MicroCultureResponseDTO getCulture(UUID cultureId, UUID hospitalId) {
        return toDto(loadScoped(cultureId, hospitalId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MicroCultureResponseDTO> getForPatient(UUID patientId, UUID hospitalId) {
        // 404-not-403, and resolves cross-hospital patients the tenant-scoped
        // finder used to miss entirely — see PatientChartAccess.
        patientChartAccess.require(patientId, hospitalId);
        return cultureRepository.findForPatient(patientId, hospitalId).stream()
            .map(this::toDto)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MicroCultureResponseDTO> getForHospital(UUID hospitalId, MicroCultureStatus status,
                                                        Pageable pageable) {
        if (hospitalId == null) {
            throw new BusinessException("An active hospital is required to list culture reports.");
        }
        return cultureRepository.findForHospital(hospitalId, status, pageable).map(this::toDto);
    }

    @Override
    public MicroCultureResponseDTO updateCulture(UUID cultureId, UUID hospitalId,
                                                 MicroCultureUpdateDTO request) {
        MicroCultureResult culture = loadScoped(cultureId, hospitalId);
        applyCorrectionGuard(culture, request.getCorrectionReason());

        if (request.getGrowthResult() != null
            && request.getGrowthResult() == MicroGrowthResult.NO_GROWTH
            && isolateRepository.countByCultureResult_Id(culture.getId()) > 0) {
            throw new BusinessException(
                "A report carrying isolates cannot be marked no-growth. Remove the isolates first.");
        }

        // Null request fields are left unchanged — corrections rewrite values,
        // they don't blank them.
        if (request.getSpecimenSource() != null) {
            culture.setSpecimenSource(request.getSpecimenSource());
        }
        if (request.getCollectedAt() != null) {
            culture.setCollectedAt(request.getCollectedAt());
        }
        if (request.getGramStain() != null) {
            culture.setGramStain(request.getGramStain());
        }
        if (request.getGrowthResult() != null) {
            culture.setGrowthResult(request.getGrowthResult());
        }
        if (request.getNotes() != null) {
            culture.setNotes(request.getNotes());
        }
        return toDto(cultureRepository.save(culture));
    }

    @Override
    public MicroCultureResponseDTO finalizeCulture(UUID cultureId, UUID hospitalId, UUID actorUserId) {
        MicroCultureResult culture = loadScoped(cultureId, hospitalId);
        if (culture.getStatus() != MicroCultureStatus.PRELIMINARY) {
            throw new BusinessException(
                "This report is already final. Corrections go through the edit endpoints with a reason.");
        }
        if (culture.getGrowthResult() == null) {
            throw new BusinessException("A growth result is required before the report can be finalized.");
        }
        long isolates = isolateRepository.countByCultureResult_Id(culture.getId());
        if (culture.getGrowthResult() == MicroGrowthResult.GROWTH && isolates == 0) {
            throw new BusinessException(
                "A growth report needs at least one isolate before it can be finalized.");
        }
        culture.setStatus(MicroCultureStatus.FINAL);
        culture.setFinalizedAt(LocalDateTime.now());
        culture.setFinalizedByUserId(actorUserId);
        MicroCultureResult saved = cultureRepository.save(culture);

        if (saved.getGrowthResult() == MicroGrowthResult.GROWTH) {
            notifyOrderingProviderBestEffort(saved);
        }
        return toDto(saved);
    }

    @Override
    public MicroCultureResponseDTO addIsolate(UUID cultureId, UUID hospitalId,
                                              MicroIsolateRequestDTO request) {
        MicroCultureResult culture = loadScoped(cultureId, hospitalId);
        applyCorrectionGuard(culture, request.getCorrectionReason());
        if (culture.getGrowthResult() == MicroGrowthResult.NO_GROWTH) {
            throw new BusinessException(
                "A no-growth report cannot carry isolates. Correct the growth result first.");
        }
        int number = request.getIsolateNumber() != null
            ? request.getIsolateNumber()
            : (int) isolateRepository.countByCultureResult_Id(culture.getId()) + 1;
        MicroIsolate isolate = MicroIsolate.builder()
            .cultureResult(culture)
            .isolateNumber(number)
            .organismName(request.getOrganismName().trim())
            .organismCode(request.getOrganismCode())
            .growthQuantity(request.getGrowthQuantity())
            .notes(request.getNotes())
            .build();
        isolateRepository.save(isolate);
        return toDto(cultureRepository.save(culture));
    }

    @Override
    public MicroCultureResponseDTO updateIsolate(UUID cultureId, UUID isolateId, UUID hospitalId,
                                                 MicroIsolateRequestDTO request) {
        MicroCultureResult culture = loadScoped(cultureId, hospitalId);
        MicroIsolate isolate = loadIsolateOf(culture, isolateId);
        applyCorrectionGuard(culture, request.getCorrectionReason());
        isolate.setOrganismName(request.getOrganismName().trim());
        isolate.setOrganismCode(request.getOrganismCode());
        if (request.getIsolateNumber() != null) {
            isolate.setIsolateNumber(request.getIsolateNumber());
        }
        isolate.setGrowthQuantity(request.getGrowthQuantity());
        isolate.setNotes(request.getNotes());
        isolateRepository.save(isolate);
        return toDto(cultureRepository.save(culture));
    }

    @Override
    public MicroCultureResponseDTO deleteIsolate(UUID cultureId, UUID isolateId, UUID hospitalId,
                                                 String correctionReason) {
        MicroCultureResult culture = loadScoped(cultureId, hospitalId);
        MicroIsolate isolate = loadIsolateOf(culture, isolateId);
        applyCorrectionGuard(culture, correctionReason);
        // Explicit child delete rather than relying on the DB cascade: the H2
        // test schema is built from the entities and carries no ON DELETE rule.
        susceptibilityRepository.deleteAll(
            susceptibilityRepository.findByIsolate_IdInOrderByAntibioticNameAsc(List.of(isolate.getId())));
        isolateRepository.delete(isolate);
        return toDto(cultureRepository.save(culture));
    }

    @Override
    public MicroCultureResponseDTO addSusceptibility(UUID cultureId, UUID isolateId, UUID hospitalId,
                                                     MicroSusceptibilityRequestDTO request) {
        MicroCultureResult culture = loadScoped(cultureId, hospitalId);
        MicroIsolate isolate = loadIsolateOf(culture, isolateId);
        applyCorrectionGuard(culture, request.getCorrectionReason());
        String antibiotic = request.getAntibioticName().trim();
        if (susceptibilityRepository.existsByIsolate_IdAndAntibioticNameIgnoreCase(isolate.getId(), antibiotic)) {
            throw new BusinessException(
                "This antibiotic is already recorded for the isolate. Remove the existing row to re-enter it.");
        }
        MicroSusceptibility row = MicroSusceptibility.builder()
            .isolate(isolate)
            .antibioticName(antibiotic)
            .antibioticCode(request.getAntibioticCode())
            .method(request.getMethod())
            .micValue(request.getMicValue())
            .interpretation(request.getInterpretation())
            .notes(request.getNotes())
            .build();
        susceptibilityRepository.save(row);
        return toDto(cultureRepository.save(culture));
    }

    @Override
    public MicroCultureResponseDTO deleteSusceptibility(UUID cultureId, UUID isolateId, UUID susceptibilityId,
                                                        UUID hospitalId, String correctionReason) {
        MicroCultureResult culture = loadScoped(cultureId, hospitalId);
        MicroIsolate isolate = loadIsolateOf(culture, isolateId);
        MicroSusceptibility row = susceptibilityRepository.findById(susceptibilityId)
            .filter(s -> s.getIsolate() != null && Objects.equals(s.getIsolate().getId(), isolate.getId()))
            .orElseThrow(() -> new ResourceNotFoundException(
                "Susceptibility not found with ID: " + susceptibilityId));
        applyCorrectionGuard(culture, correctionReason);
        susceptibilityRepository.delete(row);
        return toDto(cultureRepository.save(culture));
    }

    /* ── Guards ────────────────────────────────────────────────────────── */

    private MicroCultureResult loadScoped(UUID cultureId, UUID hospitalId) {
        MicroCultureResult culture = cultureRepository.findById(cultureId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_CULTURE_NOT_FOUND + cultureId));
        if (hospitalId != null
            && (culture.getHospital() == null
                || !Objects.equals(culture.getHospital().getId(), hospitalId))) {
            throw new ResourceNotFoundException(MSG_CULTURE_NOT_FOUND + cultureId);
        }
        return culture;
    }

    private MicroIsolate loadIsolateOf(MicroCultureResult culture, UUID isolateId) {
        return isolateRepository.findById(isolateId)
            .filter(i -> i.getCultureResult() != null
                && Objects.equals(i.getCultureResult().getId(), culture.getId()))
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ISOLATE_NOT_FOUND + isolateId));
    }

    /**
     * A PRELIMINARY report edits freely. A FINAL/CORRECTED one demands a
     * reason and stays in CORRECTED — the lock never silently lifts.
     */
    private void applyCorrectionGuard(MicroCultureResult culture, String correctionReason) {
        if (!culture.isLocked()) {
            return;
        }
        if (correctionReason == null || correctionReason.isBlank()) {
            throw new BusinessException(
                "This report is final. Changing it requires a correction reason.");
        }
        culture.setStatus(MicroCultureStatus.CORRECTED);
        culture.setCorrectedAt(LocalDateTime.now());
        culture.setCorrectionReason(correctionReason.trim());
    }

    /* ── Positive-culture notification ─────────────────────────────────── */

    /**
     * A finalized growth report is an actionable event for the ordering
     * provider, but a "Positive" that is not a number is invisible to the
     * numeric critical-value chain — so the report notifies directly. Best
     * effort by policy: a notification failure must never roll back the
     * clinical write (same stance as CriticalValueNotificationService).
     */
    private void notifyOrderingProviderBestEffort(MicroCultureResult culture) {
        try {
            LabOrder order = culture.getLabOrder();
            Staff orderingStaff = order != null ? order.getOrderingStaff() : null;
            User user = orderingStaff != null ? orderingStaff.getUser() : null;
            if (user == null || user.getUsername() == null) {
                log.warn("Positive culture {} has no resolvable ordering user; skipping notification",
                    culture.getId());
                return;
            }
            String organisms = isolateRepository
                .findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc(culture.getId()).stream()
                .map(MicroIsolate::getOrganismName)
                .collect(Collectors.joining(", "));
            String patientName = culture.getPatient() != null ? culture.getPatient().getFullName() : null;
            String message = "Positive culture finalized for "
                + (patientName != null && !patientName.isBlank() ? patientName : "your patient")
                + ": " + (organisms.isBlank() ? "growth reported" : organisms)
                + ". Susceptibilities are available in the chart.";
            notificationService.createNotification(message, user.getUsername(), POSITIVE_CULTURE_TYPE);
        } catch (RuntimeException ex) {
            log.warn("Positive-culture notification failed for culture {}: {}",
                culture.getId(), ex.getMessage(), ex);
        }
    }

    /* ── Mapping ───────────────────────────────────────────────────────── */

    private MicroCultureResponseDTO toDto(MicroCultureResult culture) {
        List<MicroIsolate> isolates =
            isolateRepository.findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc(culture.getId());
        Map<UUID, List<MicroSusceptibility>> byIsolate = isolates.isEmpty()
            ? Map.of()
            : susceptibilityRepository
                .findByIsolate_IdInOrderByAntibioticNameAsc(isolates.stream().map(MicroIsolate::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(s -> s.getIsolate().getId()));

        LabOrder order = culture.getLabOrder();
        return MicroCultureResponseDTO.builder()
            .id(culture.getId())
            .labOrderId(order != null ? order.getId() : null)
            .labOrderCode(order != null && order.getId() != null ? order.getId().toString() : null)
            .labTestName(order != null && order.getLabTestDefinition() != null
                ? order.getLabTestDefinition().getName() : null)
            .patientId(culture.getPatient() != null ? culture.getPatient().getId() : null)
            .patientName(culture.getPatient() != null ? culture.getPatient().getFullName() : null)
            .hospitalId(culture.getHospital() != null ? culture.getHospital().getId() : null)
            .hospitalName(culture.getHospital() != null ? culture.getHospital().getName() : null)
            .specimenId(culture.getSpecimen() != null ? culture.getSpecimen().getId() : null)
            .specimenAccessionNumber(culture.getSpecimen() != null
                ? culture.getSpecimen().getAccessionNumber() : null)
            .specimenSource(culture.getSpecimenSource())
            .collectedAt(culture.getCollectedAt())
            .status(culture.getStatus())
            .growthResult(culture.getGrowthResult())
            .gramStain(culture.getGramStain())
            .finalizedAt(culture.getFinalizedAt())
            .finalizedByName(resolveUserName(culture.getFinalizedByUserId()))
            .correctedAt(culture.getCorrectedAt())
            .correctionReason(culture.getCorrectionReason())
            .reportedByName(resolveStaffName(culture.getReportedByStaff()))
            .notes(culture.getNotes())
            .createdAt(culture.getCreatedAt())
            .updatedAt(culture.getUpdatedAt())
            .isolates(isolates.stream()
                .map(isolate -> toIsolateDto(isolate, byIsolate.getOrDefault(isolate.getId(), List.of())))
                .toList())
            .build();
    }

    private MicroCultureResponseDTO.Isolate toIsolateDto(MicroIsolate isolate,
                                                         List<MicroSusceptibility> susceptibilities) {
        return MicroCultureResponseDTO.Isolate.builder()
            .id(isolate.getId())
            .isolateNumber(isolate.getIsolateNumber())
            .organismName(isolate.getOrganismName())
            .organismCode(isolate.getOrganismCode())
            .growthQuantity(isolate.getGrowthQuantity())
            .notes(isolate.getNotes())
            .susceptibilities(susceptibilities.stream()
                .map(s -> MicroCultureResponseDTO.Susceptibility.builder()
                    .id(s.getId())
                    .antibioticName(s.getAntibioticName())
                    .antibioticCode(s.getAntibioticCode())
                    .method(s.getMethod())
                    .micValue(s.getMicValue())
                    .interpretation(s.getInterpretation())
                    .notes(s.getNotes())
                    .build())
                .toList())
            .build();
    }

    private String resolveUserName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
            .map(user -> {
                String combined = ((user.getFirstName() != null ? user.getFirstName().trim() : "")
                    + " " + (user.getLastName() != null ? user.getLastName().trim() : "")).trim();
                return combined.isEmpty() ? user.getUsername() : combined;
            })
            .orElse(null);
    }

    private String resolveStaffName(Staff staff) {
        return Optional.ofNullable(staff)
            .map(s -> {
                if (s.getUser() == null) {
                    return s.getName();
                }
                String first = s.getUser().getFirstName();
                String last = s.getUser().getLastName();
                String combined =
                    ((first != null ? first.trim() : "") + " " + (last != null ? last.trim() : "")).trim();
                return combined.isEmpty() ? s.getName() : combined;
            })
            .orElse(null);
    }
}
