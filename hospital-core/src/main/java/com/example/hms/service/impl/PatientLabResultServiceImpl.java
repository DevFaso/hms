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
import java.util.Map;
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

    /**
     * Resolvable message key, not a sentence, and deliberately the same one
     * {@code PatientChartAccess} throws — a refused staff read must be
     * indistinguishable from "no such patient".
     */
    private static final String MSG_PATIENT_NOT_FOUND = "patient.notFound";

    /** The nil UUID: names no hospital, for an IN list that must not be empty. */
    private static final UUID NO_HOSPITAL = new UUID(0L, 0L);

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
     * @param portalView true on the patient-facing path — the patient (or an
     *        authorized proxy) reading their own results, resolved from the
     *        authenticated principal by {@code PatientPortalServiceImpl}. It
     *        governs two things.
     *
     *        <p>B3 — redaction: an
     *        unreleased row keeps its identity (test, order, hospital) and
     *        {@code PENDING} but loses the value, unit, reference range,
     *        notes and performer. A preliminary value is the lab's until
     *        it is released; the patient learns only that one is coming.
     *
     *        <p>And scope: a {@code null} {@code hospitalId} is legitimate
     *        ONLY here. The portal has no hospital scope to offer and the
     *        patient owns every row, so the patient-only query is the right
     *        one. On the staff path a null scope means the scope did not
     *        resolve, and {@link #fetchRows} refuses rather than widening —
     *        see the comment there.
     */
    private List<PatientLabResultResponseDTO> getLabResults(UUID patientId, UUID hospitalId, int limit,
                                                            boolean portalView) {
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

        List<LabResult> results = fetchRows(patient, hospitalId, effectiveLimit, portalView);
        List<LabResult> visible = resolvePairs(results, portalView, effectiveLimit);
        // Only worth reading again if there are rows we have not seen AND the
        // wider read would actually be wider — at the cap it would repeat the
        // identical query for nothing.
        if (visible.size() < effectiveLimit
            && results.size() >= effectiveLimit
            && effectiveLimit < MAX_LIMIT) {
            results = fetchRows(patient, hospitalId, MAX_LIMIT, portalView);
            visible = resolvePairs(results, portalView, effectiveLimit);
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
            .map(result -> toResponse(result, portalView))
            .toList();
    }

    /**
     * The newest {@code window} rows for this patient, within the readable
     * hospitals when a hospital scope is in play.
     *
     * <p>With no hospital scope there are exactly two cases and they are not
     * the same: the portal, where the patient owns every row and the
     * patient-only query is correct, and a staff read whose scope failed to
     * resolve, which is refused.
     */
    private List<LabResult> fetchRows(Patient patient, UUID hospitalId, int window, boolean portalView) {
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
        } else if (!portalView) {
            // A staff read with no hospital scope. This used to fall through to
            // the patient-only query below, which returns EVERY hospital's rows
            // for the patient: RecordAccessPolicy.readableHospitalIds never ran,
            // and getLabResults' cross-hospital disclosure row is guarded on the
            // same null, so the widened read was not even accounted.
            //
            // PatientChartAccess.require already denies a null scope for anyone
            // but a super-admin, so what reached here was a super-admin in
            // global view — including one acting as an inherited clinical role,
            // since RoleExpansion grants that list before any @PreAuthorize
            // runs. There is no scope for such a caller to be measured against,
            // so widening is the only thing the old code could do. It refuses
            // instead: pick a hospital (the scope picker) and the read is
            // scoped, readable-checked and disclosed like every other.
            //
            // The chart's Labs tab used to send NO hospitalId in global view,
            // which would have turned this refusal into an error card with a
            // Retry that re-issued the same request forever. #731 closed that
            // first: the Labs section now declines to read without a scope and
            // renders the scope hint, so the two changes meet correctly and a
            // super-admin is asked to pick a hospital rather than shown a
            // failure. If a future caller reintroduces an unscoped read, it
            // gets a 404 here — deliberately, because an unaccounted
            // cross-tenant read is not an acceptable way to keep a tab
            // populated.
            //
            // 404, not 403, and the same key PatientChartAccess throws: a
            // caller who could not establish scope learns nothing about whether
            // the patient or the rows exist.
            //
            // Logged, though. The response is deliberately opaque, which makes
            // a scope-resolution failure indistinguishable from a genuine
            // missing patient in the logs too — and those are very different
            // operational events. The patient id only: it is already the
            // subject of this request, and nothing about the caller's own
            // tenancy belongs in a line that a 404 spike will be triaged from.
            log.warn("Staff lab-result read refused: no hospital scope resolved for patient {}",
                patient.getId());
            throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patient.getId());
        } else {
            // Patient portal only: the caller IS the patient (or a proxy the
            // portal already authorized), the portal has no hospital scope to
            // offer, and every row belongs to them. Every hospital's rows, read
            // through the readable query's globalView flag (there is no
            // patient-only finder left), newest first and limited at the
            // database: this used to load the patient's whole result history,
            // fully hydrated, to sort it and keep the window. The nil
            // UUID names no hospital (PostgreSQL rejects an empty IN list).
            // resultDate is NOT NULL, so the in-memory nulls-last ordering this
            // replaced has nothing to reorder.
            results = labResultRepository.findPatientResultsReadableAt(patient.getId(), Set.of(NO_HOSPITAL), null,
                true, pageable);
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
        if (!redactUnreleased) {
            return results.stream().limit(limit).toList();
        }
        List<LabResult> known = withSiblingsOfSupersedableRows(results);
        // Judge everything we know, not just the page: a winner that is itself
        // superseded must be recognisable as such before it is folded in.
        Map<UUID, LabResult> replacements = SupersededLabResults.replacements(known, known);
        if (replacements.isEmpty()) {
            return results.stream().limit(limit).toList();
        }

        List<LabResult> survivors = new java.util.ArrayList<>(results.size());
        Set<UUID> present = new java.util.HashSet<>();
        for (LabResult result : results) {
            if (!replacements.containsKey(result.getId()) && present.add(result.getId())) {
                survivors.add(result);
            }
        }
        // Only the rows on this page can pull a survivor in with them; a
        // sibling fetched purely to judge them is not something the patient
        // asked for.
        List<LabResult> pageWinners = results.stream()
            .map(result -> replacements.get(result.getId()))
            .filter(java.util.Objects::nonNull)
            .toList();
        // Removing a superseded row is only half of it: the row that replaced
        // it may be off the page — an observation time shared by the pair puts
        // them adjacent, so a tie can fall either side of the edge — and
        // dropping one without adding the other would take the test out of the
        // patient's view altogether.
        for (LabResult winner : pageWinners) {
            if (winner.getId() != null
                && !replacements.containsKey(winner.getId())
                && present.add(winner.getId())) {
                survivors.add(winner);
            }
        }
        survivors.sort(NEWEST_FIRST);
        return survivors.stream().limit(limit).toList();
    }

    /** The order the page is read in, applied again once a survivor is folded in. */
    private static final java.util.Comparator<LabResult> NEWEST_FIRST =
        java.util.Comparator.comparing(LabResult::getResultDate,
            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));

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
