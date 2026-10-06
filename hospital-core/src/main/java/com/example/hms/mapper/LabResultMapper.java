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
        Collections.emptyList()
    );


    public LabResultResponseDTO toResponseDTO(LabResult result) {
        if (result == null) return null;

        OrderContext context = extractOrderContext(result.getLabOrder());
        List<LabResultReferenceRangeDTO> referenceRanges = toReferenceRangeDtos(context.referenceRanges());
        Grading grading = gradingOf(result.getResultUnit(), context.referenceRanges());
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
        Grading grading = gradingOf(result.getResultUnit(), context.referenceRanges());
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
     * <p><b>A range in another unit is never used.</b> When no configured
     * range is in the result's unit (and the fallback range states a unit of
     * its own), the result is NOT graded: no range is returned here, the
     * severity flag is {@link #FLAG_UNSPECIFIED}, and
     * {@link #isUngradedForUnitMismatch} is true so the result is never
     * auto-released and staff see "not graded: units differ". A range with no
     * unit of its own is still used (bare limits, no unit claimed), and so is
     * any range for a result that states no unit.
     */
    public LabResultReferenceRangeDTO gradedReferenceRange(LabResult result) {
        if (result == null) {
            return null;
        }
        OrderContext context = extractOrderContext(result.getLabOrder());
        LabTestReferenceRange graded = gradingOf(result.getResultUnit(), context.referenceRanges()).range();
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
        return gradingOf(result.getResultUnit(), context.referenceRanges()).unitMismatch();
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
     * The one unit-matching rule. A range in the result's unit wins; with none,
     * the first configured range is used only when it states no unit of its
     * own (or the result states none) — a range in another unit grades
     * nothing.
     */
    private static Grading gradingOf(String resultUnit, List<LabTestReferenceRange> referenceRanges) {
        LabTestReferenceRange candidate = findMatchingRange(resultUnit, referenceRanges);
        if (candidate == null) {
            return NOT_GRADED;
        }
        return isInAnotherUnit(candidate, resultUnit) ? UNITS_DIFFER : new Grading(candidate, false);
    }

    /** True when both units are stated and they differ. */
    private static boolean isInAnotherUnit(LabTestReferenceRange range, String resultUnit) {
        String rangeUnit = range.getUnit();
        if (rangeUnit == null || rangeUnit.isBlank() || resultUnit == null || resultUnit.isBlank()) {
            return false;
        }
        return !rangeUnit.trim().equalsIgnoreCase(resultUnit.trim());
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
            labTestMetadata.referenceRanges()
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
            List<LabTestReferenceRange> referenceRanges
    ) {}

    private record PatientInfo(String fullName, String email) {}

    private record LabTestMetadata(String name, String testCode, List<LabTestReferenceRange> referenceRanges) {}

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
            return new LabTestMetadata(null, null, Collections.emptyList());
        }
        LabTestDefinition definition = order.getLabTestDefinition();
        List<LabTestReferenceRange> referenceRanges = definition.getReferenceRanges() != null
            ? definition.getReferenceRanges()
            : Collections.emptyList();
        return new LabTestMetadata(definition.getName(), definition.getTestCode(), referenceRanges);
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

    private static LabTestReferenceRange findMatchingRange(String resultUnit, List<LabTestReferenceRange> referenceRanges) {
        if (referenceRanges == null || referenceRanges.isEmpty()) {
            return null;
        }
        if (resultUnit == null || resultUnit.isBlank()) {
            return referenceRanges.get(0);
        }
        String normalizedUnit = resultUnit.trim().toLowerCase(Locale.ROOT);
        return referenceRanges.stream()
            .filter(range -> range != null && range.getUnit() != null
                && range.getUnit().trim().equalsIgnoreCase(normalizedUnit))
            .findFirst()
            .orElse(referenceRanges.get(0));
    }
}
