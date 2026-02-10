package com.example.hms.mapper;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Patient;
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
import java.util.stream.Collectors;

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
        Collections.emptyList()
    );


    public LabResultResponseDTO toResponseDTO(LabResult result) {
        if (result == null) return null;

        OrderContext context = extractOrderContext(result.getLabOrder());
        List<LabResultReferenceRangeDTO> referenceRanges = toReferenceRangeDtos(context.referenceRanges());
        String severityFlag = determineSeverityFlag(result.getResultValue(), result.getResultUnit(), context.referenceRanges());

        return LabResultResponseDTO.builder()
            .id(result.getId() != null ? result.getId().toString() : null)
            .labOrderCode(context.labOrderCode())
            .patientFullName(context.patientFullName())
            .patientEmail(context.patientEmail())
            .hospitalName(context.hospitalName())
            .labTestName(context.labTestName())
            .resultValue(result.getResultValue())
            .resultUnit(result.getResultUnit())
            .resultDate(result.getResultDate())
            .notes(result.getNotes())
            .referenceRanges(referenceRanges)
            .severityFlag(severityFlag)
            .acknowledged(result.isAcknowledged())
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
            .build();
    }

    public LabResultTrendPointDTO toTrendPointDTO(LabResult result) {
        if (result == null) {
            return null;
        }

        OrderContext context = extractOrderContext(result.getLabOrder());
        String severityFlag = determineSeverityFlag(result.getResultValue(), result.getResultUnit(), context.referenceRanges());

        return LabResultTrendPointDTO.builder()
            .labResultId(result.getId() != null ? result.getId().toString() : null)
            .labOrderCode(context.labOrderCode())
            .resultDate(result.getResultDate())
            .resultValue(result.getResultValue())
            .resultUnit(result.getResultUnit())
            .severityFlag(severityFlag)
            .build();
    }

    public LabResult toEntity(LabResultRequestDTO dto, LabOrder labOrder, UserRoleHospitalAssignment assignment) {
        if (dto == null) return null;

        return LabResult.builder()
                .labOrder(labOrder)
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
        String hospitalName = resolveHospitalName(order);
        LabTestMetadata labTestMetadata = resolveLabTestMetadata(order);
        String labOrderCode = order.getId() != null ? order.getId().toString() : null;

        return new OrderContext(
            patientInfo.fullName(),
            patientInfo.email(),
            hospitalName,
            labTestMetadata.name(),
            labOrderCode,
            labTestMetadata.referenceRanges()
        );
    }

    private record OrderContext(
            String patientFullName,
            String patientEmail,
            String hospitalName,
            String labTestName,
            String labOrderCode,
            List<LabTestReferenceRange> referenceRanges
    ) {}

    private record PatientInfo(String fullName, String email) {}

    private record LabTestMetadata(String name, List<LabTestReferenceRange> referenceRanges) {}

    private PatientInfo resolvePatientInfo(LabOrder order) {
        Patient patient = order.getPatient();
        if (patient == null || !Hibernate.isInitialized(patient)) {
            return new PatientInfo("", "");
        }
        String fullName = Optional.ofNullable(patient.getFullName())
            .filter(name -> !name.isBlank())
            .orElseGet(() -> (nullToEmpty(patient.getFirstName()) + " " + nullToEmpty(patient.getLastName())).trim());
        String email = Optional.ofNullable(patient.getEmail()).orElse("");
        return new PatientInfo(fullName, email);
    }

    private String resolveHospitalName(LabOrder order) {
        if (order.getHospital() == null || !Hibernate.isInitialized(order.getHospital())) {
            return null;
        }
        return order.getHospital().getName();
    }

    private LabTestMetadata resolveLabTestMetadata(LabOrder order) {
        if (order.getLabTestDefinition() == null || !Hibernate.isInitialized(order.getLabTestDefinition())) {
            return new LabTestMetadata(null, Collections.emptyList());
        }
        LabTestDefinition definition = order.getLabTestDefinition();
        List<LabTestReferenceRange> referenceRanges = definition.getReferenceRanges() != null
            ? definition.getReferenceRanges()
            : Collections.emptyList();
        return new LabTestMetadata(definition.getName(), referenceRanges);
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
            .map(range -> LabResultReferenceRangeDTO.builder()
                .minValue(range.getMinValue())
                .maxValue(range.getMaxValue())
                .unit(range.getUnit())
                .ageMin(range.getAgeMin())
                .ageMax(range.getAgeMax())
                .gender(range.getGender())
                .notes(range.getNotes())
                .build())
            .collect(Collectors.toList());
    }

    private String determineSeverityFlag(String rawResultValue, String resultUnit, List<LabTestReferenceRange> referenceRanges) {
        if (rawResultValue == null || rawResultValue.isBlank()) {
            return FLAG_UNSPECIFIED;
        }
        if (referenceRanges == null || referenceRanges.isEmpty()) {
            return FLAG_UNSPECIFIED;
        }

        double value;
        try {
            value = Double.parseDouble(rawResultValue);
        } catch (NumberFormatException ex) {
            return FLAG_UNSPECIFIED;
        }

        LabTestReferenceRange matchingRange = findMatchingRange(resultUnit, referenceRanges);
        if (matchingRange == null) {
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

    private LabTestReferenceRange findMatchingRange(String resultUnit, List<LabTestReferenceRange> referenceRanges) {
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
