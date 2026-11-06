package com.example.hms.service.impl;

import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.enums.MicroGrowthResult;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.model.MicroCultureCorrection;
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
import com.example.hms.repository.MicroCultureCorrectionRepository;
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

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;

/**
 * Culture report lifecycle (P3 #19): PRELIMINARY (freely editable by the
 * lab) -> FINAL (locked; finalize requires a growth result, and growth
 * requires at least one isolate) -> CORRECTED (any later mutation demands a
 * correction reason and stamps the report CORRECTED — it never silently
 * reverts to editable).
 *
 * <p>QA CX-009: a validated report says what its organisms say. Growth needs at
 * least one isolate and no-growth allows none, at finalize and through every
 * correction after it ({@link #requireConsistentOutcome}); every change takes the
 * report's row first ({@link #loadForChange}), so the count it decides on holds.
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
    /** Request properties a refusal names (QA D1352 class, round 30). */
    private static final String FIELD_LAB_ORDER_ID = "labOrderId";
    private static final String FIELD_GROWTH_RESULT = "growthResult";
    private static final String MSG_CORRECTED_NOTIFICATION = "microculture.corrected.notification";
    private static final String CORRECTED_CULTURE_TYPE = "CORRECTED_CULTURE_RESULT";

    private static final String POSITIVE_CULTURE_TYPE = "POSITIVE_CULTURE_RESULT";

    private final MicroCultureResultRepository cultureRepository;
    private final MicroIsolateRepository isolateRepository;
    private final MicroSusceptibilityRepository susceptibilityRepository;
    private final MicroCultureCorrectionRepository correctionRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabSpecimenRepository specimenRepository;
    private final PatientChartAccess patientChartAccess;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final Clock clock;

    @Override
    public MicroCultureResponseDTO createCulture(UUID hospitalId, UUID actorUserId,
                                                 MicroCultureRequestDTO request) {
        if (hospitalId == null) {
            throw new BusinessException("micro.culture.hospitalRequired");
        }
        LabOrder order = labOrderRepository.findById(request.getLabOrderId())
            .orElseThrow(() -> new ResourceNotFoundException("labOrder.notFound", request.getLabOrderId()));
        // 404-not-403: a scoped caller resulting another hospital's order
        // learns nothing, not "exists elsewhere".
        if (order.getHospital() == null || !Objects.equals(order.getHospital().getId(), hospitalId)) {
            throw new ResourceNotFoundException("labOrder.notFound", request.getLabOrderId());
        }
        if (order.getStatus() == LabOrderStatus.CANCELLED) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_LAB_ORDER_ID, "micro.culture.orderCancelled");
        }
        // QA D820: nor can one voided as entered in error.
        if (order.getStatus() == LabOrderStatus.ENTERED_IN_ERROR) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_LAB_ORDER_ID, "lab.order.enteredInError");
        }
        Patient patient = order.getPatient();
        if (patient == null) {
            throw new BusinessException("micro.culture.orderWithoutPatient");
        }

        LabSpecimen specimen = null;
        if (request.getSpecimenId() != null) {
            specimen = specimenRepository.findById(request.getSpecimenId())
                .orElseThrow(() -> new ResourceNotFoundException("labspecimen.notfound", request.getSpecimenId()));
            if (specimen.getLabOrder() == null
                || !Objects.equals(specimen.getLabOrder().getId(), order.getId())) {
                throw new com.example.hms.exception.FieldBusinessException(FIELD_LAB_ORDER_ID, "micro.culture.specimenOtherOrder");
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
            throw new BusinessException("micro.culture.hospitalRequiredToList");
        }
        return cultureRepository.findForHospital(hospitalId, status, pageable).map(this::toDto);
    }

    @Override
    public MicroCultureResponseDTO updateCulture(UUID cultureId, UUID hospitalId,
                                                 MicroCultureUpdateDTO request) {
        MicroCultureResult culture = loadForChange(cultureId, hospitalId);
        MicroGrowthResult outcomeBefore = culture.getGrowthResult();
        applyCorrectionGuard(culture, request.getCorrectionReason());
        reconcileOutcomeWithIsolates(culture, request);

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
        MicroCultureResult saved = cultureRepository.save(culture);
        notifyIfCorrectionChangedOutcome(saved, outcomeBefore);
        return toDto(saved);
    }

    @Override
    public MicroCultureResponseDTO finalizeCulture(UUID cultureId, UUID hospitalId, UUID actorUserId) {
        MicroCultureResult culture = loadForChange(cultureId, hospitalId);
        if (culture.getStatus() != MicroCultureStatus.PRELIMINARY) {
            throw new BusinessException("micro.culture.alreadyFinal");
        }
        if (culture.getGrowthResult() == null) {
            throw new com.example.hms.exception.FieldBusinessException("growthResult", "micro.culture.growthRequired");
        }
        requireConsistentOutcome(null, culture.getGrowthResult(), 1,
            isolateRepository.countByCultureResult_Id(culture.getId()), "micro.culture.isolateRequired");
        culture.setStatus(MicroCultureStatus.FINAL);
        culture.setFinalizedAt(LocalDateTime.now(clock));
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
        MicroCultureResult culture = loadForChange(cultureId, hospitalId);
        MicroGrowthResult outcomeBefore = culture.getGrowthResult();
        applyCorrectionGuard(culture, request.getCorrectionReason());
        if (culture.getGrowthResult() == MicroGrowthResult.NO_GROWTH) {
            if (!culture.isLocked()) {
                throw new BusinessException("micro.culture.noGrowthBlocksIsolates");
            }
            // QA CX-009: the organism a validated no-growth report turned out to
            // have IS the correction of its outcome, under the same reason. Asking
            // for the outcome first would pass through growth with no organism.
            culture.setGrowthResult(MicroGrowthResult.GROWTH);
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
        MicroCultureResult saved = cultureRepository.save(culture);
        notifyIfCorrectionChangedOutcome(saved, outcomeBefore);
        return toDto(saved);
    }

    @Override
    public MicroCultureResponseDTO updateIsolate(UUID cultureId, UUID isolateId, UUID hospitalId,
                                                 MicroIsolateRequestDTO request) {
        MicroCultureResult culture = loadForChange(cultureId, hospitalId);
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
        MicroCultureResult culture = loadForChange(cultureId, hospitalId);
        MicroIsolate isolate = loadIsolateOf(culture, isolateId);
        applyCorrectionGuard(culture, correctionReason);
        if (culture.isLocked()) {
            long isolates = isolateRepository.countByCultureResult_Id(culture.getId());
            requireConsistentOutcome(culture.getGrowthResult(), culture.getGrowthResult(), isolates, isolates - 1,
                "micro.culture.lastIsolateNeedsOutcome");
        }
        removeWithSusceptibilities(List.of(isolate));
        return toDto(cultureRepository.save(culture));
    }

    @Override
    public MicroCultureResponseDTO addSusceptibility(UUID cultureId, UUID isolateId, UUID hospitalId,
                                                     MicroSusceptibilityRequestDTO request) {
        MicroCultureResult culture = loadForChange(cultureId, hospitalId);
        MicroIsolate isolate = loadIsolateOf(culture, isolateId);
        applyCorrectionGuard(culture, request.getCorrectionReason());
        String antibiotic = request.getAntibioticName().trim();
        if (susceptibilityRepository.existsByIsolate_IdAndAntibioticNameIgnoreCase(isolate.getId(), antibiotic)) {
            throw new com.example.hms.exception.FieldBusinessException("antibioticName", "micro.culture.antibioticDuplicate");
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
        MicroCultureResult culture = loadForChange(cultureId, hospitalId);
        MicroIsolate isolate = loadIsolateOf(culture, isolateId);
        MicroSusceptibility row = susceptibilityRepository.findById(susceptibilityId)
            .filter(s -> s.getIsolate() != null && Objects.equals(s.getIsolate().getId(), isolate.getId()))
            .orElseThrow(() -> new ResourceNotFoundException("microculture.susceptibility.notFound", susceptibilityId));
        applyCorrectionGuard(culture, correctionReason);
        susceptibilityRepository.delete(row);
        return toDto(cultureRepository.save(culture));
    }

    /* ── Guards ────────────────────────────────────────────────────────── */

    /** Every change starts here: the row first, so what it reads next stays true until it commits. */
    private MicroCultureResult loadForChange(UUID cultureId, UUID hospitalId) {
        cultureRepository.lockRow(cultureId);
        return loadScoped(cultureId, hospitalId);
    }

    /**
     * QA CX-009, the one rule, the same one finalize has always applied: a report
     * that says growth names at least one organism. It refuses only a change that
     * MAKES a report break the rule, so a report already left in that state stays
     * readable and correctable: its notes can be fixed, an organism added, its
     * outcome changed.
     */
    private static void requireConsistentOutcome(MicroGrowthResult outcomeBefore, MicroGrowthResult outcomeAfter,
                                                 long isolatesBefore, long isolatesAfter, String messageKey) {
        boolean brokenBefore = outcomeBefore == MicroGrowthResult.GROWTH && isolatesBefore == 0;
        boolean brokenAfter = outcomeAfter == MicroGrowthResult.GROWTH && isolatesAfter == 0;
        if (brokenAfter && !brokenBefore) {
            throw new FieldBusinessException(FIELD_GROWTH_RESULT, messageKey);
        }
    }

    /**
     * A correction of the outcome and the organisms it contradicts change together
     * or not at all. A preliminary report keeps its working order: growth can be
     * set before the organism is identified, and isolates are removed one by one.
     */
    private void reconcileOutcomeWithIsolates(MicroCultureResult culture, MicroCultureUpdateDTO request) {
        MicroGrowthResult requested = request.getGrowthResult();
        boolean removeIsolates = Boolean.TRUE.equals(request.getRemoveIsolates());
        if (removeIsolates && !(culture.isLocked() && requested == MicroGrowthResult.NO_GROWTH)) {
            throw new FieldBusinessException("removeIsolates", "micro.culture.removeIsolatesNeedsNoGrowth");
        }
        long isolates = isolateRepository.countByCultureResult_Id(culture.getId());
        if (removeIsolates && isolates > 0) {
            removeWithSusceptibilities(
                isolateRepository.findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc(culture.getId()));
            isolates = 0;
        }
        if (requested == MicroGrowthResult.NO_GROWTH && isolates > 0) {
            throw new FieldBusinessException(FIELD_GROWTH_RESULT, culture.isLocked()
                ? "micro.culture.isolatesBlockNoGrowthLocked" : "micro.culture.isolatesBlockNoGrowth");
        }
        if (culture.isLocked() && requested != null) {
            requireConsistentOutcome(culture.getGrowthResult(), requested, isolates, isolates,
                "micro.culture.growthNeedsIsolate");
        }
    }

    /**
     * Explicit child delete rather than relying on the DB cascade: the H2 test
     * schema is built from the entities and carries no ON DELETE rule.
     */
    private void removeWithSusceptibilities(List<MicroIsolate> isolates) {
        susceptibilityRepository.deleteAll(susceptibilityRepository
            .findByIsolate_IdInOrderByAntibioticNameAsc(isolates.stream().map(MicroIsolate::getId).toList()));
        isolates.forEach(isolateRepository::delete);
    }

    private MicroCultureResult loadScoped(UUID cultureId, UUID hospitalId) {
        MicroCultureResult culture = cultureRepository.findById(cultureId)
            .orElseThrow(() -> new ResourceNotFoundException("microculture.culture.notFound", cultureId));
        if (hospitalId != null
            && (culture.getHospital() == null
                || !Objects.equals(culture.getHospital().getId(), hospitalId))) {
            throw new ResourceNotFoundException("microculture.culture.notFound", cultureId);
        }
        // QA D820: the entity restriction already keeps a voided order's culture
        // out of every query; this also catches one loaded before the void in the
        // same persistence context, so no read or change (edit, isolate,
        // susceptibility, finalize and its positive-culture notification) ever
        // reaches it. Hidden, so not found, like the restriction answers.
        if (culture.getLabOrder() != null
            && culture.getLabOrder().getStatus() == LabOrderStatus.ENTERED_IN_ERROR) {
            throw new ResourceNotFoundException("microculture.culture.notFound", cultureId);
        }
        return culture;
    }

    private MicroIsolate loadIsolateOf(MicroCultureResult culture, UUID isolateId) {
        return isolateRepository.findById(isolateId)
            .filter(i -> i.getCultureResult() != null
                && Objects.equals(i.getCultureResult().getId(), culture.getId()))
            .orElseThrow(() -> new ResourceNotFoundException("microculture.isolate.notFound", isolateId));
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
            throw new com.example.hms.exception.FieldBusinessException("correctionReason", "micro.culture.correctionReasonRequired");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // QA CX-009: before anything changes, what the report says now. Each
        // correction overwrites the reason on the report and a removed isolate is
        // a deleted row, so this is the record of both.
        correctionRepository.save(MicroCultureCorrection.builder()
            .cultureResult(culture)
            .correctedAt(now)
            .correctedByUserId(com.example.hms.security.CallerIdentity.currentUserId())
            .reason(correctionReason.trim())
            .statusBefore(culture.getStatus())
            .growthBefore(culture.getGrowthResult())
            .gramStainBefore(culture.getGramStain())
            .notesBefore(culture.getNotes())
            .isolatesBefore(describeIsolates(culture))
            .build());
        culture.setStatus(MicroCultureStatus.CORRECTED);
        culture.setCorrectedAt(now);
        culture.setCorrectionReason(correctionReason.trim());
    }

    /** One line per isolate, as the lab recorded them: names, quantities and S/I/R codes, nothing composed. */
    private String describeIsolates(MicroCultureResult culture) {
        List<MicroIsolate> isolates =
            isolateRepository.findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc(culture.getId());
        if (isolates.isEmpty()) {
            return null;
        }
        Map<UUID, List<MicroSusceptibility>> byIsolate = susceptibilityRepository
            .findByIsolate_IdInOrderByAntibioticNameAsc(isolates.stream().map(MicroIsolate::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(s -> s.getIsolate().getId()));
        return isolates.stream()
            .map(isolate -> describeIsolate(isolate, byIsolate.getOrDefault(isolate.getId(), List.of())))
            .collect(Collectors.joining("\n"));
    }

    private static String describeIsolate(MicroIsolate isolate, List<MicroSusceptibility> susceptibilities) {
        StringBuilder line = new StringBuilder("#").append(isolate.getIsolateNumber()).append(' ')
            .append(isolate.getOrganismName());
        if (isolate.getGrowthQuantity() != null) {
            line.append(" (").append(isolate.getGrowthQuantity()).append(')');
        }
        if (!susceptibilities.isEmpty()) {
            line.append(" — ").append(susceptibilities.stream()
                .map(s -> s.getAntibioticName() + ' ' + s.getInterpretation())
                .collect(Collectors.joining(", ")));
        }
        return line.toString();
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
            // Read by the ordering provider, in their language (QA D665).
            notificationService.createNotification("microculture.positive.notification",
                new Object[]{
                    patientName != null && !patientName.isBlank()
                        ? patientName
                        : NotificationLocalizer.key("patient.fallback.yours"),
                    organisms.isBlank()
                        ? NotificationLocalizer.key("microculture.positive.growthFallback")
                        : organisms
                },
                user.getUsername(), POSITIVE_CULTURE_TYPE);
        } catch (RuntimeException ex) {
            log.warn("Positive-culture notification failed for culture {}: {}",
                culture.getId(), ex.getMessage(), ex);
        }
    }

    /**
     * QA CX-009: finalize tells the ordering provider about a positive culture, and
     * a correction can create one or take one back after treatment has started. A
     * report that became growth is told as a positive; one that stopped being
     * growth is told as a correction. Best effort, like finalize.
     */
    private void notifyIfCorrectionChangedOutcome(MicroCultureResult culture, MicroGrowthResult outcomeBefore) {
        MicroGrowthResult outcome = culture.getGrowthResult();
        if (!culture.isLocked() || outcome == outcomeBefore) {
            return;
        }
        if (outcome == MicroGrowthResult.GROWTH) {
            notifyOrderingProviderBestEffort(culture);
        } else if (outcomeBefore == MicroGrowthResult.GROWTH) {
            notifyPositiveWithdrawnBestEffort(culture);
        }
    }

    private void notifyPositiveWithdrawnBestEffort(MicroCultureResult culture) {
        try {
            LabOrder order = culture.getLabOrder();
            Staff orderingStaff = order != null ? order.getOrderingStaff() : null;
            User user = orderingStaff != null ? orderingStaff.getUser() : null;
            if (user == null || user.getUsername() == null) {
                log.warn("Corrected culture {} has no resolvable ordering user; skipping notification",
                    culture.getId());
                return;
            }
            String patientName = culture.getPatient() != null ? culture.getPatient().getFullName() : null;
            notificationService.createNotification(MSG_CORRECTED_NOTIFICATION,
                new Object[]{patientName != null && !patientName.isBlank()
                    ? patientName
                    : NotificationLocalizer.key("patient.fallback.yours")},
                user.getUsername(), CORRECTED_CULTURE_TYPE);
        } catch (RuntimeException ex) {
            log.warn("Corrected-culture notification failed for culture {}: {}",
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
            .labOrderCode(com.example.hms.mapper.LabOrderMapper.orderCodeOf(order))
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
            .corrections(correctionRepository.findByCultureResult_IdOrderByCorrectedAtDesc(culture.getId()).stream()
                .map(this::toCorrectionDto)
                .toList())
            .build();
    }

    private MicroCultureResponseDTO.Correction toCorrectionDto(MicroCultureCorrection correction) {
        return MicroCultureResponseDTO.Correction.builder()
            .id(correction.getId())
            .correctedAt(correction.getCorrectedAt())
            .correctedByName(resolveUserName(correction.getCorrectedByUserId()))
            .reason(correction.getReason())
            .statusBefore(correction.getStatusBefore())
            .growthBefore(correction.getGrowthBefore())
            .gramStainBefore(correction.getGramStainBefore())
            .notesBefore(correction.getNotesBefore())
            .isolatesBefore(correction.getIsolatesBefore())
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
