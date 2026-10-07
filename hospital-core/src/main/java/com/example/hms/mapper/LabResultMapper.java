package com.example.hms.mapper;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabResultTrendPointDTO;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
public class LabResultMapper {

    public static final String FLAG_UNSPECIFIED = "UNSPECIFIED";

    private static final OrderContext EMPTY_ORDER_CONTEXT = new OrderContext(
        "",
        "",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Collections.emptyList(),
        null
    );


    public LabResultResponseDTO toResponseDTO(LabResult result) {
        if (result == null) return null;

        OrderContext context = extractOrderContext(result.getLabOrder());
        List<LabResultReferenceRangeDTO> referenceRanges = toReferenceRangeDtos(context.referenceRanges());
        Grading grading = gradingOf(result.getResultUnit(), context.testUnit(), context.referenceRanges());
        String severityFlag = determineSeverityFlag(result.getResultValue(), grading.range());

        return LabResultResponseDTO.builder()
            .id(result.getId() != null ? result.getId().toString() : null)
            .labOrderId(result.getLabOrder() != null && result.getLabOrder().getId() != null
                ? result.getLabOrder().getId().toString() : null)
            .labOrderCode(context.labOrderCode())
            .labTestCode(context.labTestCode())
            .patientId(result.getLabOrder() != null && result.getLabOrder().getPatient() != null
                && result.getLabOrder().getPatient().getId() != null
                ? result.getLabOrder().getPatient().getId().toString() : null)
            .patientFullName(context.patientFullName())
            .patientEmail(context.patientEmail())
            .hospitalId(context.hospitalId())
            .hospitalName(context.hospitalName())
            .performingHospitalId(context.performingHospitalId())
            .orderedByName(context.orderedByName())
            .labTestName(context.labTestName())
            .resultValue(result.getResultValue())
            .resultUnit(result.getResultUnit())
            .resultDate(result.getResultDate())
            .notes(result.getNotes())
            .referenceRanges(referenceRanges)
            .severityFlag(severityFlag)
            .unitMismatch(grading.unitMismatch())
            .acknowledged(result.isAcknowledged())
            .criticalNotifiedAt(result.getCriticalNotifiedAt())
            .criticalEscalatedAt(result.getCriticalEscalatedAt())
            .criticalEscalationLevel(result.getCriticalEscalationLevel())
            .criticalReadBackAt(result.getCriticalReadBackAt())
            .criticalReadBackBy(result.getCriticalReadBackByDisplay())
            // The recorded read-back, including a mismatched one — a clinician
            // repeating the wrong value is the event the audit trail is for.
            .criticalReadBackValue(result.getCriticalReadBackValue())
            .acknowledgedAt(result.getAcknowledgedAt())
            .acknowledgedBy(result.getAcknowledgedByDisplay())
        .released(result.isReleased())
        .releasedAt(result.getReleasedAt())
        .releasedByFullName(result.getReleasedByDisplay())
        .signedAt(result.getSignedAt())
        .signedBy(result.getSignedByDisplay())
        .signatureValue(result.getSignatureValue())
        .signatureNotes(result.getSignatureNotes())
            .createdAt(result.getCreatedAt())
            .updatedAt(result.getUpdatedAt())
            .sourceMessageControlId(result.getSourceMessageControlId())
            .observationResultStatus(result.getObservationResultStatus())
            .build();
    }

    public LabResultTrendPointDTO toTrendPointDTO(LabResult result) {
        if (result == null) {
            return null;
        }

        OrderContext context = extractOrderContext(result.getLabOrder());
        Grading grading = gradingOf(result.getResultUnit(), context.testUnit(), context.referenceRanges());
        String severityFlag = determineSeverityFlag(result.getResultValue(), grading.range());

        return LabResultTrendPointDTO.builder()
            .labResultId(result.getId() != null ? result.getId().toString() : null)
            .labOrderCode(context.labOrderCode())
            .resultDate(result.getResultDate())
            .resultValue(result.getResultValue())
            .resultUnit(result.getResultUnit())
            .severityFlag(severityFlag)
            .build();
    }

