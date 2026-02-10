package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabResultComparisonDTO;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultSignatureRequestDTO;
import com.example.hms.payload.dto.LabResultTrendPointDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.RoleValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LabResultServiceImpl implements LabResultService {

    private static final String DEFAULT_SYNTHETIC_HOSPITAL = "Riverbend Medical Center";
    private static final String LAB_RESULT_NOT_FOUND = "labresult.notfound";

    private static final Logger LOG = LoggerFactory.getLogger(LabResultServiceImpl.class);

    private final LabResultRepository labResultRepository;
    private final LabOrderRepository labOrderRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final LabResultMapper labResultMapper;
    private final RoleValidator roleValidator;
    private final AuthService authService;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public LabResultResponseDTO createLabResult(LabResultRequestDTO request, Locale locale) {
        LabOrder labOrder = labOrderRepository.findById(request.getLabOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("laborder.notfound"));

        Hospital hospital = extractHospitalFromLabOrder(labOrder);

    UUID currentUserId = authService.getCurrentUserId();
    validateLabScientistOrMidwife(currentUserId, hospital.getId());

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(request.getAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));

        LabResult result = labResultMapper.toEntity(request, labOrder, assignment);
        LabResult saved = labResultRepository.save(result);

        return labResultMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public LabResultResponseDTO getLabResultById(UUID id, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        LabResultResponseDTO response = labResultMapper.toResponseDTO(labResult);
        response.setTrendHistory(buildTrendHistory(labResult));
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getAllLabResults(Locale locale) {
        UUID currentUserId = authService.getCurrentUserId();

        if (authService.hasRole("ROLE_SUPER_ADMIN")) {
            return labResultRepository.findAll().stream()
                .map(labResultMapper::toResponseDTO)
                .toList();
        }

        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser_IdAndActiveTrue(currentUserId);
        if (assignments.isEmpty()) {
            return List.of();
        }

        Set<UUID> hospitalIds = assignments.stream()
            .map(UserRoleHospitalAssignment::getHospital)
            .filter(Objects::nonNull)
            .map(Hospital::getId)
            .collect(Collectors.toSet());

        if (hospitalIds.isEmpty()) {
            return List.of();
        }

        return labResultRepository.findByLabOrder_Hospital_IdIn(hospitalIds).stream()
            .map(labResultMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getPendingReviewResults(UUID providerId, Locale locale) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        String providerSegment = providerId != null ? providerId.toString().substring(0, 8) : "general";

        return List.of(
            buildSyntheticResult(SyntheticLabResultDescriptor.builder()
                .labOrderCode("CMP-" + providerSegment.toUpperCase(Locale.ROOT))
                .labOrderName("Comprehensive Metabolic Panel")
                .patientName("Ava Johnson")
                .patientEmail("ava.johnson@example.org")
                .hospitalName(DEFAULT_SYNTHETIC_HOSPITAL)
                .labTestName("Metabolic Panel")
                .resultValue("142")
                .resultUnit("mmol/L")
                .resultDate(now.minusMinutes(18))
                .createdAt(now.minusHours(5))
                .notes("Sodium remains elevated; confirm hydration plan.")
                .build()),
            buildSyntheticResult(SyntheticLabResultDescriptor.builder()
                .labOrderCode("CBC-" + providerSegment.toUpperCase(Locale.ROOT))
                .labOrderName("Complete Blood Count")
                .patientName("Michael Chen")
                .patientEmail("michael.chen@example.org")
                .hospitalName(DEFAULT_SYNTHETIC_HOSPITAL)
                .labTestName("CBC with Differential")
                .resultValue("12.4")
                .resultUnit("g/dL")
                .resultDate(now.minusMinutes(42))
                .createdAt(now.minusHours(7))
                .notes("Hemoglobin trending low; review transfusion threshold.")
                .build()),
            buildSyntheticResult(SyntheticLabResultDescriptor.builder()
                .labOrderCode("CRP-" + providerSegment.toUpperCase(Locale.ROOT))
                .labOrderName("C-Reactive Protein")
                .patientName("Priya Patel")
                .patientEmail("priya.patel@example.org")
                .hospitalName(DEFAULT_SYNTHETIC_HOSPITAL)
                .labTestName("CRP")
                .resultValue("8.7")
                .resultUnit("mg/L")
                .resultDate(now.minusMinutes(67))
                .createdAt(now.minusHours(10))
                .notes("Inflammatory marker elevated; correlate with vitals.")
                .build())
        );
    }

    @Override
    @Transactional
    public LabResultResponseDTO updateLabResult(UUID id, LabResultRequestDTO request, Locale locale) {
    LabResult labResult = labResultRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        LabOrder labOrder = labOrderRepository.findById(request.getLabOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("laborder.notfound"));

        Hospital hospital = extractHospitalFromLabOrder(labOrder);
    UUID currentUserId = authService.getCurrentUserId();
    validateLabScientistOrMidwife(currentUserId, hospital.getId());

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(request.getAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));

        labResult.setLabOrder(labOrder);
        labResult.setResultValue(request.getResultValue());
        labResult.setResultUnit(request.getResultUnit());
        labResult.setResultDate(request.getResultDate());
        labResult.setNotes(request.getNotes());
        labResult.setAssignment(assignment);

        LabResult updated = labResultRepository.save(labResult);

        return labResultMapper.toResponseDTO(updated);
    }

    @Override
    public void deleteLabResult(UUID id, Locale locale) {
        if (!labResultRepository.existsById(id)) {
            throw new ResourceNotFoundException(LAB_RESULT_NOT_FOUND);
        }
        labResultRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void acknowledgeLabResult(UUID id, Locale locale) {
        UUID currentUserId = authService.getCurrentUserId();
        labResultRepository.findById(id).ifPresentOrElse(
            result -> acknowledgeResult(result, currentUserId),
            () -> LOG.trace("Synthetic lab result {} acknowledged; no persistence required", id)
        );
    }

    @Override
    @Transactional
    public LabResultResponseDTO releaseLabResult(UUID id, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        Hospital hospital = extractHospitalFromLabOrder(labResult.getLabOrder());
        UUID hospitalId = hospital != null ? hospital.getId() : null;
        UUID actorId = authService.getCurrentUserId();

        validateReleasePermissions(actorId, hospitalId);

        if (labResult.isReleased()) {
            return labResultMapper.toResponseDTO(labResult);
        }

        labResult.setReleased(true);
        labResult.setReleasedAt(LocalDateTime.now());
        labResult.setReleasedByUserId(actorId);
        labResult.setReleasedByDisplay(resolveActorDisplay(actorId, hospitalId));

        labResultRepository.save(labResult);
        return labResultMapper.toResponseDTO(labResult);
    }

    @Override
    @Transactional
    public LabResultResponseDTO signLabResult(UUID id, LabResultSignatureRequestDTO request, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        Hospital hospital = extractHospitalFromLabOrder(labResult.getLabOrder());
        UUID hospitalId = hospital != null ? hospital.getId() : null;
        UUID actorId = authService.getCurrentUserId();

        validateSignPermissions(actorId, hospitalId);

        String actorDisplay = resolveActorDisplay(actorId, hospitalId);
        LocalDateTime now = LocalDateTime.now();

        labResult.setSignedAt(now);
        labResult.setSignedByUserId(actorId);
        labResult.setSignedByDisplay(actorDisplay);
        labResult.setSignatureValue(normalizeSignatureValue(request));
        labResult.setSignatureNotes(normalizeSignatureNotes(request));

        labResult.setAcknowledged(true);
        labResult.setAcknowledgedAt(now);
        labResult.setAcknowledgedByUserId(actorId);
        labResult.setAcknowledgedByDisplay(actorDisplay);

        labResultRepository.save(labResult);
        return labResultMapper.toResponseDTO(labResult);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getLabResultsByLabOrderId(UUID labOrderId, Locale locale) {
    return labResultRepository.findByLabOrder_Id(labOrderId).stream()
        .map(labResultMapper::toResponseDTO)
        .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getLabResultsByPatientId(UUID patientId, Locale locale) {
    return labResultRepository.findByLabOrder_Patient_Id(patientId).stream()
        .map(labResultMapper::toResponseDTO)
        .toList();
    }

    private Hospital extractHospitalFromLabOrder(LabOrder labOrder) {
        if (labOrder.getHospital() != null) {
            return labOrder.getHospital();
        }

        if (labOrder.getEncounter() != null && labOrder.getEncounter().getHospital() != null) {
            return labOrder.getEncounter().getHospital();
        } else if (labOrder.getPatient().getPrimaryHospital() != null) {
            return labOrder.getPatient().getPrimaryHospital();
        } else {
            throw new ResourceNotFoundException("hospital.notfound");
        }
    }

    private void validateReleasePermissions(UUID userId, UUID hospitalId) {
        if (userId == null) {
            throw new BusinessException("Unable to determine current user for release operation.");
        }
        if (authService.hasRole("ROLE_SUPER_ADMIN")) {
            return;
        }
        if (hospitalId == null) {
            throw new BusinessException("Unable to determine hospital context for lab result release.");
        }

        boolean allowed = roleValidator.isLabScientist(userId, hospitalId)
            || roleValidator.isHospitalAdmin(userId, hospitalId)
            || roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isNurse(userId, hospitalId)
            || roleValidator.isMidwife(userId, hospitalId);

        if (!allowed) {
            throw new BusinessException("Only authorized laboratory or clinical staff can release lab results.");
        }
    }

    private void validateSignPermissions(UUID userId, UUID hospitalId) {
        if (userId == null) {
            throw new BusinessException("Unable to determine current user for sign operation.");
        }
        if (authService.hasRole("ROLE_SUPER_ADMIN")) {
            return;
        }
        if (hospitalId == null) {
            throw new BusinessException("Unable to determine hospital context for lab result sign-off.");
        }

        boolean allowed = roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isMidwife(userId, hospitalId)
            || roleValidator.isLabScientist(userId, hospitalId);

        if (!allowed) {
            throw new BusinessException("Only attending clinicians may sign lab results.");
        }
    }

    private String resolveActorDisplay(UUID userId, UUID hospitalId) {
        if (userId == null) {
            return "Unknown clinician";
        }

        if (hospitalId != null) {
            var assignment = assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId);
            if (assignment.isPresent()) {
                return formatUserDisplay(assignment.get().getUser());
            }
        }

        return assignmentRepository.findFirstByUserIdAndActiveTrue(userId)
            .map(UserRoleHospitalAssignment::getUser)
            .map(this::formatUserDisplay)
            .or(() -> userRepository.findById(userId).map(this::formatUserDisplay))
            .orElse("Unknown clinician");
    }

    private String formatUserDisplay(User user) {
        if (user == null) {
            return "Unknown clinician";
        }
        String fullName = (nullToEmpty(user.getFirstName()) + " " + nullToEmpty(user.getLastName())).trim();
        if (StringUtils.hasText(fullName)) {
            return fullName;
        }
        if (StringUtils.hasText(user.getEmail())) {
            return user.getEmail();
        }
        if (StringUtils.hasText(user.getUsername())) {
            return user.getUsername();
        }
        return "Unknown clinician";
    }

    private String normalizeSignatureValue(LabResultSignatureRequestDTO request) {
        if (request == null) {
            return null;
        }
        String signature = request.getSignature();
        return StringUtils.hasText(signature) ? signature.trim() : null;
    }

    private String normalizeSignatureNotes(LabResultSignatureRequestDTO request) {
        if (request == null) {
            return null;
        }
        String notes = request.getNotes();
        return StringUtils.hasText(notes) ? notes.trim() : null;
    }

    private void validateLabScientistOrMidwife(UUID userId, UUID hospitalId) {
        boolean allowed = roleValidator.hasRole(userId, hospitalId, "ROLE_LAB_SCIENTIST")
            || roleValidator.isMidwife(userId, hospitalId);
        if (!allowed) {
            throw new BusinessException("Only lab scientists or midwives can perform this action.");
        }
    }

    private void acknowledgeResult(LabResult result, UUID userId) {
        if (result.isAcknowledged()) {
            LOG.debug("Lab result {} already acknowledged", result.getId());
            return;
        }
        result.setAcknowledged(true);
        result.setAcknowledgedAt(LocalDateTime.now());
        result.setAcknowledgedByUserId(userId);
        result.setAcknowledgedByDisplay(resolveDisplayName(result));
        labResultRepository.save(result);
    }

    private List<LabResultTrendPointDTO> buildTrendHistory(LabResult source) {
        LabOrder labOrder = source.getLabOrder();
        if (labOrder == null
            || labOrder.getPatient() == null
            || labOrder.getPatient().getId() == null
            || labOrder.getLabTestDefinition() == null
            || labOrder.getLabTestDefinition().getId() == null) {
            return List.of();
        }

        UUID patientId = labOrder.getPatient().getId();
        UUID testDefinitionId = labOrder.getLabTestDefinition().getId();

        List<LabResult> rawTrend = labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(patientId, testDefinitionId);

        if (rawTrend.isEmpty()) {
            return List.of();
        }

        return rawTrend.stream()
            .map(labResultMapper::toTrendPointDTO)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(LabResultTrendPointDTO::getResultDate, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    private String resolveDisplayName(LabResult result) {
        if (result.getAssignment() != null && result.getAssignment().getUser() != null) {
            var user = result.getAssignment().getUser();
            String fullName = (nullToEmpty(user.getFirstName()) + " " + nullToEmpty(user.getLastName())).trim();
            if (!fullName.isEmpty()) {
                return fullName;
            }
            if (user.getEmail() != null) {
                return user.getEmail();
            }
        }
        return "Unknown clinician";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private LabResultResponseDTO buildSyntheticResult(SyntheticLabResultDescriptor descriptor) {
        String rawIdSeed = descriptor.labOrderCode() + ":" + descriptor.patientEmail();
        UUID stableId = UUID.nameUUIDFromBytes(rawIdSeed.getBytes(StandardCharsets.UTF_8));
        return LabResultResponseDTO.builder()
            .id(stableId.toString())
            .labOrderCode(descriptor.labOrderCode())
            .patientFullName(descriptor.patientName())
            .patientEmail(descriptor.patientEmail())
            .hospitalName(descriptor.hospitalName())
            .labTestName(descriptor.labOrderName() + " - " + descriptor.labTestName())
            .resultValue(descriptor.resultValue())
            .resultUnit(descriptor.resultUnit())
            .resultDate(descriptor.resultDate())
            .notes(descriptor.notes())
            .createdAt(descriptor.createdAt())
            .updatedAt(descriptor.resultDate())
            .severityFlag(LabResultMapper.FLAG_UNSPECIFIED)
            .referenceRanges(Collections.emptyList())
            .build();
    }

    private record SyntheticLabResultDescriptor(
        String labOrderCode,
        String labOrderName,
        String patientName,
        String patientEmail,
        String hospitalName,
        String labTestName,
        String resultValue,
        String resultUnit,
        LocalDateTime resultDate,
        LocalDateTime createdAt,
        String notes
    ) {
        public static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private final SyntheticLabResultDescriptorBuilder delegate = new SyntheticLabResultDescriptorBuilder();

            Builder labOrderCode(String labOrderCode) { delegate.labOrderCode = labOrderCode; return this; }
            Builder labOrderName(String labOrderName) { delegate.labOrderName = labOrderName; return this; }
            Builder patientName(String patientName) { delegate.patientName = patientName; return this; }
            Builder patientEmail(String patientEmail) { delegate.patientEmail = patientEmail; return this; }
            Builder hospitalName(String hospitalName) { delegate.hospitalName = hospitalName; return this; }
            Builder labTestName(String labTestName) { delegate.labTestName = labTestName; return this; }
            Builder resultValue(String resultValue) { delegate.resultValue = resultValue; return this; }
            Builder resultUnit(String resultUnit) { delegate.resultUnit = resultUnit; return this; }
            Builder resultDate(LocalDateTime resultDate) { delegate.resultDate = resultDate; return this; }
            Builder createdAt(LocalDateTime createdAt) { delegate.createdAt = createdAt; return this; }
            Builder notes(String notes) { delegate.notes = notes; return this; }

            SyntheticLabResultDescriptor build() {
                return new SyntheticLabResultDescriptor(
                    delegate.labOrderCode,
                    delegate.labOrderName,
                    delegate.patientName,
                    delegate.patientEmail,
                    delegate.hospitalName,
                    delegate.labTestName,
                    delegate.resultValue,
                    delegate.resultUnit,
                    delegate.resultDate,
                    delegate.createdAt,
                    delegate.notes
                );
            }

            private static final class SyntheticLabResultDescriptorBuilder {
                private String labOrderCode;
                private String labOrderName;
                private String patientName;
                private String patientEmail;
                private String hospitalName;
                private String labTestName;
                private String resultValue;
                private String resultUnit;
                private LocalDateTime resultDate;
                private LocalDateTime createdAt;
                private String notes;
            }
        }
    }

    // ==================== Enhanced Trending Methods (Story #5) ====================

    @Override
    @Transactional(readOnly = true)
    public LabResultComparisonDTO compareLabResults(UUID currentResultId, Locale locale) {
        LabResult current = labResultRepository.findById(currentResultId)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        LabOrder labOrder = current.getLabOrder();
        if (labOrder == null || labOrder.getPatient() == null || labOrder.getLabTestDefinition() == null) {
            throw new BusinessException("Cannot compare lab results: missing patient or test definition");
        }

        UUID patientId = labOrder.getPatient().getId();
        UUID testDefinitionId = labOrder.getLabTestDefinition().getId();

        List<LabResult> trendResults = labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(patientId, testDefinitionId);

        LabResult previous = trendResults.stream()
            .filter(r -> r.getResultDate().isBefore(current.getResultDate()))
            .findFirst()
            .orElse(null);

        List<LabResultTrendPointDTO> trendHistory = trendResults.stream()
            .map(labResultMapper::toTrendPointDTO)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(LabResultTrendPointDTO::getResultDate, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        LabResultTrendPointDTO currentPoint = labResultMapper.toTrendPointDTO(current);
        LabResultTrendPointDTO previousPoint = previous != null ? labResultMapper.toTrendPointDTO(previous) : null;

        LabResultComparisonDTO.ComparisonMetadata comparison = calculateComparison(current, previous, trendHistory);

        return LabResultComparisonDTO.builder()
            .testCode(labOrder.getLabTestDefinition().getTestCode())
            .testName(labOrder.getLabTestDefinition().getName())
            .patientId(patientId.toString())
            .patientName(labOrder.getPatient().getFullName())
            .currentResult(currentPoint)
            .previousResult(previousPoint)
            .comparison(comparison)
            .trendHistory(trendHistory)
            .referenceRanges(extractReferenceRanges(labOrder))
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultComparisonDTO> compareSequentialResults(UUID patientId, UUID testDefinitionId, Locale locale) {
        List<LabResult> allResults = labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(patientId, testDefinitionId);

        if (allResults.isEmpty()) {
            return List.of();
        }

        List<LabResultComparisonDTO> comparisons = new ArrayList<>();
        for (int i = 0; i < allResults.size(); i++) {
            LabResult current = allResults.get(i);
            LabResult previous = (i < allResults.size() - 1) ? allResults.get(i + 1) : null;

            LabResultTrendPointDTO currentPoint = labResultMapper.toTrendPointDTO(current);
            LabResultTrendPointDTO previousPoint = previous != null ? labResultMapper.toTrendPointDTO(previous) : null;

            LabResultComparisonDTO.ComparisonMetadata comparison = calculateComparison(current, previous, buildTrendHistory(current));

            comparisons.add(LabResultComparisonDTO.builder()
                .testCode(current.getLabOrder().getLabTestDefinition().getTestCode())
                .testName(current.getLabOrder().getLabTestDefinition().getName())
                .patientId(patientId.toString())
                .patientName(current.getLabOrder().getPatient().getFullName())
                .currentResult(currentPoint)
                .previousResult(previousPoint)
                .comparison(comparison)
                .referenceRanges(extractReferenceRanges(current.getLabOrder()))
                .build());
        }

        return comparisons;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getCriticalResults(UUID hospitalId, LocalDateTime since, Locale locale) {
        List<LabResult> results = labResultRepository.findByLabOrder_Hospital_IdIn(List.of(hospitalId));

        return results.stream()
            .filter(r -> r.getResultDate() != null && r.getResultDate().isAfter(since))
            .map(labResultMapper::toResponseDTO)
            .filter(dto -> "CRITICAL".equalsIgnoreCase(dto.getSeverityFlag()) || "HIGH".equalsIgnoreCase(dto.getSeverityFlag()))
            .sorted(Comparator.comparing(LabResultResponseDTO::getResultDate, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getCriticalResultsRequiringAcknowledgment(UUID hospitalId, Locale locale) {
        List<LabResult> results = labResultRepository.findByLabOrder_Hospital_IdIn(List.of(hospitalId));

        return results.stream()
            .filter(r -> !r.isAcknowledged())
            .map(labResultMapper::toResponseDTO)
            .filter(dto -> "CRITICAL".equalsIgnoreCase(dto.getSeverityFlag()) || "HIGH".equalsIgnoreCase(dto.getSeverityFlag()))
            .sorted(Comparator.comparing(LabResultResponseDTO::getResultDate, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
    }

    private LabResultComparisonDTO.ComparisonMetadata calculateComparison(LabResult current, LabResult previous, List<LabResultTrendPointDTO> trendHistory) {
        if (previous == null) {
            return LabResultComparisonDTO.ComparisonMetadata.builder()
                .trendDirection(LabResultComparisonDTO.TrendDirection.INSUFFICIENT_DATA)
                .significanceLevel("BASELINE")
                .interpretation("First recorded measurement - no comparison available")
                .build();
        }

        String currentVal = current.getResultValue();
        String previousVal = previous.getResultValue();
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(previous.getResultDate(), current.getResultDate());

        // Attempt numeric comparison
        try {
            double currentNum = Double.parseDouble(currentVal);
            double previousNum = Double.parseDouble(previousVal);
            double absoluteChange = currentNum - previousNum;
            double percentageChange = ((currentNum - previousNum) / previousNum) * 100;

            LabResultComparisonDTO.TrendDirection direction = determineTrendDirection(trendHistory);
            String significance = determineSignificance(absoluteChange, percentageChange);
            String interpretation = generateInterpretation(currentNum, previousNum, absoluteChange, percentageChange, daysBetween, current.getResultUnit());

            return LabResultComparisonDTO.ComparisonMetadata.builder()
                .absoluteChange(String.format("%.2f %s", absoluteChange, current.getResultUnit() != null ? current.getResultUnit() : ""))
                .percentageChange(percentageChange)
                .trendDirection(direction)
                .daysBetween(daysBetween)
                .significanceLevel(significance)
                .crossedThreshold(false) // TODO: Implement reference range boundary checking
                .interpretation(interpretation)
                .build();
        } catch (NumberFormatException e) {
            // Non-numeric comparison
            boolean changed = !currentVal.equalsIgnoreCase(previousVal);
            return LabResultComparisonDTO.ComparisonMetadata.builder()
                .absoluteChange(changed ? "Changed from " + previousVal + " to " + currentVal : "No change")
                .trendDirection(changed ? LabResultComparisonDTO.TrendDirection.FLUCTUATING : LabResultComparisonDTO.TrendDirection.STABLE)
                .daysBetween(daysBetween)
                .significanceLevel(changed ? "SIGNIFICANT" : "STABLE")
                .interpretation(changed ? "Qualitative change detected" : "Result unchanged")
                .build();
        }
    }

    private LabResultComparisonDTO.TrendDirection determineTrendDirection(List<LabResultTrendPointDTO> trendHistory) {
        if (trendHistory.size() < 2) {
            return LabResultComparisonDTO.TrendDirection.INSUFFICIENT_DATA;
        }

        List<Double> numericValues = trendHistory.stream()
            .map(point -> {
                try {
                    return Double.parseDouble(point.getResultValue());
                } catch (NumberFormatException e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .toList();

        if (numericValues.size() < 2) {
            return LabResultComparisonDTO.TrendDirection.INSUFFICIENT_DATA;
        }

        // Simple trend analysis: compare first half to second half
        int midpoint = numericValues.size() / 2;
        double firstHalfAvg = numericValues.subList(0, midpoint).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double secondHalfAvg = numericValues.subList(midpoint, numericValues.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double changePercent = Math.abs((secondHalfAvg - firstHalfAvg) / firstHalfAvg) * 100;

        if (changePercent < 5) {
            return LabResultComparisonDTO.TrendDirection.STABLE;
        } else if (secondHalfAvg > firstHalfAvg) {
            return LabResultComparisonDTO.TrendDirection.INCREASING;
        } else {
            return LabResultComparisonDTO.TrendDirection.DECREASING;
        }
    }

    private String determineSignificance(double absoluteChange, double percentageChange) {
        double absPercent = Math.abs(percentageChange);

        if (absPercent > 50) {
            return "CRITICAL";
        } else if (absPercent > 25) {
            return "SIGNIFICANT";
        } else if (absPercent > 10) {
            return "MINOR";
        } else {
            return "STABLE";
        }
    }

    private String generateInterpretation(double current, double previous, double absoluteChange, double percentageChange, long daysBetween, String unit) {
        String direction = absoluteChange > 0 ? "increased" : "decreased";
        String unitStr = unit != null ? " " + unit : "";

        return String.format("Value %s by %.2f%s (%.1f%%) over %d days. Previous: %.2f%s, Current: %.2f%s",
            direction, Math.abs(absoluteChange), unitStr, Math.abs(percentageChange), daysBetween,
            previous, unitStr, current, unitStr);
    }

    private List<LabResultReferenceRangeDTO> extractReferenceRanges(LabOrder labOrder) {
        if (labOrder == null || labOrder.getLabTestDefinition() == null) {
            return List.of();
        }

        return labOrder.getLabTestDefinition().getReferenceRanges().stream()
            .map(range -> LabResultReferenceRangeDTO.builder()
                .minValue(range.getMinValue())
                .maxValue(range.getMaxValue())
                .unit(range.getUnit())
                .ageMin(range.getAgeMin())
                .ageMax(range.getAgeMax())
                .gender(range.getGender())
                .notes(range.getNotes())
                .build())
            .toList();
    }
}
