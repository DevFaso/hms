package com.example.hms.service.impl;

import com.example.hms.enums.UltrasoundOrderStatus;
import com.example.hms.enums.UltrasoundScanType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UltrasoundMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UltrasoundOrderRepository;
import com.example.hms.repository.UltrasoundReportRepository;
import com.example.hms.service.UltrasoundService;
import com.example.hms.service.PatientSubjectReadGuard;
import com.example.hms.service.PatientSubjectReaderRoles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.utility.RoleValidator;

@Service
@RequiredArgsConstructor
@Transactional
public class UltrasoundServiceImpl implements UltrasoundService {
    /** Request properties a refusal names (QA D1352 class, round 30). */
    private static final String FIELD_GESTATIONAL_AGE_AT_ORDER = "gestationalAgeAtOrder";

    private static final String ULTRASOUND_ORDER_NOT_FOUND_KEY = "ultrasound.order.notFound";
    private static final String ULTRASOUND_REPORT_NOT_FOUND_KEY = "ultrasound.report.notFound";



    private final UltrasoundOrderRepository orderRepository;
    private final UltrasoundReportRepository reportRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UltrasoundMapper ultrasoundMapper;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final PatientSubjectReadGuard subjectReadGuard;
    private final RoleValidator roleValidator;
    private final PatientHospitalRegistrationRepository registrationRepository;
    /** QA D1538 review: audits an obstetric rule skipped for an emergency. */
    private final com.example.hms.service.support.ObstetricRuleSkipRecorder skipRecorder;