    /**
     * The reference range {@link #toResponseDTO} graded this result against
     * (its {@code severityFlag}), or null when there was none to grade
     * against. A reader showing "the" range beside the value must show THIS
     * one: on a test configured with a range per unit, the first configured
     * range can be in a unit the value was never expressed in, and a patient
     * reading 5.4 mmol/L beside 70-110 mg/dL draws the wrong conclusion.
     *
     * <p>One selection, {@link #gradingOf}, serves the grading, the critical
     * alerting that reads it, the release gate and this, so they cannot drift.
     *
     * <p><b>A range in another unit is never used.</b> A range with no unit
     * of its own is stated in the test definition's unit. When no range is in
     * the result's unit and none has a unit at all (neither its own nor the
     * test's), the result is NOT graded: no range is returned here, the
     * severity flag is {@link #FLAG_UNSPECIFIED}, and
     * {@link #isUngradedForUnitMismatch} is true so the result is never
     * auto-released and staff see "not graded: units differ". A range with no
     * unit anywhere is still used (bare limits, no unit claimed), and so is
     * any range for a result that states no unit.
     */
    public LabResultReferenceRangeDTO gradedReferenceRange(LabResult result) {
        if (result == null) {
            return null;
        }
        OrderContext context = extractOrderContext(result.getLabOrder());
        LabTestReferenceRange graded = gradingOf(result.getResultUnit(), context.testUnit(), context.referenceRanges()).range();
        return graded != null ? toReferenceRangeDto(graded) : null;
    }

    /**
     * True when the test has reference ranges configured but none of them can
     * grade this result because they are all stated in another unit. Such a
     * result carries no range-derived severity, raises no range-derived
     * critical alert, and is never released without a person reviewing it.
     * An analyser's own OBX-8 flag on the row is a separate signal and is
     * not affected.
     *
     * <p>Reads the order's test definition only if it is already loaded; a
     * caller holding a lazy order initialises it first.
     */
    public boolean isUngradedForUnitMismatch(LabResult result) {
        if (result == null) {
            return false;
        }
        OrderContext context = extractOrderContext(result.getLabOrder());
        return gradingOf(result.getResultUnit(), context.testUnit(), context.referenceRanges()).unitMismatch();
    }

    /**
     * The range a result is graded against, or why there is none.
     * {@code unitMismatch} is true only when ranges exist and every candidate
     * is stated in a unit other than the result's.
     */
    private record Grading(LabTestReferenceRange range, boolean unitMismatch) {}

    private static final Grading NOT_GRADED = new Grading(null, false);
    private static final Grading UNITS_DIFFER = new Grading(null, true);

    /**
     * The one unit-matching rule, in this order, independent of the order the
     * ranges were configured in. A range's EFFECTIVE unit is its own unit, or,
     * when it states none, the test definition's unit: an administrator who
     * leaves a range's unit empty means the test's unit, and a glucose range
     * of 70-110 on a mg/dL test must not grade 5.4 mmol/L. A result that
     * states no unit is likewise in the test's unit. Units compare trimmed and
     * case-insensitively; nothing is converted.
     * <ol>
     *   <li>the first range whose effective unit is the result's effective unit;</li>
     *   <li>else the first range with no effective unit at all (neither the
     *       range nor the test states one: bare limits);</li>
     *   <li>else, when neither the result nor the test states a unit, the
     *       first range;</li>
     *   <li>else nothing grades it: every range is in another unit.</li>
     * </ol>
     */
    private static Grading gradingOf(String resultUnit, String testUnit,
                                     List<LabTestReferenceRange> referenceRanges) {
        if (referenceRanges == null || referenceRanges.isEmpty()) {
            return NOT_GRADED;
        }
        // A result that states no unit is in the test's unit, like a range.
        String result = normalisedUnit(resultUnit);
        if (result == null) {
            result = normalisedUnit(testUnit);
        }
        LabTestReferenceRange chosen = result != null ? firstInUnit(referenceRanges, result, testUnit) : null;
        if (chosen == null) {
            chosen = firstWithoutUnit(referenceRanges, testUnit);
        }
        if (chosen != null) {
            return new Grading(chosen, false);
        }
        if (result == null) {
            return firstRange(referenceRanges);
        }
        return UNITS_DIFFER;
    }

    /** Step 1: the first range whose effective unit is {@code unit} (already normalised). */
    private static LabTestReferenceRange firstInUnit(List<LabTestReferenceRange> ranges, String unit,
                                                     String testUnit) {
        return ranges.stream()
            .filter(range -> range != null && unit.equals(effectiveUnit(range, testUnit)))
            .findFirst()
            .orElse(null);
    }

    /** Step 2: the first range with no effective unit at all (bare limits). */
    private static LabTestReferenceRange firstWithoutUnit(List<LabTestReferenceRange> ranges, String testUnit) {
        return ranges.stream()
            .filter(range -> range != null && effectiveUnit(range, testUnit) == null)
            .findFirst()
            .orElse(null);
    }

