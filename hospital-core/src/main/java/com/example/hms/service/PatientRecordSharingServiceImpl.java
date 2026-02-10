package com.example.hms.service;

import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.RecordExportException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdvanceDirectiveMapper;
import com.example.hms.mapper.EncounterHistoryMapper;
import com.example.hms.mapper.EncounterMapper;
import com.example.hms.mapper.EncounterTreatmentMapper;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.mapper.PatientAllergyMapper;
import com.example.hms.mapper.PatientProblemMapper;
import com.example.hms.mapper.PatientSurgicalHistoryMapper;
import com.example.hms.mapper.PatientInsuranceMapper;
import com.example.hms.mapper.PrescriptionMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Encounter;
import com.example.hms.model.EncounterHistory;
import com.example.hms.model.AdvanceDirective;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientConsent;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientSurgicalHistory;
import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.AdvanceDirectiveResponseDTO;
import com.example.hms.payload.dto.EncounterHistoryResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.EncounterTreatmentResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.PatientAllergyResponseDTO;
import com.example.hms.payload.dto.PatientRecordDTO;
import com.example.hms.payload.dto.PatientInsuranceResponseDTO;
import com.example.hms.payload.dto.PatientProblemResponseDTO;
import com.example.hms.payload.dto.PatientSurgicalHistoryResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.AdvanceDirectiveRepository;
import com.example.hms.repository.EncounterHistoryRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientConsentRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientSurgicalHistoryRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.barcodes.BarcodeQRCode;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.function.Supplier;

import jakarta.persistence.PersistenceException;

@Service
@Slf4j
@RequiredArgsConstructor
public class PatientRecordSharingServiceImpl implements PatientRecordSharingService {

