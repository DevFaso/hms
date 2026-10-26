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
import java.util.UUID;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
public class LabResultMapper {

    public static final String FLAG_UNSPECIFIED = "UNSPECIFIED";
    public static final String FLAG_CRITICAL = "CRITICAL";
    /** A result no range grades (a qualitative answer) that reads abnormal. */
    public static final String FLAG_ABNORMAL = "ABNORMAL";

    private static final OrderContext EMPTY_ORDER_CONTEXT = new OrderContext(
        "",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Collections.emptyList(),
        null,
        null
    );


    public LabResultResponseDTO toResponseDTO(LabResult result) {
        if (result == null) return null;

        OrderContext context = extractOrderContext(result.getLabOrder());
        List<LabResultReferenceRangeDTO> referenceRanges = toReferenceRangeDtos(context.referenceRanges());
        Grading grading = gradingOf(result.getResultUnit(), context.testUnit(), context.referenceRanges());
        String severityFlag = effectiveSeverityFlag(result, grading.range(), context.expectedResult());

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
            // QA D527: no staff or patient screen reads the patient's e-mail off a result; it is not sent.
            .patientEmail(null)
            .hospitalId(context.hospitalId())
            .hospitalName(context.hospitalName())
            .performingHospitalId(context.performingHospitalId())
            .orderedByName(context.orderedByName())
            .labTestName(context.labTestName() != null || !definitionMissing(result.getLabOrder())
                ? context.labTestName()
                : com.example.hms.utility.MessageUtil.resolveOrRaw(LabOrderMapper.DELETED_TEST_LABEL))
            .labTestDefinitionMissing(definitionMissing(result.getLabOrder()))
            .resultValue(result.getResultValue())
            .resultUnit(result.getResultUnit())
            .resultDate(result.getResultDate())
            .notes(result.getNotes())
            .referenceRanges(referenceRanges)
            .expectedResult(context.expectedResult())
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
        .selfReleased(result.isReleased() && enteredBy(result, result.getReleasedByUserId()))
        .supersedesResultId(result.getSupersedesResultId() != null ? result.getSupersedesResultId().toString() : null)
        .supersededByResultId(result.getSupersededByResultId() != null ? result.getSupersededByResultId().toString() : null)
        .enteredByUserId(enteredByUserIdOf(result) != null ? enteredByUserIdOf(result).toString() : null)
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

    /** D58: the user who entered the result - its assignment's user - or null when unknown. */
    public static UUID enteredByUserIdOf(LabResult result) {
        if (result == null || result.getAssignment() == null || result.getAssignment().getUser() == null) {
            return null;
        }
        return result.getAssignment().getUser().getId();
    }

    /** D58: whether {@code userId} is the user who entered this result. */
    public static boolean enteredBy(LabResult result, UUID userId) {
        UUID author = enteredByUserIdOf(result);
        return author != null && author.equals(userId);
    }

    public LabResultTrendPointDTO toTrendPointDTO(LabResult result) {
        if (result == null) {
            return null;
        }

        OrderContext context = extractOrderContext(result.getLabOrder());
        Grading grading = gradingOf(result.getResultUnit(), context.testUnit(), context.referenceRanges());
        String severityFlag = effectiveSeverityFlag(result, grading.range(), context.expectedResult());

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
     * Whether a result graded {@code severityFlag} raises the critical value
     * alert: only a CRITICAL grade does, that is a value beyond the range's
     * critical limits or an analyzer's CRITICAL flag.
     *
     * <p>QA D313 (D206 follow-up): the D107/D271 fail-safe paged ANY value
     * outside a range that declares no critical limits, so a glucose of 6.5 on
     * 3.9-6.1 paged the doctor as a panic value. Out of range is abnormal (HIGH
     * or LOW), not critical; a laboratory that wants a page sets the limits.
     */
    public boolean raisesCriticalAlert(String severityFlag) {
        return FLAG_CRITICAL.equalsIgnoreCase(severityFlag);
    }

    /**
     * QA D178: the severity the lab-results screen shows for this result
     * (CRITICAL, LOW, HIGH, NORMAL or UNSPECIFIED), so every other screen
     * can grade it the same way instead of guessing.
     */
    public String severityFlagOf(LabResult result) {
        if (result == null) {
            return FLAG_UNSPECIFIED;
        }
        OrderContext context = extractOrderContext(result.getLabOrder());
        LabTestReferenceRange graded = gradingOf(result.getResultUnit(), context.testUnit(), context.referenceRanges()).range();
        return effectiveSeverityFlag(result, graded, context.expectedResult());
    }

    /**
     * QA D206 / D212: the ONE severity every screen shows. It is the range
     * grade, except that an analyzer's CRITICAL flag is labelled CRITICAL
     * (QA D313: a value outside a range without critical limits is not). Never
     * the notification stamp: that describes a page, not the current value.
     * Before this, K 7.9 on such a range was paged as a critical value and
     * shown as "Eleve" with a critical tile of 0.
     */
    private static String effectiveSeverityFlag(LabResult result, LabTestReferenceRange graded,
                                                String expectedResult) {
        String flag = determineSeverityFlag(result.getResultValue(), graded);
        if (FLAG_CRITICAL.equals(flag)) {
            return flag;
        }
        // From the value only: an analyzer's CRITICAL flag is part of the
        // result. The criticalNotifiedAt stamp is NOT - it records a page
        // about a value that may since have been amended (79 -> 4.2), and
        // keying the label on it kept a corrected typo CRITICAL for good.
        if (result.getAbnormalFlag() == com.example.hms.enums.AbnormalFlag.CRITICAL) {
            return FLAG_CRITICAL;
        }
        // QA D648: the definition this value was graded against was deleted
        // (before D601), so no range can grade it any more. The page that went
        // out when it could is the one stored record that it was critical; a
        // released critical value must not drop off the critical list with it.
        if (definitionMissing(result.getLabOrder()) && result.getCriticalNotifiedAt() != null) {
            return FLAG_CRITICAL;
        }
        // QA D834: a qualitative test states the answer it expects
        // ("Négatif"); a result that reads the same is normal. Anything else
        // falls through to the rules below, so a "Positif" stays abnormal and
        // an unexpected wording stays not evaluated rather than guessed.
        if (FLAG_UNSPECIFIED.equals(flag) && !isAbnormalRecorded(result.getAbnormalFlag())
                && result.getAbnormalFlag() != com.example.hms.enums.AbnormalFlag.CRITICAL
                && matchesExpectedResult(result.getResultValue(), expectedResult)) {
            return "NORMAL";
        }
        // QA D313: a value outside a range that declares no critical limits
        // stays HIGH or LOW (abnormal). It used to be promoted to CRITICAL here.
        // QA D286: no range grades a qualitative answer, so the range says
        // UNSPECIFIED ("Non evalue"). The recorded flag, or - for a row stored
        // before the write-time rule existed - the value itself, says abnormal.
        // An explicit NORMAL someone recorded is kept.
        if (FLAG_UNSPECIFIED.equals(flag) && (isAbnormalRecorded(result.getAbnormalFlag())
                || (result.getAbnormalFlag() == null
                    && com.example.hms.service.LabResultValueRules.isPositiveQualitative(result.getResultValue())))) {
            return FLAG_ABNORMAL;
        }
        return flag;
    }

    /**
     * QA D834: whether a qualitative answer is the test's expected one,
     * ignoring case, accents, surrounding spaces and a trailing sign or
     * punctuation ("négatif", "NEGATIF", "Négatif (-)"). A numeric value is
     * never a qualitative answer.
     */
    static boolean matchesExpectedResult(String value, String expectedResult) {
        if (value == null || value.isBlank() || expectedResult == null || expectedResult.isBlank()) {
            return false;
        }
        String folded = foldAnswer(value);
        if (folded.isEmpty() || NUMERIC_ANSWER.matcher(folded).matches()) {
            return false;
        }
        return folded.equals(foldAnswer(expectedResult));
    }

    private static final java.util.regex.Pattern NUMERIC_ANSWER =
        java.util.regex.Pattern.compile("[+-]?\\d+([.,]\\d+)?");
    private static final java.util.regex.Pattern COMBINING_MARKS = java.util.regex.Pattern.compile("\\p{M}");
    /** Trailing notation that is not the answer: a sign, bare or in brackets, and end punctuation. */
    private static final String TRAILING_NOTATION = "()+-.;:! \t";

    private static String foldAnswer(String text) {
        String folded = COMBINING_MARKS.matcher(
                java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFD))
            .replaceAll("")
            .toUpperCase(java.util.Locale.ROOT);
        int end = folded.length();
        while (end > 0 && TRAILING_NOTATION.indexOf(folded.charAt(end - 1)) >= 0) {
            end--;
        }
        return String.join(" ", folded.substring(0, end).trim().split("\\s+"));
    }

    private static boolean isAbnormalRecorded(com.example.hms.enums.AbnormalFlag recorded) {
        return recorded != null && recorded.severity() == com.example.hms.enums.AbnormalFlag.ABNORMAL;
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
     * field: the HL7 parser stores only OBX-6's identifier, decoded, and a
     * text OBX-5 decoded too. (No row stored a coded unit or an escaped value
     * before that, in any environment, as of 2026-10-06.)
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

    /**
     * QA D648: the order points at a definition that no longer exists. Only an
     * initialised order can say so; a lazy one is treated as unknown.
     */
    private static boolean definitionMissing(LabOrder order) {
        return order != null && Hibernate.isInitialized(order) && order.getLabTestDefinition() == null;
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
        String labOrderCode = LabOrderMapper.orderCodeOf(order);
        String orderedByName = resolveOrderingStaffName(order);

        return new OrderContext(
            patientInfo.fullName(),
            hospitalId,
            hospitalName,
            performingHospitalId,
            labTestMetadata.name(),
            labTestMetadata.testCode(),
            labOrderCode,
            orderedByName,
            labTestMetadata.referenceRanges(),
            labTestMetadata.unit(),
            labTestMetadata.expectedResult()
        );
    }

    private record OrderContext(
            String patientFullName,
            String hospitalId,
            String hospitalName,
            String performingHospitalId,
            String labTestName,
            String labTestCode,
            String labOrderCode,
            String orderedByName,
            List<LabTestReferenceRange> referenceRanges,
            /** The test definition's own unit: what a range with no unit of its own is stated in. */
            String testUnit,
            /** QA D834: the expected answer of a qualitative test. */
            String expectedResult
    ) {}

    private record PatientInfo(String fullName) {}

    private record LabTestMetadata(String name, String testCode, List<LabTestReferenceRange> referenceRanges,
                                   String unit, String expectedResult) {}

    private PatientInfo resolvePatientInfo(LabOrder order) {
        Patient patient = order.getPatient();
        if (patient == null || !Hibernate.isInitialized(patient)) {
            return new PatientInfo("");
        }
        String fullName = Optional.ofNullable(patient.getFullName())
            .filter(Predicate.not(String::isBlank))
            .orElse(null);
        if (fullName == null) {
            fullName = (nullToEmpty(patient.getFirstName()) + " " + nullToEmpty(patient.getLastName())).trim();
        }
        return new PatientInfo(fullName);
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
            return new LabTestMetadata(null, null, Collections.emptyList(), null, null);
        }
        LabTestDefinition definition = order.getLabTestDefinition();
        List<LabTestReferenceRange> referenceRanges = definition.getReferenceRanges() != null
            ? definition.getReferenceRanges()
            : Collections.emptyList();
        return new LabTestMetadata(definition.getName(), definition.getTestCode(), referenceRanges,
            definition.getUnit(), definition.getExpectedResult());
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
            .criticalLow(range.getCriticalLow())
            .criticalHigh(range.getCriticalHigh())
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

        // D107: the panic limits come first, so a value beyond them is
        // labelled CRITICAL - the same word the alert uses.
        if (matchingRange.getCriticalLow() != null && value < matchingRange.getCriticalLow()) {
            return FLAG_CRITICAL;
        }
        if (matchingRange.getCriticalHigh() != null && value > matchingRange.getCriticalHigh()) {
            return FLAG_CRITICAL;
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