    /** Step 3, for a result that states no unit: the first configured range. */
    private static Grading firstRange(List<LabTestReferenceRange> ranges) {
        LabTestReferenceRange first = ranges.get(0);
        return first != null ? new Grading(first, false) : NOT_GRADED;
    }

    /** The range's own unit, else the test's, normalised; null when neither states one. */
    private static String effectiveUnit(LabTestReferenceRange range, String testUnit) {
        String own = normalisedUnit(range.getUnit());
        return own != null ? own : normalisedUnit(testUnit);
    }

    /**
     * The unit as compared: trimmed and lower-cased, null when blank. Nothing
     * is split or converted here - {@code 10^9/L} is a unit, not a coded
     * field. Rows written by the HL7 parser store only OBX-6's identifier;
     * a row stored before that (coded, {@code mmol/L^...^UCUM}) is compared
     * as written.
     */
    private static String normalisedUnit(String unit) {
        return unit == null || unit.isBlank() ? null : unit.trim().toLowerCase(Locale.ROOT);
    }

    public LabResult toEntity(LabResultRequestDTO dto, LabOrder labOrder, UserRoleHospitalAssignment assignment) {
        if (dto == null) return null;

        return LabResult.builder()
                .labOrder(labOrder)
                .testCode(dto.getTestCode())
                .sourceSendingApplication(dto.getSourceSendingApplication())
                .sourceSendingFacility(dto.getSourceSendingFacility())
                .sourceMessageControlId(dto.getSourceMessageControlId())
                .resultValue(dto.getResultValue())
                .resultUnit(dto.getResultUnit())
                .resultDate(dto.getResultDate())
                .notes(dto.getNotes())
                .assignment(assignment)
                .build();
    }

    private OrderContext extractOrderContext(LabOrder order) {
        if (order == null || !Hibernate.isInitialized(order)) {
            return EMPTY_ORDER_CONTEXT;
        }

        PatientInfo patientInfo = resolvePatientInfo(order);
        String hospitalId = resolveHospitalId(order);
        String hospitalName = resolveHospitalName(order);
        String performingHospitalId = resolvePerformingHospitalId(order);
        LabTestMetadata labTestMetadata = resolveLabTestMetadata(order);
        String labOrderCode = order.getId() != null ? order.getId().toString() : null;
        String orderedByName = resolveOrderingStaffName(order);

        return new OrderContext(
            patientInfo.fullName(),
            patientInfo.email(),
            hospitalId,
            hospitalName,
            performingHospitalId,
            labTestMetadata.name(),
            labTestMetadata.testCode(),
            labOrderCode,
            orderedByName,
            labTestMetadata.referenceRanges(),
            labTestMetadata.unit()
        );
    }

    private record OrderContext(
            String patientFullName,
            String patientEmail,
            String hospitalId,
            String hospitalName,
            String performingHospitalId,
            String labTestName,
            String labTestCode,
            String labOrderCode,
            String orderedByName,
            List<LabTestReferenceRange> referenceRanges,
            /** The test definition's own unit: what a range with no unit of its own is stated in. */
            String testUnit
    ) {}

    private record PatientInfo(String fullName, String email) {}

    private record LabTestMetadata(String name, String testCode, List<LabTestReferenceRange> referenceRanges,
                                   String unit) {}

    private PatientInfo resolvePatientInfo(LabOrder order) {
        Patient patient = order.getPatient();
        if (patient == null || !Hibernate.isInitialized(patient)) {
            return new PatientInfo("", "");
        }
        String fullName = Optional.ofNullable(patient.getFullName())
            .filter(Predicate.not(String::isBlank))
            .orElse(null);
        if (fullName == null) {
            fullName = (nullToEmpty(patient.getFirstName()) + " " + nullToEmpty(patient.getLastName())).trim();
        }
        String email = Optional.ofNullable(patient.getEmail()).orElse("");
        return new PatientInfo(fullName, email);
    }

    private String resolveHospitalId(LabOrder order) {
        if (order.getHospital() == null || !Hibernate.isInitialized(order.getHospital())) {
            return null;
        }
        return order.getHospital().getId() != null ? order.getHospital().getId().toString() : null;
    }

