package com.example.hms.service.impl;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.service.lab.SupersededLabResults;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.service.PatientLabResultService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;
import com.example.hms.security.context.HospitalContextHolder;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientLabResultServiceImpl implements PatientLabResultService {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_ABNORMAL = "ABNORMAL";
    private static final String STATUS_ABNORMAL_HIGH = "ABNORMAL_HIGH";
    private static final String STATUS_ABNORMAL_LOW = "ABNORMAL_LOW";
    private static final String STATUS_CRITICAL = "CRITICAL";
    private static final String STATUS_PENDING = "PENDING";

    private final LabResultRepository labResultRepository;
    private final PatientChartAccess patientChartAccess;
    private final HospitalRepository hospitalRepository;
    private final LabResultMapper labResultMapper;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;

    @Override
    @Transactional(readOnly = true)
    public List<PatientLabResultResponseDTO> getLabResultsForPatient(UUID patientId, UUID hospitalId, int limit) {
        return getLabResults(patientId, hospitalId, limit, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientLabResultResponseDTO> getLabResultsForPatientPortal(UUID patientId, UUID hospitalId, int limit) {
        return getLabResults(patientId, hospitalId, limit, true);
    }

    /**
     * @param redactUnreleased B3 — true on the patient-facing path: an
     *        unreleased row keeps its identity (test, order, hospital) and
     *        {@code PENDING} but loses the value, unit, reference range,
     *        notes and performer. A preliminary value is the lab's until
     *        it is released; the patient learns only that one is coming.
     */
    private List<PatientLabResultResponseDTO> getLabResults(UUID patientId, UUID hospitalId, int limit,
                                                            boolean redactUnreleased) {
        log.info("Fetching lab results for patient {} in hospital {}", patientId, hospitalId);

        // See PatientChartAccess — cross-hospital safe, and adds the hospital
        // authorization this read previously relied on the finder for.
        Patient patient = patientChartAccess.require(patientId, hospitalId);

        int effectiveLimit = limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        // Read the caller's limit first. If the pairing then removes rows —
        // and only the patient path can remove any — read again at the cap and
        // resolve once more, so the caller gets the count it asked for rather
        // than a page one short for every pair it happened to contain. Two
        // queries at most, and the second only when a pair was actually found:
        // widening every call to MAX_LIMIT made the staff path read a hundred
        // rows to return one, and a fixed +1 still came up short whenever a
        // page held more than one pair.

        List<LabResult> results = fetchRows(patient, hospitalId, effectiveLimit);
        List<LabResult> visible = resolvePairs(results, redactUnreleased, effectiveLimit);
        if (visible.size() < effectiveLimit && results.size() >= effectiveLimit) {
            results = fetchRows(patient, hospitalId, MAX_LIMIT);
            visible = resolvePairs(results, redactUnreleased, effectiveLimit);
        }

        if (hospitalId != null) {
            // Accounted on what the patient is actually shown, not on the wider
            // window the pairing needed.
            UUID requesterUserId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
            reachRecorder.recordReach(patient.getId(), hospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(
                    visible.stream().map(r -> hospitalIdOf(r.getLabOrder())).toList(), hospitalId),
                "Cross-hospital lab result read on the treatment relationship");
        }

        return visible.stream()
            .map(result -> toResponse(result, redactUnreleased))
            .toList();
    }

    /**
     * The newest {@code window} rows for this patient, within the readable
     * hospitals when a hospital scope is in play.
     */
    private List<LabResult> fetchRows(Patient patient, UUID hospitalId, int window) {
        Pageable pageable = PageRequest.of(0, window, Sort.by(Sort.Direction.DESC, "resultDate"));
        List<LabResult> results;
        if (hospitalId != null) {
            Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));
            // E9 #59b — results follow the patient across the readable
            // hospitals (lab rows carry no sensitivity tag, V158); every
            // foreign row surfaced is accounted.
            UUID requesterUserId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patient.getId(), hospital.getId());
            results = labResultRepository
                .findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(patient.getId(), readable, pageable);
        } else {
            // Fallback: patient-only query (no hospital scope) — common for patient portal
            results = labResultRepository.findByLabOrder_Patient_Id(patient.getId()).stream()
                .sorted((a, b) -> {
                    if (a.getResultDate() == null && b.getResultDate() == null) return 0;
                    if (a.getResultDate() == null) return 1;
                    if (b.getResultDate() == null) return -1;
                    return b.getResultDate().compareTo(a.getResultDate());
                })
                .limit(window)
                .toList();
        }
        return results;
    }

    /**
     * An analyzer reporting preliminary then final stores two rows — it must,
     * because the message control id is the replay key and a critical value is
     * notified against the row it was raised on. The patient sees the row the
     * analyzer has not superseded; the one it replaced is dropped from their
     * view, never from the record. Order completion applies the same rule, from
     * the same class. The staff path resolves nothing: both rows are the record.
     */
    private List<LabResult> resolvePairs(List<LabResult> results, boolean redactUnreleased, int limit) {
        Set<UUID> superseded = redactUnreleased
            ? SupersededLabResults.supersededRowIds(results, withSiblingsOfSupersedableRows(results))
            : Set.of();
        return results.stream()
            .filter(result -> !superseded.contains(result.getId()))
            .limit(limit)
            .toList();
    }

    private PatientLabResultResponseDTO toResponse(LabResult result, boolean redactUnreleased) {
        LabOrder labOrder = result.getLabOrder();
        LabTestDefinition testDefinition = labOrder != null ? labOrder.getLabTestDefinition() : null;
        LabResultResponseDTO mapped = labResultMapper.toResponseDTO(result);

        PatientLabResultResponseDTO.PatientLabResultResponseDTOBuilder response = PatientLabResultResponseDTO.builder()
            .id(result.getId())
            .testName(resolveTestName(labOrder, mapped))
            .testCode(testDefinition != null ? testDefinition.getTestCode() : null)
            .status(resolveStatus(result, mapped))
            .released(result.isReleased())
            .collectedAt(labOrder != null ? labOrder.getOrderDatetime() : null)
            .resultedAt(result.getResultDate())
            .orderedBy(resolveStaffName(labOrder != null ? labOrder.getOrderingStaff() : null))
            .category(testDefinition != null ? testDefinition.getCategory() : null)
            .hospitalId(hospitalIdOf(labOrder))
            .hospitalName(labOrder != null && labOrder.getHospital() != null ? labOrder.getHospital().getName() : null);

        if (redactUnreleased && !result.isReleased()) {
            return response.build();
        }
        String unit = resolveUnit(result, testDefinition);
        return response
            .value(result.getResultValue())
            .unit(unit)
            .referenceRange(formatReferenceRange(mapped != null ? mapped.getReferenceRanges() : null, unit))
            .performedBy(resolveAssignmentUser(result.getAssignment()))
            .notes(result.getNotes())
            .build();
    }

    /**
     * The page, plus every result on the orders whose page rows the analyzer
     * marked preliminary.
     *
     * <p>A row that supersedes a preliminary is newer, and the page is newest
     * first, so it is almost always on the page already — but two rows for one
     * observation usually carry the SAME observation time, and a tie can fall
     * either side of the page edge. Rather than page wider and hope, ask for
     * the handful of siblings that could matter. Only orders that actually
     * carry a preliminary on this page are fetched, so a patient with no
     * analyzer results costs no query at all.
     */
    private List<LabResult> withSiblingsOfSupersedableRows(List<LabResult> page) {
        Set<UUID> ordersToInspect = page.stream()
            .filter(SupersededLabResults::mayBeSuperseded)
            .map(result -> orderIdOf(result.getLabOrder()))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        if (ordersToInspect.isEmpty()) {
            return page;
        }
        List<LabResult> siblings = labResultRepository.findByLabOrder_IdIn(ordersToInspect);
        List<LabResult> known = new java.util.ArrayList<>(page.size() + siblings.size());
        known.addAll(page);
        known.addAll(siblings);
        return known;
    }

    /**
     * The ORDER's id — named apart from {@link #hospitalIdOf}, which takes the
     * same argument and returns a hospital id. A reader who mistook one for
     * the other would empty the sibling lookup without failing a test.
     */
    private static UUID orderIdOf(LabOrder order) {
        return order == null ? null : order.getId();
    }

    /** Provenance (E9): the hospital that resulted the row, off the order that owns it. */
    private static UUID hospitalIdOf(LabOrder order) {
        return order == null ? null : CrossHospitalReachRecorder.hospitalIdOf(order.getHospital());
    }

    private String resolveUnit(LabResult result, LabTestDefinition definition) {
        if (result.getResultUnit() != null && !result.getResultUnit().isBlank()) {
            return result.getResultUnit();
        }
        if (definition != null && definition.getUnit() != null && !definition.getUnit().isBlank()) {
            return definition.getUnit();
        }
        return null;
    }

    private String resolveTestName(LabOrder order, LabResultResponseDTO mapped) {
        if (order != null && order.getLabTestDefinition() != null
            && order.getLabTestDefinition().getName() != null
            && !order.getLabTestDefinition().getName().isBlank()) {
            return order.getLabTestDefinition().getName();
        }
        if (order != null && order.getClinicalIndication() != null && !order.getClinicalIndication().isBlank()) {
            return order.getClinicalIndication();
        }
        if (mapped != null && mapped.getLabTestName() != null && !mapped.getLabTestName().isBlank()) {
            return mapped.getLabTestName();
        }
        return "Lab Result";
    }

    /**
     * B18 — two gradings can exist for one row: what the configured
     * reference range says about the value, and the flag the analyzer
     * (OBX-8) or the technologist recorded. Neither is discarded: the
     * patient sees the MORE SEVERE of the two, with the direction taken
     * from whichever source is directional (the range wins when both
     * are, since it is the hospital's own range). So a row inside its
     * range that the analyzer still flagged H reads ABNORMAL_HIGH, and an
     * HH row never reads merely ABNORMAL_HIGH because the range was mild.
     */
    private String resolveStatus(LabResult result, LabResultResponseDTO mapped) {
        if (!result.isReleased()) {
            return STATUS_PENDING;
        }
        String fromRange = statusFromRange(mapped != null ? mapped.getSeverityFlag() : null);
        String fromFlag = statusOf(result.getAbnormalFlag());
        if (fromRange == null) {
            return fromFlag;
        }
        int rangeRank = rank(fromRange);
        int flagRank = rank(fromFlag);
        if (rangeRank != flagRank) {
            return rangeRank > flagRank ? fromRange : fromFlag;
        }
        return isDirectional(fromRange) || !isDirectional(fromFlag) ? fromRange : fromFlag;
    }

    /**
     * What the configured reference range says, or null when there is
     * none to grade against. Out of range is ABNORMAL with a direction,
     * nothing more: CRITICAL comes only from a mapper severity that is
     * itself CRITICAL or from the recorded flag. (An earlier reading
     * upgraded an unacknowledged HIGH to CRITICAL as a review nudge; once
     * merged with the analyzer flag that synthetic CRITICAL outranked an
     * explicit N and reached the patient unreviewed.)
     */
    private static String statusFromRange(String severity) {
        if (severity == null || severity.isBlank() || LabResultMapper.FLAG_UNSPECIFIED.equalsIgnoreCase(severity)) {
            return null;
        }
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case STATUS_CRITICAL -> STATUS_CRITICAL;
            case "HIGH" -> STATUS_ABNORMAL_HIGH;
            case "LOW" -> STATUS_ABNORMAL_LOW;
            default -> STATUS_NORMAL;
        };
    }

    private static int rank(String status) {
        return switch (status) {
            case STATUS_CRITICAL -> 3;
            case STATUS_ABNORMAL_HIGH, STATUS_ABNORMAL_LOW, STATUS_ABNORMAL -> 2;
            default -> 1;
        };
    }

    private static boolean isDirectional(String status) {
        return STATUS_ABNORMAL_HIGH.equals(status) || STATUS_ABNORMAL_LOW.equals(status);
    }

    private static String statusOf(AbnormalFlag flag) {
        if (flag == null) {
            return STATUS_NORMAL;
        }
        return switch (flag) {
            case CRITICAL -> STATUS_CRITICAL;
            case ABNORMAL_HIGH -> STATUS_ABNORMAL_HIGH;
            case ABNORMAL_LOW -> STATUS_ABNORMAL_LOW;
            case ABNORMAL -> STATUS_ABNORMAL;
            case NORMAL -> STATUS_NORMAL;
        };
    }

    private String resolveStaffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        String fullName = staff.getFullName();
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        if (staff.getName() != null && !staff.getName().isBlank()) {
            return staff.getName();
        }
        return Optional.ofNullable(staff.getUser())
            .map(this::resolveUserDisplay)
            .orElse(null);
    }

    private String resolveAssignmentUser(UserRoleHospitalAssignment assignment) {
        if (assignment == null) {
            return null;
        }
        return resolveUserDisplay(assignment.getUser());
    }

    private String resolveUserDisplay(User user) {
        if (user == null) {
            return null;
        }
        String firstName = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String lastName = user.getLastName() != null ? user.getLastName().trim() : "";
        String full = (firstName + " " + lastName).trim();
        if (!full.isBlank()) {
            return full;
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            return user.getEmail();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return null;
    }

    private String formatReferenceRange(List<LabResultReferenceRangeDTO> ranges, String fallbackUnit) {
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        LabResultReferenceRangeDTO range = ranges.get(0);
        Double min = range.getMinValue();
        Double max = range.getMaxValue();
        String unit = range.getUnit();
        if (unit == null || unit.isBlank()) {
            unit = fallbackUnit;
        }
        String unitSuffix = unit != null && !unit.isBlank() ? " " + unit : "";

        if (min != null && max != null) {
            return formatNumber(min) + " - " + formatNumber(max) + unitSuffix;
        }
        if (min != null) {
            return ">= " + formatNumber(min) + unitSuffix;
        }
        if (max != null) {
            return "<= " + formatNumber(max) + unitSuffix;
        }
        return null;
    }

    private String formatNumber(Double value) {
        if (value == null) {
            return null;
        }
        return new java.text.DecimalFormat("0.##").format(value);
    }
}