    private static final String HEADER_STATUS = "Status";
    private static final String HEADER_NOTES = "Notes";

    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientConsentRepository consentRepository;
    private final EncounterRepository encounterRepository;
    private final EncounterHistoryRepository encounterHistoryRepository;
    private final EncounterTreatmentRepository encounterTreatmentRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final EncounterMapper encounterMapper;
    private final EncounterHistoryMapper encounterHistoryMapper;
    private final EncounterTreatmentMapper encounterTreatmentMapper;
    private final LabOrderMapper labOrderMapper;
    private final LabResultMapper labResultMapper;
    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionMapper prescriptionMapper;
    private final PatientInsuranceRepository patientInsuranceRepository;
    private final PatientInsuranceMapper patientInsuranceMapper;
    private final PatientProblemRepository patientProblemRepository;
    private final PatientProblemMapper patientProblemMapper;
    private final PatientSurgicalHistoryRepository patientSurgicalHistoryRepository;
    private final PatientSurgicalHistoryMapper patientSurgicalHistoryMapper;
    private final AdvanceDirectiveRepository advanceDirectiveRepository;
    private final AdvanceDirectiveMapper advanceDirectiveMapper;
    private final PatientAllergyRepository patientAllergyRepository;
    private final PatientAllergyMapper patientAllergyMapper;
    private final AuditEventLogRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private final Map<String, Boolean> tableAvailabilityCache = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public PatientRecordDTO getPatientRecord(UUID patientId, UUID fromHospitalId, UUID toHospitalId) {
        return buildPatientRecord(patientId, fromHospitalId, toHospitalId);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportPatientRecord(UUID patientId, UUID fromHospitalId, UUID toHospitalId, String format) {
        PatientRecordDTO dto = buildPatientRecord(patientId, fromHospitalId, toHospitalId);

        if ("csv".equalsIgnoreCase(format)) {
            return generateCsv(dto);
        }
        if ("pdf".equalsIgnoreCase(format)) {
            return generatePdf(dto);
        }
        return throwUnsupportedFormat(format);
    }

    private PatientRecordDTO buildPatientRecord(UUID patientId, UUID fromHospitalId, UUID toHospitalId) {
        PatientConsent consent = consentRepository
            .findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospitalId, toHospitalId)
            .orElseThrow(() -> new BusinessException("Active consent is required before sharing records."));

        if (!consent.isConsentActive()) {
            throw new BusinessException("Consent not granted or expired for sharing records.");
        }

        Patient patient = consent.getPatient() != null
            ? consent.getPatient()
            : patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found."));

        Hospital fromHospital = consent.getFromHospital() != null
            ? consent.getFromHospital()
            : hospitalRepository.findById(fromHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Source hospital not found."));

        Hospital toHospital = consent.getToHospital() != null
            ? consent.getToHospital()
            : hospitalRepository.findById(toHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Target hospital not found."));

        Map<UUID, String> mrnByHospital = buildHospitalMrnMap(patient);

        List<Encounter> encounters = safeFetchFromTable(
            "clinical",
            "encounters",
            () -> encounterRepository.findByPatient_Id(patientId),
            "Encounter"
        );

        List<Encounter> encountersInScope = encounters.stream()
            .filter(encounter -> encounter.getHospital() != null && fromHospitalId.equals(encounter.getHospital().getId()))
            .sorted(Comparator.comparing(Encounter::getEncounterDate, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        List<EncounterResponseDTO> encounterDtos = encountersInScope.stream()
            .map(encounterMapper::toEncounterResponseDTO)
            .toList();

        Map<UUID, Encounter> encounterById = encountersInScope.stream()
            .collect(Collectors.toMap(Encounter::getId, Function.identity()));

        List<EncounterHistoryResponseDTO> encounterHistoryDtos = encounterById.isEmpty()
            ? List.of()
            : loadEncounterHistory(encounterById);

        List<EncounterTreatmentResponseDTO> treatmentDtos = loadEncounterTreatments(encountersInScope);

        Comparator<PatientProblem> problemComparator = Comparator
            .comparing(PatientProblem::getOnsetDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PatientProblem::getLastReviewedAt, Comparator.nullsLast(Comparator.reverseOrder()));

        List<PatientProblemResponseDTO> problemDtos = safeFetchFromTable(
            "clinical",
            "patient_problems",
            () -> patientProblemRepository
                .findByPatient_IdAndHospital_Id(patientId, fromHospitalId).stream()
                .sorted(problemComparator)
                .map(patientProblemMapper::toResponseDto)
                .toList(),
            "Patient problem"
        );

        List<PatientSurgicalHistoryResponseDTO> surgicalHistoryDtos = safeFetchFromTable(
            "clinical",
            "patient_surgical_history",
            () -> patientSurgicalHistoryRepository
                .findByPatient_IdAndHospital_Id(patientId, fromHospitalId).stream()
                .sorted(Comparator
                    .comparing(PatientSurgicalHistory::getProcedureDate, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(PatientSurgicalHistory::getLastUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(patientSurgicalHistoryMapper::toResponseDto)
                .toList(),
            "Patient surgical history"
        );

        List<AdvanceDirectiveResponseDTO> advanceDirectiveDtos = safeFetchFromTable(
            "clinical",
            "advance_directives",
            () -> advanceDirectiveRepository
                .findByPatient_IdAndHospital_Id(patientId, fromHospitalId).stream()
                .sorted(Comparator
                    .comparing(AdvanceDirective::getEffectiveDate, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(AdvanceDirective::getLastReviewedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(advanceDirectiveMapper::toResponseDto)
                .toList(),
            "Advance directive"
        );

        List<LabOrder> labOrdersInScope = safeFetchFromTable(
            "lab",
            "lab_orders",
            () -> labOrderRepository.findByPatient_Id(patientId).stream()
                .filter(order -> order.getHospital() != null && fromHospitalId.equals(order.getHospital().getId()))
                .sorted(Comparator.comparing(LabOrder::getOrderDatetime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList(),
            "Lab order"
        );

        List<LabOrderResponseDTO> labOrderDtos = labOrdersInScope.stream()
            .map(labOrderMapper::toLabOrderResponseDTO)
            .toList();

        Set<UUID> labOrderIds = labOrdersInScope.stream()
            .map(LabOrder::getId)
            .collect(Collectors.toSet());

        List<LabResultResponseDTO> labResultDtos = safeFetchFromTable(
            "lab",
            "lab_results",
            () -> labResultRepository.findByLabOrder_Patient_Id(patientId).stream()
                .filter(result -> result.getLabOrder() != null
                    && result.getLabOrder().getHospital() != null
                    && fromHospitalId.equals(result.getLabOrder().getHospital().getId()))
                .filter(result -> {
                    UUID orderId = result.getLabOrder() != null ? result.getLabOrder().getId() : null;
                    return orderId == null || labOrderIds.contains(orderId);
                })
                .map(labResultMapper::toResponseDTO)
                .toList(),
            "Lab result"
        );

        Comparator<PatientAllergy> allergyComparator = Comparator
            .<PatientAllergy>comparingInt(allergy -> severityOrder(allergy.getSeverity()))
            .thenComparing(PatientAllergy::getOnsetDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PatientAllergy::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()));

        List<PatientAllergy> allergyEntities = safeFetchFromTable(
            "clinical",
            "patient_allergies",
            () -> fromHospitalId == null
                ? patientAllergyRepository.findByPatient_Id(patientId)
                : patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, fromHospitalId),
            "Patient allergy"
        );

        List<PatientAllergyResponseDTO> allergyDtos = allergyEntities.stream()
            .sorted(allergyComparator)
            .map(patientAllergyMapper::toResponseDto)
            .toList();

        List<PrescriptionResponseDTO> prescriptionDtos = safeFetchFromTable(
            "clinical",
            "prescriptions",
            () -> prescriptionRepository
                .findByPatient_IdAndHospital_Id(patientId, fromHospitalId).stream()
                .sorted(Comparator.comparing(Prescription::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(prescriptionMapper::toResponseDTO)
                .toList(),
            "Prescription"
        );

        List<PatientInsuranceResponseDTO> insuranceDtos = safeFetchFromTable(
            "clinical",
            "patient_insurances",
            () -> patientInsuranceRepository
                .findByPatient_Id(patientId).stream()
                .filter(insurance -> isInsuranceInScope(insurance, fromHospitalId))
                .sorted(Comparator
                    .comparing(PatientInsurance::isPrimary).reversed()
                    .thenComparing(PatientInsurance::getEffectiveDate, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(PatientInsurance::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(patientInsuranceMapper::toPatientInsuranceResponseDTO)
                .toList(),
            "Patient insurance"
        );

        PatientRecordDTO dto = PatientRecordDTO.builder()
            .patientId(patient.getId())
            .firstName(patient.getFirstName())
            .lastName(patient.getLastName())
            .middleName(patient.getMiddleName())
            .dateOfBirth(patient.getDateOfBirth())
            .gender(patient.getGender())
            .bloodType(patient.getBloodType())
            .medicalHistorySummary(patient.getMedicalHistorySummary())
            .allergies(resolveAllergySummary(patient, allergyDtos))
            .address(patient.getAddress())
            .city(patient.getCity())
            .state(patient.getState())
            .zipCode(patient.getZipCode())
            .country(patient.getCountry())
            .phoneNumberPrimary(patient.getPhoneNumberPrimary())
            .phoneNumberSecondary(patient.getPhoneNumberSecondary())
            .email(patient.getEmail())
            .emergencyContactName(patient.getEmergencyContactName())
            .emergencyContactPhone(patient.getEmergencyContactPhone())
            .emergencyContactRelationship(patient.getEmergencyContactRelationship())
            .hospitalMRNs(Set.copyOf(mrnByHospital.values()))
            .hospitalMrnMap(new LinkedHashMap<>(mrnByHospital))
            .consentId(consent.getId())
            .consentTimestamp(consent.getConsentTimestamp())
            .consentExpiration(consent.getConsentExpiration())
            .consentPurpose(consent.getPurpose())
            .fromHospitalId(fromHospital.getId())
            .fromHospitalName(fromHospital.getName())
            .toHospitalId(toHospital.getId())
            .toHospitalName(toHospital.getName())
            .encounters(encounterDtos)
            .treatments(treatmentDtos)
            .labOrders(labOrderDtos)
            .labResults(labResultDtos)
            .allergiesDetailed(allergyDtos)
            .prescriptions(prescriptionDtos)
            .insurances(insuranceDtos)
            .problems(problemDtos)
            .surgicalHistory(surgicalHistoryDtos)
            .advanceDirectives(advanceDirectiveDtos)
            .encounterHistory(encounterHistoryDtos)
            .build();

        logAuditEvent(patient, fromHospitalId, toHospitalId, dto, consent);
        return dto;
    }

    private Map<UUID, String> buildHospitalMrnMap(Patient patient) {
        if (patient.getHospitalRegistrations() == null || patient.getHospitalRegistrations().isEmpty()) {
            return Map.of();
        }

        return patient.getHospitalRegistrations().stream()
            .filter(PatientHospitalRegistration::isActive)
            .filter(registration -> registration.getHospital() != null && registration.getHospital().getId() != null)
            .filter(registration -> registration.getMrn() != null && !registration.getMrn().isBlank())
            .sorted(Comparator.comparing(
                PatientHospitalRegistration::getRegistrationDate,
                Comparator.nullsLast(Comparator.naturalOrder())
            ))
            .collect(Collectors.toMap(
                registration -> registration.getHospital().getId(),
                PatientHospitalRegistration::getMrn,
                (existing, replacement) -> existing,
                LinkedHashMap::new
            ));
    }

    private List<EncounterHistoryResponseDTO> loadEncounterHistory(Map<UUID, Encounter> encounterById) {
        Set<UUID> encounterIds = encounterById.keySet();
        if (encounterIds.isEmpty()) {
            return List.of();
        }

        return safeFetchFromTable(
            "clinical",
            "encounter_history",
            () -> encounterHistoryRepository.findByEncounterIdIn(encounterIds).stream()
                .filter(history -> history.getEncounterId() != null && encounterById.containsKey(history.getEncounterId()))
                .sorted(Comparator.comparing(EncounterHistory::getChangedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(encounterHistoryMapper::toResponseDto)
                .toList(),
            "Encounter history"
        );
    }

    private List<EncounterTreatmentResponseDTO> loadEncounterTreatments(List<Encounter> encountersInScope) {
        if (encountersInScope.isEmpty()) {
            return List.of();
        }

        return safeFetchFromTable(
            "clinical",
            "encounter_treatments",
            () -> encountersInScope.stream()
                .flatMap(encounter -> encounterTreatmentRepository.findByEncounter_Id(encounter.getId()).stream())
                .map(encounterTreatmentMapper::toDto)
                .toList(),
            "Encounter treatment"
        );
    }

    private boolean isMissingTable(Throwable throwable, String schema, String tableName) {
        Throwable current = throwable;
        String lookup = (schema + "." + tableName).toLowerCase(Locale.ROOT);
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains(lookup)
                    && (lower.contains("does not exist") || lower.contains("missing") || lower.contains("not found"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isTableAvailable(String schema, String tableName) {
        String cacheKey = schema + "." + tableName;
        return tableAvailabilityCache.computeIfAbsent(cacheKey, key -> {
            try {
                Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)",
                    Boolean.class,
                    schema,
                    tableName
                );
                boolean available = Boolean.TRUE.equals(exists);
                if (!available) {
                    log.info("Table {}.{} not found; omitting related data from record share payload.", schema, tableName);
                }
                return available;
            } catch (DataAccessException ex) {
                log.warn("Unable to verify table {}.{} availability; treating as absent.", schema, tableName);
                log.debug("Table availability check failure", ex);
                return false;
            }
        });
    }

    private <T> List<T> safeFetchFromTable(String schema, String tableName, Supplier<List<T>> fetcher, String logSubject) {
        if (!isTableAvailable(schema, tableName)) {
            return List.of();
        }

        try {
            return fetcher.get();
        } catch (DataAccessException | PersistenceException ex) {
            if (isMissingTable(ex, schema, tableName)) {
                tableAvailabilityCache.put(schema + "." + tableName, false);
                log.warn("{} data unavailable; continuing without entries.", logSubject);
                log.debug("{} lookup failure", logSubject, ex);
                return List.of();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (isMissingTable(ex, schema, tableName)) {
                tableAvailabilityCache.put(schema + "." + tableName, false);
                log.warn("{} data unavailable; continuing without entries.", logSubject);
                log.debug("{} lookup failure", logSubject, ex);
                return List.of();
            }
            throw ex;
        }
    }

    private boolean isInsuranceInScope(PatientInsurance insurance, UUID fromHospitalId) {
        if (insurance == null) {
            return false;
        }
        if (insurance.getAssignment() == null || insurance.getAssignment().getHospital() == null) {
            return true;
        }
        return fromHospitalId != null
            && fromHospitalId.equals(insurance.getAssignment().getHospital().getId());
    }

    private byte[] generateCsv(PatientRecordDTO dto) {
        List<PatientAllergyResponseDTO> allergies = dto.getAllergiesDetailed();
        long totalAllergies = allergies.size();
        long criticalAllergyCount = allergies.stream().filter(this::isCriticalAllergy).count();
        long activeAllergyCount = allergies.stream().filter(this::isActiveAllergy).count();
        String allergyDetails = formatAllergyDetailsForExport(allergies);
        Set<String> hospitalMrns = dto.getHospitalMRNs() != null ? dto.getHospitalMRNs() : Collections.emptySet();

        StringBuilder sb = new StringBuilder();
        sb.append(
            "Patient ID,First Name,Middle Name,Last Name,Date of Birth,Gender,Blood Type,Email,Primary Phone,Secondary Phone,Address,City,State,ZIP,Country,Allergies,Allergy Count,Critical Allergy Count,Active Allergy Count,Allergy Details,Medical History,Emergency Contact Name,Emergency Contact Phone,Emergency Contact Relationship,MRNs,Prescription Count,Problem Count,Surgical History Count,Advance Directive Count,Encounter History Count,Insurance Count\n"
        );
        sb.append(dto.getPatientId()).append(",")
            .append(escapeCsv(dto.getFirstName())).append(",")
            .append(escapeCsv(dto.getMiddleName())).append(",")
            .append(escapeCsv(dto.getLastName())).append(",")
            .append(dto.getDateOfBirth()).append(",")
            .append(escapeCsv(dto.getGender())).append(",")
            .append(escapeCsv(dto.getBloodType())).append(",")
            .append(escapeCsv(dto.getEmail())).append(",")
            .append(escapeCsv(dto.getPhoneNumberPrimary())).append(",")
            .append(escapeCsv(dto.getPhoneNumberSecondary())).append(",")
            .append(escapeCsv(dto.getAddress())).append(",")
            .append(escapeCsv(dto.getCity())).append(",")
            .append(escapeCsv(dto.getState())).append(",")
            .append(escapeCsv(dto.getZipCode())).append(",")
            .append(escapeCsv(dto.getCountry())).append(",")
            .append(escapeCsv(dto.getAllergies())).append(",")
            .append(totalAllergies).append(",")
            .append(criticalAllergyCount).append(",")
            .append(activeAllergyCount).append(",")
            .append(escapeCsv(allergyDetails)).append(",")
            .append(escapeCsv(dto.getMedicalHistorySummary())).append(",")
            .append(escapeCsv(dto.getEmergencyContactName())).append(",")
            .append(escapeCsv(dto.getEmergencyContactPhone())).append(",")
            .append(escapeCsv(dto.getEmergencyContactRelationship())).append(",")
            .append("\"").append(String.join(" | ", hospitalMrns)).append("\"").append(",")
            .append(dto.getPrescriptions().size()).append(",")
            .append(dto.getProblems().size()).append(",")
            .append(dto.getSurgicalHistory().size()).append(",")
            .append(dto.getAdvanceDirectives().size()).append(",")
            .append(dto.getEncounterHistory().size()).append(",")
            .append(dto.getInsurances().size())
            .append("\n");

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\"")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private String joinNonBlank(String... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        return Arrays.stream(parts)
            .filter(part -> part != null && !part.isBlank())
            .map(String::trim)
            .collect(Collectors.joining(" "));
    }

    private String formatAllergyDetailsForExport(List<PatientAllergyResponseDTO> allergies) {
        if (allergies == null || allergies.isEmpty()) {
            return "";
        }
        int limit = Math.min(5, allergies.size());
        List<String> entries = new ArrayList<>(limit + 1);
        for (int i = 0; i < limit; i++) {
            String summary = summarizeAllergyForExport(allergies.get(i));
            if (!summary.isBlank()) {
                entries.add(summary);
            }
        }
        if (allergies.size() > limit) {
            entries.add("+" + (allergies.size() - limit) + " more");
        }
        return String.join(" | ", entries);
    }

    private String summarizeAllergyForExport(PatientAllergyResponseDTO allergy) {
        if (allergy == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (hasText(allergy.getAllergenDisplay())) {
            parts.add(allergy.getAllergenDisplay().trim());
        } else if (hasText(allergy.getAllergenCode())) {
            parts.add(allergy.getAllergenCode().trim());
        }
        if (hasText(allergy.getSeverity())) {
            parts.add(allergy.getSeverity().trim());
        }
        if (Boolean.FALSE.equals(allergy.getActive())) {
            parts.add("inactive");
        }
        String reactionDetail = null;
        if (hasText(allergy.getReaction()) && hasText(allergy.getReactionNotes())) {
            reactionDetail = allergy.getReaction().trim() + " — " + allergy.getReactionNotes().trim();
        } else if (hasText(allergy.getReaction())) {
            reactionDetail = allergy.getReaction().trim();
        } else if (hasText(allergy.getReactionNotes())) {
            reactionDetail = allergy.getReactionNotes().trim();
        }
        if (reactionDetail != null && !reactionDetail.isBlank()) {
            parts.add(reactionDetail);
        }
        if (parts.isEmpty()) {
            return allergy.getId() != null ? "Allergy " + allergy.getId() : "Allergy";
        }
        return String.join(" • ", parts);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isCriticalAllergy(PatientAllergyResponseDTO allergy) {
        if (allergy == null || !hasText(allergy.getSeverity())) {
            return false;
        }
        String severity = allergy.getSeverity().trim().toUpperCase(Locale.ROOT);
        try {
            AllergySeverity parsed = AllergySeverity.valueOf(severity);
            return parsed == AllergySeverity.LIFE_THREATENING || parsed == AllergySeverity.SEVERE;
        } catch (IllegalArgumentException ex) {
            return "CRITICAL".equals(severity) || "ANAPHYLAXIS".equals(severity);
        }
    }

    private boolean isActiveAllergy(PatientAllergyResponseDTO allergy) {
        return allergy != null && Boolean.TRUE.equals(allergy.getActive());
    }

    private int severityOrder(AllergySeverity severity) {
        if (severity == null) {
            return 4;
        }
        return switch (severity) {
            case LIFE_THREATENING -> 0;
            case SEVERE -> 1;
            case MODERATE -> 2;
            case MILD -> 3;
            case UNKNOWN -> 4;
        };
    }

    private String resolveAllergySummary(Patient patient, List<PatientAllergyResponseDTO> allergyDtos) {
        if (allergyDtos != null && !allergyDtos.isEmpty()) {
            List<String> allergenNames = allergyDtos.stream()
                .map(PatientAllergyResponseDTO::getAllergenDisplay)
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .toList();

            if (!allergenNames.isEmpty()) {
                int limit = Math.min(5, allergenNames.size());
                String headline = String.join(", ", allergenNames.subList(0, limit));
                int remaining = allergenNames.size() - limit;
                return remaining > 0 ? headline + " +" + remaining + " more" : headline;
            }
        }

        String legacyAllergies = patient.getAllergies();
        if (legacyAllergies == null) {
            return null;
        }
        String trimmed = legacyAllergies.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultText(String value) {
        if (value == null) {
            return "—";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "—" : trimmed;
    }
    /**
     * Generates a PDF document containing the patient's record.
     *
     * @param dto The patient record data transfer object.
     * @return A byte array representing the PDF document.
     */
    private byte[] generatePdf(PatientRecordDTO dto) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (PdfWriter writer = new PdfWriter(out);
             PdfDocument pdfDoc = new PdfDocument(writer);
             Document document = new Document(pdfDoc)) {

            PdfFont font = PdfFontFactory.createFont(StandardFonts.HELVETICA);
            Color headerColor = new DeviceRgb(63, 81, 181);
            Color borderColor = new DeviceRgb(200, 200, 200);

            addHospitalLogo(document);

            document.add(new Paragraph("Patient Record")
                    .setFont(font)
                    .setFontSize(20)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(headerColor));
            document.add(new Paragraph("\n"));

            float[] columnWidths = {150F, 300F};
            Table table = new Table(columnWidths).setWidth(UnitValue.createPercentValue(100));

            table.addHeaderCell(new Cell().add(new Paragraph("Field").setBold())
                    .setBackgroundColor(headerColor)
                    .setFontColor(ColorConstants.WHITE)
                    .setBorder(Border.NO_BORDER));
            table.addHeaderCell(new Cell().add(new Paragraph("Value").setBold())
                    .setBackgroundColor(headerColor)
                    .setFontColor(ColorConstants.WHITE)
                    .setBorder(Border.NO_BORDER));

            BiConsumer<String, String> addRow = (label, value) -> {
                table.addCell(new Cell().add(new Paragraph(label)).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
                table.addCell(new Cell().add(new Paragraph(value != null ? value : "N/A")).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            };

            addRow.accept("Patient ID", dto.getPatientId().toString());
            addRow.accept("Name", joinNonBlank(dto.getFirstName(), dto.getMiddleName(), dto.getLastName()));
            addRow.accept("Date of Birth", dto.getDateOfBirth() != null ? dto.getDateOfBirth().toString() : "N/A");
            addRow.accept("Gender", dto.getGender());
            addRow.accept("Blood Type", dto.getBloodType());
            addRow.accept("Email", dto.getEmail());
            addRow.accept("Primary Phone", dto.getPhoneNumberPrimary());
            addRow.accept("Secondary Phone", dto.getPhoneNumberSecondary());
            addRow.accept("Address", joinNonBlank(dto.getAddress(), dto.getCity(), dto.getState(), dto.getZipCode(), dto.getCountry()));
            addRow.accept("Allergies", dto.getAllergies());
            addRow.accept("Medical History", dto.getMedicalHistorySummary());
            addRow.accept("Emergency Contact", joinNonBlank(dto.getEmergencyContactName(), dto.getEmergencyContactRelationship()));
            addRow.accept("Emergency Contact Phone", dto.getEmergencyContactPhone());
            addRow.accept("MRNs", String.join(", ", dto.getHospitalMRNs()));

            document.add(table);
            document.add(new Paragraph("\n"));

            addAllergySection(document, dto, font, headerColor, borderColor);

            if (!dto.getPrescriptions().isEmpty()) {
                document.add(new Paragraph("Prescriptions")
                    .setFont(font)
                    .setFontSize(16)
                    .setBold()
                    .setFontColor(headerColor)
                    .setMarginTop(10));

                float[] rxWidths = {160F, 80F, 80F, 80F, 80F, 160F};
                Table rxTable = new Table(rxWidths).setWidth(UnitValue.createPercentValue(100));

                String[] rxHeaders = {"Medication", "Dosage", "Frequency", "Duration", HEADER_STATUS, HEADER_NOTES};
                for (String header : rxHeaders) {
                    rxTable.addHeaderCell(new Cell()
                        .add(new Paragraph(header).setBold())
                        .setBackgroundColor(headerColor)
                        .setFontColor(ColorConstants.WHITE)
                        .setBorder(Border.NO_BORDER));
                }

                dto.getPrescriptions().forEach(prescription -> {
                    rxTable.addCell(new Cell().add(new Paragraph(defaultText(prescription.getMedicationDisplayName()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
                    rxTable.addCell(new Cell().add(new Paragraph(defaultText(prescription.getDosage()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
                    rxTable.addCell(new Cell().add(new Paragraph(defaultText(prescription.getFrequency()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
                    rxTable.addCell(new Cell().add(new Paragraph(defaultText(prescription.getDuration()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
                    rxTable.addCell(new Cell().add(new Paragraph(defaultText(prescription.getStatus()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
                    String notes = prescription.getNotes();
                    if ((notes == null || notes.isBlank()) && prescription.getCreatedAt() != null) {
                        notes = "Prescribed on " + prescription.getCreatedAt();
                    }
                    rxTable.addCell(new Cell().add(new Paragraph(defaultText(notes))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
                });

                document.add(rxTable);
                document.add(new Paragraph("\n"));
            }

            addProblemSection(document, dto, font, headerColor, borderColor);
            addSurgicalHistorySection(document, dto, font, headerColor, borderColor);
            addEncounterHistorySection(document, dto, font, headerColor, borderColor);
            addAdvanceDirectiveSection(document, dto, font, headerColor, borderColor);
            addInsuranceSection(document, dto, font, headerColor, borderColor);

            String qrContent = "https://hospital-system.com/patient/" + dto.getPatientId();
            BarcodeQRCode qrCode = new BarcodeQRCode(qrContent);
            PdfFormXObject qrFormObject = qrCode.createFormXObject(ColorConstants.BLACK, pdfDoc);
            Image qrImage = new Image(qrFormObject)
                    .scaleToFit(100, 100)
                    .setHorizontalAlignment(HorizontalAlignment.CENTER);

            Paragraph qrSection = new Paragraph("Scan QR Code to Access Record Online")
                    .setFontSize(12)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(20);

            document.add(qrSection);
            document.add(qrImage);

            document.add(new Paragraph("\nGenerated on: " + LocalDateTime.now())
                    .setFontSize(10)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setFontColor(ColorConstants.GRAY));
            document.add(new Paragraph("Hospital System")
                    .setFontSize(10)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setFontColor(ColorConstants.GRAY));
        } catch (Exception e) {
            throw new RecordExportException("Failed to generate patient record PDF", e);
        }

        return out.toByteArray();
    }

    private void addAllergySection(Document document, PatientRecordDTO dto, PdfFont font, Color headerColor, Color borderColor) {
        List<PatientAllergyResponseDTO> allergies = dto.getAllergiesDetailed();
        if (allergies == null || allergies.isEmpty()) {
            return;
        }

        document.add(new Paragraph("Allergies")
            .setFont(font)
            .setFontSize(16)
            .setBold()
            .setFontColor(headerColor)
            .setMarginTop(10));

        float[] widths = {170F, 80F, 110F, 180F, 70F, 100F, 120F, 120F};
        Table table = new Table(widths).setWidth(UnitValue.createPercentValue(100));

        String[] headers = {"Allergen", "Severity", "Verification", "Reaction / Notes", "Active", "Last Occurrence", "Hospital", "Source"};
        for (String header : headers) {
            table.addHeaderCell(new Cell()
                .add(new Paragraph(header).setBold())
                .setBackgroundColor(headerColor)
                .setFontColor(ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER));
        }

        allergies.forEach(allergy -> {
            String allergen = hasText(allergy.getAllergenDisplay())
                ? allergy.getAllergenDisplay().trim()
                : (hasText(allergy.getAllergenCode()) ? allergy.getAllergenCode().trim() : null);
            String reactionDetail;
            if (hasText(allergy.getReaction()) && hasText(allergy.getReactionNotes())) {
                reactionDetail = allergy.getReaction().trim() + " — " + allergy.getReactionNotes().trim();
            } else if (hasText(allergy.getReaction())) {
                reactionDetail = allergy.getReaction().trim();
            } else if (hasText(allergy.getReactionNotes())) {
                reactionDetail = allergy.getReactionNotes().trim();
            } else {
                reactionDetail = null;
            }
            String activeLabel = allergy.getActive() == null ? null : (allergy.getActive() ? "Active" : "Inactive");
            String lastOccurrence = allergy.getLastOccurrenceDate() != null ? allergy.getLastOccurrenceDate().toString() : null;

            table.addCell(new Cell().add(new Paragraph(defaultText(allergen))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(allergy.getSeverity()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(allergy.getVerificationStatus()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(reactionDetail))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(activeLabel))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(lastOccurrence))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(allergy.getHospitalName()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(allergy.getSourceSystem()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
        });

        document.add(table);
        document.add(new Paragraph("\n"));
    }

    private void addEncounterHistorySection(Document document, PatientRecordDTO dto, PdfFont font, Color headerColor, Color borderColor) {
        List<EncounterHistoryResponseDTO> historyEntries = dto.getEncounterHistory();
        if (historyEntries == null || historyEntries.isEmpty()) {
            return;
        }

        document.add(new Paragraph("Encounter History")
            .setFont(font)
            .setFontSize(16)
            .setBold()
            .setFontColor(headerColor)
            .setMarginTop(10));

        float[] historyWidths = {120F, 90F, 90F, 110F, 120F, 190F};
        Table historyTable = new Table(historyWidths).setWidth(UnitValue.createPercentValue(100));

    String[] historyHeaders = {"Changed At", "Change Type", HEADER_STATUS, "Encounter Type", "Changed By", HEADER_NOTES};
        for (String header : historyHeaders) {
            historyTable.addHeaderCell(new Cell()
                .add(new Paragraph(header).setBold())
                .setBackgroundColor(headerColor)
                .setFontColor(ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER));
        }

        historyEntries.forEach(entry -> {
            historyTable.addCell(new Cell().add(new Paragraph(defaultText(entry.getChangedAt() != null ? entry.getChangedAt().toString() : null))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            historyTable.addCell(new Cell().add(new Paragraph(defaultText(entry.getChangeType()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            historyTable.addCell(new Cell().add(new Paragraph(defaultText(entry.getStatus()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            historyTable.addCell(new Cell().add(new Paragraph(defaultText(entry.getEncounterType()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            historyTable.addCell(new Cell().add(new Paragraph(defaultText(entry.getChangedBy()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            historyTable.addCell(new Cell().add(new Paragraph(defaultText(entry.getNotes()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
        });

        document.add(historyTable);
        document.add(new Paragraph("\n"));
    }

    private void addProblemSection(Document document, PatientRecordDTO dto, PdfFont font, Color headerColor, Color borderColor) {
        List<PatientProblemResponseDTO> problems = dto.getProblems();
        if (problems == null || problems.isEmpty()) {
            return;
        }

        document.add(new Paragraph("Clinical Problems")
            .setFont(font)
            .setFontSize(16)
            .setBold()
            .setFontColor(headerColor)
            .setMarginTop(10));

        float[] widths = {180F, 80F, 80F, 80F, 80F, 120F, 200F};
        Table table = new Table(widths).setWidth(UnitValue.createPercentValue(100));

    String[] headers = {"Problem", HEADER_STATUS, "Severity", "Onset", "Resolved", "Source", HEADER_NOTES};
        for (String header : headers) {
            table.addHeaderCell(new Cell()
                .add(new Paragraph(header).setBold())
                .setBackgroundColor(headerColor)
                .setFontColor(ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER));
        }

        problems.forEach(problem -> {
            table.addCell(new Cell().add(new Paragraph(defaultText(problem.getProblemDisplay()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(problem.getStatus()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(problem.getSeverity()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(problem.getOnsetDate() != null ? problem.getOnsetDate().toString() : null))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(problem.getResolvedDate() != null ? problem.getResolvedDate().toString() : null))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(problem.getSourceSystem()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(problem.getNotes()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
        });

        document.add(table);
        document.add(new Paragraph("\n"));
    }

    private void addSurgicalHistorySection(Document document, PatientRecordDTO dto, PdfFont font, Color headerColor, Color borderColor) {
        List<PatientSurgicalHistoryResponseDTO> surgeries = dto.getSurgicalHistory();
        if (surgeries == null || surgeries.isEmpty()) {
            return;
        }

        document.add(new Paragraph("Surgical History")
            .setFont(font)
            .setFontSize(16)
            .setBold()
            .setFontColor(headerColor)
            .setMarginTop(10));

        float[] widths = {180F, 80F, 80F, 100F, 120F, 200F};
        Table table = new Table(widths).setWidth(UnitValue.createPercentValue(100));

    String[] headers = {"Procedure", "Date", "Outcome", "Location", "Performed By", HEADER_NOTES};
        for (String header : headers) {
            table.addHeaderCell(new Cell()
                .add(new Paragraph(header).setBold())
                .setBackgroundColor(headerColor)
                .setFontColor(ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER));
        }

        surgeries.forEach(history -> {
            table.addCell(new Cell().add(new Paragraph(defaultText(history.getProcedureDisplay()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(history.getProcedureDate() != null ? history.getProcedureDate().toString() : null))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(history.getOutcome()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(history.getLocation()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(history.getPerformedBy()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(history.getNotes()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
        });

        document.add(table);
        document.add(new Paragraph("\n"));
    }

    private void addAdvanceDirectiveSection(Document document, PatientRecordDTO dto, PdfFont font, Color headerColor, Color borderColor) {
        List<AdvanceDirectiveResponseDTO> directives = dto.getAdvanceDirectives();
        if (directives == null || directives.isEmpty()) {
            return;
        }

        document.add(new Paragraph("Advance Directives")
            .setFont(font)
            .setFontSize(16)
            .setBold()
            .setFontColor(headerColor)
            .setMarginTop(10));

        float[] widths = {150F, 80F, 80F, 80F, 120F, 120F, 160F};
        Table table = new Table(widths).setWidth(UnitValue.createPercentValue(100));

    String[] headers = {"Type", HEADER_STATUS, "Effective", "Expires", "Physician", "Witness", "Document"};
        for (String header : headers) {
            table.addHeaderCell(new Cell()
                .add(new Paragraph(header).setBold())
                .setBackgroundColor(headerColor)
                .setFontColor(ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER));
        }

        directives.forEach(directive -> {
            table.addCell(new Cell().add(new Paragraph(defaultText(directive.getDirectiveType()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(directive.getStatus()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(directive.getEffectiveDate() != null ? directive.getEffectiveDate().toString() : null))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(directive.getExpirationDate() != null ? directive.getExpirationDate().toString() : null))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(directive.getPhysicianName()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(directive.getWitnessName()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            table.addCell(new Cell().add(new Paragraph(defaultText(directive.getDocumentLocation()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
        });

        document.add(table);
        document.add(new Paragraph("\n"));
    }

    private void addInsuranceSection(Document document, PatientRecordDTO dto, PdfFont font, Color headerColor, Color borderColor) {
        List<PatientInsuranceResponseDTO> insurances = dto.getInsurances();
        if (insurances == null || insurances.isEmpty()) {
            return;
        }

        document.add(new Paragraph("Insurance Coverage")
            .setFont(font)
            .setFontSize(16)
            .setBold()
            .setFontColor(headerColor)
            .setMarginTop(10));

        float[] insuranceWidths = {150F, 110F, 90F, 80F, 140F, 150F};
        Table insuranceTable = new Table(insuranceWidths).setWidth(UnitValue.createPercentValue(100));

        String[] insuranceHeaders = {"Provider", "Policy #", "Group #", "Primary", "Coverage", "Subscriber"};
        for (String header : insuranceHeaders) {
            insuranceTable.addHeaderCell(new Cell()
                .add(new Paragraph(header).setBold())
                .setBackgroundColor(headerColor)
                .setFontColor(ColorConstants.WHITE)
                .setBorder(Border.NO_BORDER));
        }

        insurances.forEach(insurance -> {
            insuranceTable.addCell(new Cell().add(new Paragraph(defaultText(insurance.getProviderName()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            insuranceTable.addCell(new Cell().add(new Paragraph(defaultText(insurance.getPolicyNumber()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            insuranceTable.addCell(new Cell().add(new Paragraph(defaultText(insurance.getGroupNumber()))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            String primaryLabel = insurance.isPrimary() ? "Primary" : "Secondary";
            insuranceTable.addCell(new Cell().add(new Paragraph(primaryLabel)).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            insuranceTable.addCell(new Cell().add(new Paragraph(defaultText(formatInsuranceWindow(insurance.getEffectiveDate(), insurance.getExpirationDate())))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
            insuranceTable.addCell(new Cell().add(new Paragraph(defaultText(formatSubscriber(insurance)))).setBorderBottom(new SolidBorder(borderColor, 0.5f)));
        });

        document.add(insuranceTable);
        document.add(new Paragraph("\n"));
    }

    private String formatInsuranceWindow(LocalDate effective, LocalDate expiration) {
        if (effective == null && expiration == null) {
            return null;
        }
        if (effective != null && expiration != null) {
            return effective + " → " + expiration;
        }
        if (effective != null) {
            return "Effective " + effective;
        }
        return "Expires " + expiration;
    }

    private String formatSubscriber(PatientInsuranceResponseDTO insurance) {
        if (insurance == null) {
            return null;
        }

        String name = insurance.getSubscriberName();
        if (name != null) {
            name = name.trim();
            if (name.isEmpty()) {
                name = null;
            }
        }

        String relationship = insurance.getSubscriberRelationship();
        if (relationship != null) {
            relationship = relationship.trim();
            if (relationship.isEmpty()) {
                relationship = null;
            }
        }

        if (name != null && relationship != null) {
            return name + " (" + relationship + ")";
        }
        if (name != null) {
            return name;
        }
        if (relationship != null) {
            return relationship;
        }
        return null;
    }

    private void addHospitalLogo(Document document) {
        try {
            String logoPath = "path/to/hospital_logo.png";
            Image logo = new Image(ImageDataFactory.create(logoPath))
                .scaleToFit(100, 100)
                .setHorizontalAlignment(HorizontalAlignment.CENTER);
            document.add(logo);
        } catch (Exception e) {
            log.warn("Hospital logo not found, skipping logo section.");
        }
    }

    private byte[] throwUnsupportedFormat(String format) {
        throw new IllegalArgumentException("Unsupported export format: " + format);
    }

    private void logAuditEvent(Patient patient, UUID fromHospitalId, UUID toHospitalId, PatientRecordDTO dto, PatientConsent consent) {
        try {
            Map<String, Object> auditPayload = new HashMap<>();
            auditPayload.put("patientId", patient.getId());
            auditPayload.put("fromHospitalId", fromHospitalId);
            auditPayload.put("toHospitalId", toHospitalId);
            auditPayload.put("consentId", consent.getId());
            auditPayload.put("consentExpiresAt", consent.getConsentExpiration());
            auditPayload.put("encounterCount", dto.getEncounters().size());
            auditPayload.put("treatmentCount", dto.getTreatments().size());
            auditPayload.put("labOrderCount", dto.getLabOrders().size());
            auditPayload.put("labResultCount", dto.getLabResults().size());
            auditPayload.put("prescriptionCount", dto.getPrescriptions().size());
            auditPayload.put("problemCount", dto.getProblems().size());
            auditPayload.put("surgicalHistoryCount", dto.getSurgicalHistory().size());
            auditPayload.put("advanceDirectiveCount", dto.getAdvanceDirectives().size());
            auditPayload.put("encounterHistoryCount", dto.getEncounterHistory().size());
            auditPayload.put("insuranceCount", dto.getInsurances().size());
            List<PatientAllergyResponseDTO> allergyEntries = dto.getAllergiesDetailed();
            auditPayload.put("allergyCount", allergyEntries.size());
            auditPayload.put("criticalAllergyCount", allergyEntries.stream().filter(this::isCriticalAllergy).count());
            auditPayload.put("activeAllergyCount", allergyEntries.stream().filter(this::isActiveAllergy).count());

            String rawDetails = objectMapper.writeValueAsString(auditPayload);
            String details = rawDetails.length() > 1000 ? rawDetails.substring(0, 997) + "..." : rawDetails;
            log.debug("Full audit details: {}", rawDetails);

            AuditEventLog auditLog = AuditEventLog.builder()
                .user(patient.getUser())
                .eventType(AuditEventType.RECORD_SHARE)
                .eventDescription(String.format(
                    "Shared patient record from hospital %s to %s using consent %s",
                    fromHospitalId,
                    toHospitalId,
                    consent.getId()
                ))
                .resourceId(patient.getId().toString())
                .entityType("PATIENT")
                .status(AuditStatus.SUCCESS)
                .details(details)
                .build();

            auditRepository.save(auditLog);

        } catch (Exception e) {
            log.warn("Failed to serialize audit log details: {}", e.getMessage());
        }
    }

}