    @Override
    public UltrasoundOrderResponseDTO createOrder(UltrasoundOrderRequestDTO request, UUID orderedByUserId) {
        // An order is placed only at the hospital the caller acts at, for a
        // patient registered there; either refusal answers as the missing row.
        requireActingHospital(request.getHospitalId());
        requirePatientRegisteredAtActingHospital(request.getPatientId());
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));

        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));

        com.example.hms.service.support.DeceasedPatientGuard.refuseNewCare(patient);
        // QA D1538 review: one form of the priority everywhere (the exemption, the worklists, the row).
        request.setPriority(normalisedPriority(request.getPriority(), "ROUTINE"));
        // QA D1538 (round 40): every scan type of this module but OTHER is a pregnancy scan, so the
        // maternity rule applies, refused on patientId; an URGENT or STAT scan is an emergency, which
        // the rule never refuses, but an emergency for a patient it would refuse is flagged and audited.
        boolean ruleSkipped = judgeOnCreate(patient, request);
        // Validate gestational age for scan type
        validateGestationalAgeForScanType(request.getScanType(), request.getGestationalAgeAtOrder());
        requirePlausibleSchedule(request, null);

        // Use mapper to create entity
        UltrasoundOrder entity = ultrasoundMapper.toOrderEntity(request, patient, hospital);
        entity.setStatus(UltrasoundOrderStatus.ORDERED);
        entity.setObstetricRuleSkipped(ruleSkipped);

        // Set ordered by information. QA D930 class: the id decides who may
        // change or cancel the order; the name is for display. The session
        // decides it: the controller's id parses the username as a UUID,
        // which is null for every real account.
        UUID authorId = com.example.hms.security.CallerIdentity.currentUserId();
        if (authorId == null) {
            authorId = orderedByUserId;
        }
        entity.setOrderedByUserId(authorId);
        if (authorId != null) {
            Staff orderedByStaff = staffRepository.findByUserIdAndHospitalId(authorId, hospital.getId()).orElse(null);
            if (orderedByStaff != null) {
                String name = orderedByStaff.getName();
                if (name == null && orderedByStaff.getUser() != null) {
                    name = orderedByStaff.getUser().getFirstName() + " " + orderedByStaff.getUser().getLastName();
                }
                entity.setOrderedBy(name);
            }
        }

        UltrasoundOrder saved = orderRepository.save(entity);
        if (ruleSkipped) {
            skipRecorder.record(patient, saved.getPriority(), AUDIT_ENTITY_ULTRASOUND_ORDER, saved.getId(), hospital.getId());
        }
        return ultrasoundMapper.toOrderResponseDTO(saved);
    }

    static final String AUDIT_ENTITY_ULTRASOUND_ORDER = com.example.hms.service.support.ObstetricRuleSkipRecorder.ENTITY_ULTRASOUND_ORDER;

    /**
     * QA D1538 review: whether a scan of this type is judged by the obstetric rule. Every type is a
     * prenatal scan but OTHER ("not covered by standard categories"), which need not be; a missing type
     * is judged (the request is refused for it elsewhere).
     */
    static boolean isObstetricScan(com.example.hms.enums.UltrasoundScanType type) {
        return type != com.example.hms.enums.UltrasoundScanType.OTHER;
    }

    /** The rule on a new order: judged unless OTHER; returns whether it was skipped for an emergency. */
    static boolean judgeOnCreate(Patient patient, UltrasoundOrderRequestDTO request) {
        if (!isObstetricScan(request.getScanType())) {
            return false;
        }
        return com.example.hms.utility.ObstetricEligibility.requireObstetricEligible(patient, "patientId",
            com.example.hms.enums.ClinicalPriority.isEmergency(request.getPriority()));
    }

    /**
     * QA D1538 (round 40): an edit that leaves an exemption the order was placed under is judged
     * again: an URGENT or STAT scan lowered to routine (refused on {@code priority}), or an OTHER scan
     * turned into a prenatal one (refused on {@code scanType}). An edit that keeps the order exempt, or
     * one that was judged when it was ordered (or ordered before the rule), is not judged again.
     *
     * <p>QA D1538 review: the review flag ({@code obstetricRuleSkipped}) is re-evaluated on every edit
     * against the order as it will stand: set when it is a prenatal emergency scan for a patient the rule
     * would refuse, cleared when the reason is gone (type OTHER, a patient record now eligible, or no
     * longer an emergency and judged and passing).
     *
     * @return the review flag the order carries after the edit; the caller audits a change of it.
     */
    static boolean requireObstetricPatientOnEdit(UltrasoundOrder order, UltrasoundOrderRequestDTO request) {
        String resultingPriority = request.getPriority() != null ? request.getPriority() : order.getPriority();
        com.example.hms.enums.UltrasoundScanType resultingType =
            request.getScanType() != null ? request.getScanType() : order.getScanType();
        boolean judgedBefore = isObstetricScan(order.getScanType())
            && !com.example.hms.enums.ClinicalPriority.isEmergency(order.getPriority());
        boolean obstetricAfter = isObstetricScan(resultingType);
        boolean emergencyAfter = com.example.hms.enums.ClinicalPriority.isEmergency(resultingPriority);
        if (obstetricAfter && !emergencyAfter && !judgedBefore) {
            String field = isObstetricScan(order.getScanType()) ? "priority" : "scanType";
            com.example.hms.utility.ObstetricEligibility.requireObstetricEligible(order.getPatient(), field, false);
        }
        return obstetricAfter && emergencyAfter
            && com.example.hms.utility.ObstetricEligibility.isIneligible(order.getPatient());
    }

    /**
     * QA D1538 review: the priority as it is stored and read (ROUTINE, URGENT, STAT), whatever case or
     * padding the request used. A blank one is {@code whenBlank} (ROUTINE on a new order, the order's
     * own on an edit); a value that names no priority is refused on {@code priority}.
     */
    static String normalisedPriority(String requested, String whenBlank) {
        if (requested == null || requested.isBlank()) {
            return whenBlank;
        }
        com.example.hms.enums.ClinicalPriority parsed = com.example.hms.enums.ClinicalPriority.parse(requested);
        if (parsed == null) {
            throw new com.example.hms.exception.FieldBusinessException("priority", "validation.priority.invalid");
        }
        return parsed.name();
    }

    /**
     * QA D1447: an appointment is not booked in the past, its time is a time of
     * day, and a scan count starts at 1. A value an edit only echoes is not
     * judged (the imaging order's rule): an order whose appointment has passed,
     * or stored before this check, stays editable on its other fields.
     */
    public static void requirePlausibleSchedule(UltrasoundOrderRequestDTO request, UltrasoundOrder current) {
        java.time.LocalDate date = request.getScheduledDate();
        if (date != null && !date.equals(current == null ? null : current.getScheduledDate())
            && date.isBefore(java.time.LocalDate.now(java.time.ZoneId.systemDefault()))) {
            throw new com.example.hms.exception.FieldBusinessException("scheduledDate", "ultrasound.order.scheduledDatePast");
        }
        String time = request.getScheduledTime();
        if (time != null && !time.isBlank() && !time.equals(current == null ? null : current.getScheduledTime())
            && !isTimeOfDay(time.trim())) {
            throw new com.example.hms.exception.FieldBusinessException("scheduledTime", "ultrasound.order.scheduledTimeInvalid");
        }
        Integer count = request.getScanCountForPregnancy();
        if (count != null && count < 1 && !count.equals(current == null ? null : current.getScanCountForPregnancy())) {
            throw new com.example.hms.exception.FieldBusinessException("scanCountForPregnancy", "ultrasound.order.scanCountInvalid");
        }
    }

    private static boolean isTimeOfDay(String value) {
        try {
            java.time.LocalTime.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException ex) {
            return false;
        }
    }

    @Override
    public UltrasoundOrderResponseDTO updateOrder(UUID orderId, UltrasoundOrderRequestDTO request) {
        UltrasoundOrder order = getOrderInScope(orderId);
        requireOrderAuthor(order);

        // Prevent modification of completed orders (QA D1098: and reported ones)
        if (order.getStatus() == UltrasoundOrderStatus.COMPLETED
                || order.getStatus() == UltrasoundOrderStatus.REPORT_AVAILABLE) {
            throw new BusinessException("ultrasound.order.completedNoEdit");
        }

        if (order.getStatus() == UltrasoundOrderStatus.CANCELLED) {
            throw new BusinessException("ultrasound.order.cancelledNoEdit");
        }

        // Update hospital if changed
        if (request.getHospitalId() != null && !request.getHospitalId().equals(order.getHospital().getId())) {
            // Never moved away from the hospital the caller acts at: the
            // portal echoes the acting hospital, so any other one is refused
            // exactly as a missing hospital. A verified super-admin in global
            // view keeps the old behaviour.
            requireActingHospital(request.getHospitalId());
            Hospital newHospital = hospitalRepository.findClinicalById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));
            order.setHospital(newHospital);
        }

        requirePlausibleSchedule(request, order);
        // QA D1538 review: an absent priority keeps the order's own; a present one is stored normalised.
        request.setPriority(normalisedPriority(request.getPriority(), null));
        boolean wasFlagged = order.isObstetricRuleSkipped();
        boolean flagged = requireObstetricPatientOnEdit(order, request);
        ultrasoundMapper.updateOrderFromRequest(order, request);
        order.setObstetricRuleSkipped(flagged);

        UltrasoundOrder saved = orderRepository.save(order);
        UUID savedHospitalId = saved.getHospital() != null ? saved.getHospital().getId() : null;
        if (flagged && !wasFlagged) {
            skipRecorder.record(saved.getPatient(), saved.getPriority(), AUDIT_ENTITY_ULTRASOUND_ORDER, saved.getId(),
                savedHospitalId);
        } else if (!flagged && wasFlagged) {
            skipRecorder.recordCleared(saved.getPatient(), saved.getPriority(), AUDIT_ENTITY_ULTRASOUND_ORDER, saved.getId(),
                savedHospitalId);
        }
        return ultrasoundMapper.toOrderResponseDTO(saved);
    }

    @Override
    public UltrasoundOrderResponseDTO cancelOrder(UUID orderId, String cancellationReason) {
        UltrasoundOrder order = getOrderInScope(orderId);
        requireOrderAuthor(order);

        if (order.getStatus() == UltrasoundOrderStatus.CANCELLED) {
            throw new BusinessException("ultrasound.order.alreadyCancelled");
        }

        // QA D1098 (round 19): an order whose report is available is resulted
        // too - it was cancellable, leaving a report on a cancelled order.
        if (order.getStatus() == UltrasoundOrderStatus.COMPLETED
                || order.getStatus() == UltrasoundOrderStatus.REPORT_AVAILABLE) {
            throw new BusinessException("ultrasound.order.completedNoCancel");
        }
        // QA D1098: every cancel states why (the endpoint's reason was optional).
        String reason = com.example.hms.service.support.CancelledOrders.requireCancelReason(cancellationReason, "cancellationReason");

        order.setStatus(UltrasoundOrderStatus.CANCELLED);
        order.setCancellationReason(reason);
        // QA D1047: a cancellation is stamped with when and by whom, like every order.
        order.setCancelledAt(LocalDateTime.now(java.time.ZoneId.systemDefault()));
        order.setCancelledBy(cancellerName(order));

        UltrasoundOrder saved = orderRepository.save(order);
        return ultrasoundMapper.toOrderResponseDTO(saved);
    }

    /**
     * QA D930 class: an ultrasound order is changed or cancelled by the
     * clinician who placed it, or a verified super-admin in global view. An
     * order written before V243 has no recorded author and keeps the old rule
     * (any doctor or midwife in scope). Anyone else gets 403; the order was
     * resolved in the caller's scope first.
     */
    private void requireOrderAuthor(UltrasoundOrder order) {
        UUID author = order.getOrderedByUserId();
        if (author == null || com.example.hms.security.CallerIdentity.isGlobalSuperAdmin()) {
            return;
        }
        if (!author.equals(com.example.hms.security.CallerIdentity.currentUserId())) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("ultrasound.order.authorOnly"));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UltrasoundOrderResponseDTO getOrderById(UUID orderId) {
        UltrasoundOrder order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_ORDER_NOT_FOUND_KEY, orderId));
        // A patient caller reads only their own; staff read their active
        // hospital's, and — staff who are also patients (#754's rule) — their
        // own order elsewhere, as its patient. Every refusal answers exactly
        // as a missing id does.
        boolean asPatient = subjectReadGuard.isPatientOnly(PatientSubjectReaderRoles.ULTRASOUND_READS);
        boolean readable;
        if (asPatient) {
            readable = subjectReadGuard.callerOwns(order.getPatient());
        } else if (inStaffScope(order.getHospital())) {
            readable = true;
        } else {
            asPatient = subjectReadGuard.ownsAsItsPatient(order.getPatient());
            readable = asPatient;
        }
        if (!readable) {
            throw new ResourceNotFoundException(ULTRASOUND_ORDER_NOT_FOUND_KEY, orderId);
        }
        return toOrderResponseDTO(order, asPatient);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundOrderResponseDTO> getOrdersByPatientId(UUID patientId) {
        return readOrders(patientId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundOrderResponseDTO> getOrdersByPatientIdAndStatus(UUID patientId, UltrasoundOrderStatus status) {
        return readOrders(patientId, status);
    }

    /**
     * E9 #59d — ultrasound orders follow the patient across the readable
     * hospitals when the caller acts in one; a super-admin in global view
     * keeps the unscoped read. Every foreign row surfaced is accounted.
     */
    private List<UltrasoundOrderResponseDTO> readOrders(UUID patientId, UltrasoundOrderStatus status) {
        // A patient caller reads only their own. Another patient's id answers
        // exactly as an id that matches no row does -- an empty list -- and
        // before any lookup that could answer differently.
        if (!subjectReadGuard.mayRead(PatientSubjectReaderRoles.ULTRASOUND_READS, patientId)) {
            return List.of();
        }
        if (subjectReadGuard.ownsAsItsPatient(patientId)) {
            // Their own record, read as its patient wherever it was written —
            // a patient, or staff who are also this patient (#754's rule), whom
            // the staff branch below would hold to the hospital they work at.
            // Not a disclosure, so no reach is recorded; released reports only.
            List<UltrasoundOrder> own = status == null
                ? orderRepository.findAllByPatientId(patientId)
                : orderRepository.findByPatientIdAndStatus(patientId, status);
            return own.stream().map(order -> toOrderResponseDTO(order, true)).toList();
        }
        boolean patientOnly = subjectReadGuard.isPatientOnly(PatientSubjectReaderRoles.ULTRASOUND_READS);
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID actingHospitalId = ctx.pinnedHospitalId();
        List<UltrasoundOrder> orders;
        if (actingHospitalId == null) {
            orders = status == null
                ? orderRepository.findAllByPatientId(patientId)
                : orderRepository.findByPatientIdAndStatus(patientId, status);
        } else {
            UUID requesterUserId = ctx.getPrincipalUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, actingHospitalId);
            orders = status == null
                ? orderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedDateDesc(patientId, readable)
                : orderRepository.findByPatient_IdAndHospital_IdInAndStatusOrderByOrderedDateDesc(patientId, readable, status);
            reachRecorder.recordReach(patientId, actingHospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(orders.stream().map(o -> CrossHospitalReachRecorder.hospitalIdOf(o.getHospital())).toList(), actingHospitalId),
                "Cross-hospital ultrasound order read on the treatment relationship");
        }
        return orders.stream()
            .map(order -> toOrderResponseDTO(order, patientOnly))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundOrderResponseDTO> getOrdersByHospitalId(UUID hospitalId) {
        if (!isActingHospital(hospitalId)) {
            return List.of();
        }
        return orderRepository.findAllByHospitalId(hospitalId).stream()
            .map(ultrasoundMapper::toOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundOrderResponseDTO> getPendingOrders(UUID hospitalId) {
        if (!isActingHospital(hospitalId)) {
            return List.of();
        }
        return orderRepository.findPendingOrders(hospitalId).stream()
            .map(ultrasoundMapper::toOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundOrderResponseDTO> getHighRiskOrders(UUID hospitalId) {
        if (!isActingHospital(hospitalId)) {
            return List.of();
        }
        return orderRepository.findAllHighRiskOrders(hospitalId).stream()
            .map(ultrasoundMapper::toOrderResponseDTO)
            .toList();
    }

    /**
     * QA D1150 (round 21): a report records findings. A blank report was saved
     * with every value null and read "RAPPORT Normal", with "Notify the
     * patient" offered: the finding category alone (preselected by the form)
     * is not a finding. At least one measurement, observation or written
     * finding must be present.
     *
     * <p>QA D1182 (round 22): the yes/no boxes (cardiac activity, movement,
     * tone, nasal bone, anatomy survey complete, ...) are NOT findings: the
     * report form pre-fills cardiac activity, movement and tone, so the edit
     * of a blank report sent them as {@code true} and saved "RAPPORT Normal"
     * with every value null. Create and edit go through here alike. A scan
     * with no cardiac activity says so in the findings or the interpretation.
     * {@code UltrasoundReportFindingsTest} sets every box at once and expects
     * the refusal. One fetus is not a finding either (it was a default for
     * years); more than one is.
     *
     * <p>QA D1525 (round 38): a finding is charted only when the clinician
     * states it. The templates no longer pre-fill the three flags, the
     * category, the gestational age or the fetus count, the entity no longer
     * defaults the flags to {@code true}, and the mapper keeps a missing flag
     * {@code null} ("not assessed"). So every value counted here was entered by
     * someone. {@code UltrasoundReportFindingsTest} and
     * {@code ClinicalTemplatePrefillGuardTest} pin it.
     */
    static void requireReportFindings(UltrasoundReportRequestDTO request) {
        Integer fetuses = request.getNumberOfFetuses();
        com.example.hms.service.support.ClinicalContent.requireAny("findingsSummary",
            "ultrasound.report.findingsRequired",
            fetuses != null && fetuses > 1 ? fetuses : null,
            request.getGestationalAgeAtScan(), request.getGestationalAgeDays(), request.getNuchalTranslucencyMm(),
            request.getCrownRumpLengthMm(), request.getEstimatedDueDate(),
            request.getFetalPosition(), request.getBiparietalDiameterMm(), request.getHeadCircumferenceMm(),
            request.getAbdominalCircumferenceMm(), request.getFemurLengthMm(), request.getEstimatedFetalWeightGrams(),
            request.getPlacentalLocation(), request.getPlacentalGrade(), request.getAmnioticFluidIndex(),
            request.getAmnioticFluidLevel(), request.getCervicalLengthMm(), request.getUmbilicalArteryDoppler(),
            request.getUterineArteryDoppler(), request.getFetalHeartRate(), request.getAnatomyFindings(), request.getFindingsSummary(), request.getInterpretation(),
            request.getAnomalyDescription());
    }

    /**
     * QA D1525: the clinician chooses the finding category; nothing defaults it
     * to NORMAL. Asked after {@link #requireReportFindings}.
     */
    static void requireFindingCategory(UltrasoundReportRequestDTO request) {
        com.example.hms.service.support.ClinicalContent.requirePresent(request.getFindingCategory(),
            "findingCategory", "ultrasoundReport.findingCategory.required");
    }

    @Override
    public UltrasoundReportResponseDTO createOrUpdateReport(UUID orderId, UltrasoundReportRequestDTO request, UUID performedByUserId) {
        UltrasoundOrder order = getOrderInScope(orderId);

        if (order.getStatus() == UltrasoundOrderStatus.CANCELLED) {
            throw new BusinessException("ultrasound.report.cancelledOrder");
        }

        String performerName = resolveStaffName(performedByUserId, order.getHospital().getId());

        UltrasoundReport report = reportRepository.findByUltrasoundOrderId(orderId).orElse(null);
        if (report != null && Boolean.TRUE.equals(report.getReportReviewedByProvider())) {
            throw new BusinessException("ultrasound.report.reviewedNoEdit");
        }
        // QA D1525: the empty report is refused first, so an untouched template
        // (which no longer pre-selects NORMAL) reads as empty, not "pick a category".
        requireReportFindings(request);
        requireFindingCategory(request);

        if (report == null) {
            report = ultrasoundMapper.toReportEntity(request, order, order.getHospital());
            if (performerName != null && request.getScanPerformedBy() == null) {
                report.setScanPerformedBy(performerName);
            }
        } else {
            ultrasoundMapper.updateReportFromRequest(report, request);
        }

        // Update order status
        if (order.getStatus() == UltrasoundOrderStatus.ORDERED || order.getStatus() == UltrasoundOrderStatus.SCHEDULED) {
            order.setStatus(UltrasoundOrderStatus.IN_PROGRESS);
        }

        UltrasoundReport saved = reportRepository.save(report);
        orderRepository.save(order);

        return ultrasoundMapper.toReportResponseDTO(saved);
    }

    @Override
    public UltrasoundReportResponseDTO markReportReviewed(UUID reportId, UUID reviewedByUserId) {
        UltrasoundReport report = getReportInScope(reportId);
        // QA D1066 class: a cancelled scan order is not completed back to life by its report's review.
        com.example.hms.service.support.CancelledOrders.requireNotCancelled(report.getUltrasoundOrder().getStatus(),
            "ultrasound.order.cancelled.locked");

        if (report.getReportReviewedByProvider() != null && report.getReportReviewedByProvider()) {
            throw new BusinessException("ultrasound.report.alreadyReviewed");
        }

        UUID hospitalId = report.getUltrasoundOrder().getHospital().getId();
        String reviewerName = resolveStaffName(reviewedByUserId, hospitalId);

        report.setReportReviewedByProvider(true);
        if (reviewerName != null) {
            report.setReportFinalizedBy(reviewerName);
        }
        report.setReportFinalizedAt(LocalDateTime.now());

        // Mark order as completed
        UltrasoundOrder order = report.getUltrasoundOrder();
        order.setStatus(UltrasoundOrderStatus.COMPLETED);

        UltrasoundReport saved = reportRepository.save(report);
        orderRepository.save(order);

        return ultrasoundMapper.toReportResponseDTO(saved);
    }

    @Override
    public UltrasoundReportResponseDTO markPatientNotified(UUID reportId) {
        UltrasoundReport report = getReportInScope(reportId);

        if (report.getPatientNotifiedAt() != null) {
            throw new BusinessException("ultrasound.report.patientAlreadyNotified");
        }

        report.setPatientNotifiedAt(LocalDateTime.now());

        UltrasoundReport saved = reportRepository.save(report);
        return ultrasoundMapper.toReportResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public UltrasoundReportResponseDTO getReportById(UUID reportId) {
        UltrasoundReport report = reportRepository.findById(reportId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_REPORT_NOT_FOUND_KEY, reportId));
        if (!mayReadReport(report)) {
            throw new ResourceNotFoundException(ULTRASOUND_REPORT_NOT_FOUND_KEY, reportId);
        }
        return ultrasoundMapper.toReportResponseDTO(report);
    }

    @Override
    @Transactional(readOnly = true)
    public UltrasoundReportResponseDTO getReportByOrderId(UUID orderId) {
        UltrasoundReport report = reportRepository.findByUltrasoundOrderId(orderId)
            .orElseThrow(() -> reportForOrderNotFound(orderId));
        if (!mayReadReport(report)) {
            throw reportForOrderNotFound(orderId);
        }
        return ultrasoundMapper.toReportResponseDTO(report);
    }

    /**
     * The one rule for reading an ultrasound report by id or by order; a
     * refusal is answered by the caller exactly as a missing report is.
     *
     * <p>A patient-only caller reads a report only when it is theirs AND
     * {@link UltrasoundReport#isReleasedToPatient() released to them}: reviewed
     * by a provider and communicated. An unreviewed report can carry a finding
     * nobody has discussed with the patient yet. Release is asked first — it
     * costs nothing — and ownership second, which initialises the order.
     *
     * <p>Staff read reports at their active hospital only, as the imaging and
     * procedure siblings do; before this, any clinician read any tenant's
     * prenatal report by id.
     */
    private boolean mayReadReport(UltrasoundReport report) {
        if (subjectReadGuard.isPatientOnly(PatientSubjectReaderRoles.ULTRASOUND_READS)) {
            return report.isReleasedToPatient()
                && subjectReadGuard.callerOwns(report.getUltrasoundOrder().getPatient());
        }
        // Staff who are also patients read their own released report outside
        // their hospital, as its patient (#754's rule).
        return inStaffScope(report.getHospital())
            || (report.isReleasedToPatient()
                && subjectReadGuard.ownsAsItsPatient(report.getUltrasoundOrder().getPatient()));
    }

    /**
     * The order, only when it belongs to the hospital the caller acts at. A
     * foreign order answers exactly as a missing one; a null scope (a
     * verified super-admin in global view) reaches any. The same rule as the
     * imaging-order writes.
     */
    private UltrasoundOrder getOrderInScope(UUID orderId) {
        UltrasoundOrder order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_ORDER_NOT_FOUND_KEY, orderId));
        if (!inStaffScope(order.getHospital())) {
            throw new ResourceNotFoundException(ULTRASOUND_ORDER_NOT_FOUND_KEY, orderId);
        }
        return order;
    }

    /** The report, only at the hospital the caller acts at; otherwise as missing. */
    private UltrasoundReport getReportInScope(UUID reportId) {
        UltrasoundReport report = reportRepository.findById(reportId)
            .orElseThrow(() -> new ResourceNotFoundException(ULTRASOUND_REPORT_NOT_FOUND_KEY, reportId));
        if (!inStaffScope(report.getHospital())) {
            throw new ResourceNotFoundException(ULTRASOUND_REPORT_NOT_FOUND_KEY, reportId);
        }
        return report;
    }

    /**
     * A write places an order only at the hospital the caller acts at; any
     * other hospital is answered exactly as a missing one.
     */
    private void requireActingHospital(UUID hospitalId) {
        if (!isActingHospital(hospitalId)) {
            throw new ResourceNotFoundException("hospital.notFound", hospitalId);
        }
    }

    /**
     * A hospital worklist is the acting hospital's. Another hospital's id
     * answers as one with no rows, the answer an unknown hospital gets.
     */
    private boolean isActingHospital(UUID hospitalId) {
        UUID scope = roleValidator.requireActiveHospitalId();
        return scope == null || scope.equals(hospitalId);
    }

    /**
     * The patient must be registered at the acting hospital (any
     * registration, as for lab, imaging and consultation orders). A patient
     * registered elsewhere answers exactly as a missing one.
     */
    private void requirePatientRegisteredAtActingHospital(UUID patientId) {
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope != null && (patientId == null
                || !registrationRepository.existsByPatientIdAndHospitalId(patientId, scope))) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }
    }

    /**
     * The imaging and procedure siblings' hospital boundary: a staff caller
     * reads rows at their active hospital; a super-admin in global view (a
     * null scope) reads any. Not applied to a patient-only caller, whose
     * boundary is ownership — their own record at any hospital, as on the
     * encounter reads.
     */
    private boolean inStaffScope(Hospital hospital) {
        UUID scope = roleValidator.requireActiveHospitalId();
        return scope == null || hospital == null || scope.equals(hospital.getId());
    }

    /**
     * The order DTO embeds its report. A patient-only caller gets the report
     * only once it is released to them — the same rule as reading it directly.
     */
    private UltrasoundOrderResponseDTO toOrderResponseDTO(UltrasoundOrder order, boolean patientOnly) {
        UltrasoundOrderResponseDTO dto = ultrasoundMapper.toOrderResponseDTO(order);
        if (patientOnly && dto != null && order.getReport() != null && !order.getReport().isReleasedToPatient()) {
            dto.setReport(null);
        }
        return dto;
    }

    private static ResourceNotFoundException reportForOrderNotFound(UUID orderId) {
        return new ResourceNotFoundException("ultrasound.report.notFoundForOrder", orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundReportResponseDTO> getReportsRequiringFollowUp(UUID hospitalId) {
        if (!isActingHospital(hospitalId)) {
            return List.of();
        }
        return reportRepository.findReportsRequiringFollowUp(hospitalId).stream()
            .map(ultrasoundMapper::toReportResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UltrasoundReportResponseDTO> getReportsWithAnomalies(UUID hospitalId) {
        if (!isActingHospital(hospitalId)) {
            return List.of();
        }
        return reportRepository.findReportsWithAnomalies(hospitalId).stream()
            .map(ultrasoundMapper::toReportResponseDTO)
            .toList();
    }

    /**
     * QA D1525: a template suggests the form, never a finding. It used to
     * pre-fill cardiac activity, movement and tone as present, the category as
     * NORMAL, a gestational age and one fetus, and an untouched template was
     * charted as all of that. It now carries only the date of the scan (today,
     * an administrative default the clinician corrects); every observation,
     * measurement and the category are left for the clinician to state.
     * {@code ClinicalTemplatePrefillGuardTest} fails if a finding comes back.
     */
    @Override
    @Transactional(readOnly = true)
    public UltrasoundReportRequestDTO getNuchalTranslucencyTemplate() {
        return UltrasoundReportRequestDTO.builder()
            .scanDate(java.time.LocalDate.now())
            .build();
    }

    /** QA D1525: as {@link #getNuchalTranslucencyTemplate()}, the date only. */
    @Transactional(readOnly = true)
    @Override
    public UltrasoundReportRequestDTO getAnatomyScanTemplate() {
        return UltrasoundReportRequestDTO.builder()
            .scanDate(java.time.LocalDate.now())
            .build();
    }

    // Private helper methods

    private void validateGestationalAgeForScanType(UltrasoundScanType scanType, Integer gestationalAgeWeeks) {
        if (gestationalAgeWeeks == null) {
            return; // Optional validation
        }

        switch (scanType) {
            case NUCHAL_TRANSLUCENCY:
                if (gestationalAgeWeeks < 11 || gestationalAgeWeeks > 14) {
                    throw new com.example.hms.exception.FieldBusinessException(FIELD_GESTATIONAL_AGE_AT_ORDER, "ultrasound.ga.NUCHAL_TRANSLUCENCY", gestationalAgeWeeks);
                }
                break;
            case ANATOMY_SCAN:
                if (gestationalAgeWeeks < 18 || gestationalAgeWeeks > 22) {
                    throw new com.example.hms.exception.FieldBusinessException(FIELD_GESTATIONAL_AGE_AT_ORDER, "ultrasound.ga.ANATOMY_SCAN", gestationalAgeWeeks);
                }
                break;
            case GROWTH_SCAN:
                if (gestationalAgeWeeks < 24) {
                    throw new com.example.hms.exception.FieldBusinessException(FIELD_GESTATIONAL_AGE_AT_ORDER, "ultrasound.ga.GROWTH_SCAN", gestationalAgeWeeks);
                }
                break;
            default:
                break;
        }
    }

    /** QA D1047: the canceller's name at the order's hospital, else the session's username. */
    private String cancellerName(UltrasoundOrder order) {
        UUID caller = com.example.hms.security.CallerIdentity.currentUserId();
        String name = order.getHospital() != null ? resolveStaffName(caller, order.getHospital().getId()) : null;
        if (name != null) {
            return name;
        }
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    private String resolveStaffName(UUID userId, UUID hospitalId) {
        if (userId == null) return null;
        Staff staff = staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
        if (staff == null) return null;
        if (staff.getName() != null) return staff.getName();
        return staff.getUser() != null
            ? staff.getUser().getFirstName() + " " + staff.getUser().getLastName()
            : null;
    }
}