    /**
     * B1: the laboratory the order was routed to, null when the ordering
     * hospital ran it.
     *
     * <p>{@code performingHospital} is a lazy many-to-one that no entity
     * graph fetches, so the {@code Hibernate.isInitialized} guard the sibling
     * resolvers use was false on every real response and this field came back
     * null everywhere — which made the release-button fix it exists for
     * inert. But {@code proxy.getId()} is not the free read it looks like
     * either: {@code BaseEntity} puts {@code @Id} on the FIELD with no
     * {@code @Access(PROPERTY)} override, so Hibernate uses field access, has
     * no identifier getter to intercept, and the call initialises the proxy —
     * a SELECT per outsourced row on {@code GET /lab-results}, and a
     * {@code LazyInitializationException} on any path that maps a detached
     * result.
     *
     * <p>So the identifier is taken from the proxy's own initializer, which
     * holds it by definition: no query, no initialisation, correct while
     * detached, and no dependence on which finder loaded the row. An
     * already-initialised association is read directly.
     */
    private String resolvePerformingHospitalId(LabOrder order) {
        java.util.UUID id = com.example.hms.persistence.JpaProxyUtils.idOf(order.getPerformingHospital());
        return id != null ? id.toString() : null;
    }

    private String resolveHospitalName(LabOrder order) {
        if (order.getHospital() == null || !Hibernate.isInitialized(order.getHospital())) {
            return null;
        }
        return order.getHospital().getName();
    }

    private String resolveOrderingStaffName(LabOrder order) {
        if (order.getOrderingStaff() == null || !Hibernate.isInitialized(order.getOrderingStaff())) {
            return null;
        }
        Staff staff = order.getOrderingStaff();
        if (staff.getName() != null && !staff.getName().isBlank()) {
            return staff.getName().trim();
        }
        if (staff.getUser() != null && Hibernate.isInitialized(staff.getUser())) {
            String first = nullToEmpty(staff.getUser().getFirstName());
            String last = nullToEmpty(staff.getUser().getLastName());
            String combined = (first + " " + last).trim();
            if (!combined.isEmpty()) {
                return combined;
            }
        }
        return null;
    }

    private LabTestMetadata resolveLabTestMetadata(LabOrder order) {
        if (order.getLabTestDefinition() == null || !Hibernate.isInitialized(order.getLabTestDefinition())) {
            return new LabTestMetadata(null, null, Collections.emptyList(), null);
        }
        LabTestDefinition definition = order.getLabTestDefinition();
        List<LabTestReferenceRange> referenceRanges = definition.getReferenceRanges() != null
            ? definition.getReferenceRanges()
            : Collections.emptyList();
        return new LabTestMetadata(definition.getName(), definition.getTestCode(), referenceRanges,
            definition.getUnit());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private List<LabResultReferenceRangeDTO> toReferenceRangeDtos(List<LabTestReferenceRange> referenceRanges) {
        if (referenceRanges == null || referenceRanges.isEmpty()) {
            return Collections.emptyList();
        }
        return referenceRanges.stream()
            .filter(Objects::nonNull)
            .map(LabResultMapper::toReferenceRangeDto)
            .toList();
    }

    private static LabResultReferenceRangeDTO toReferenceRangeDto(LabTestReferenceRange range) {
        return LabResultReferenceRangeDTO.builder()
            .minValue(range.getMinValue())
            .maxValue(range.getMaxValue())
            .unit(range.getUnit())
            .ageMin(range.getAgeMin())
            .ageMax(range.getAgeMax())
            .gender(range.getGender())
            .notes(range.getNotes())
            .build();
    }

    /**
     * LOW / HIGH / NORMAL against {@code matchingRange}, the range
     * {@link #gradingOf} chose; UNSPECIFIED when there is none (no range
     * configured, or only ranges in another unit) or the value is not a
     * number.
     */
    private static String determineSeverityFlag(String rawResultValue, LabTestReferenceRange matchingRange) {
        if (rawResultValue == null || rawResultValue.isBlank()) {
            return FLAG_UNSPECIFIED;
        }
        if (matchingRange == null) {
            return FLAG_UNSPECIFIED;
        }

        double value;
        try {
            value = Double.parseDouble(rawResultValue);
        } catch (NumberFormatException ex) {
            return FLAG_UNSPECIFIED;
        }

        Double min = matchingRange.getMinValue();
        Double max = matchingRange.getMaxValue();
        if (min != null && value < min) {
            return "LOW";
        }
        if (max != null && value > max) {
            return "HIGH";
        }
        return "NORMAL";
    }
}
