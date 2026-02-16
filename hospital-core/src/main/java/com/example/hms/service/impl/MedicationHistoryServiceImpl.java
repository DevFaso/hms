package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PharmacyFillMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.model.medication.PharmacyFill;
import com.example.hms.payload.dto.medication.DrugInteractionDTO;
import com.example.hms.payload.dto.medication.MedicationTimelineEntryDTO;
import com.example.hms.payload.dto.medication.MedicationTimelineResponseDTO;
import com.example.hms.payload.dto.medication.PharmacyFillRequestDTO;
import com.example.hms.payload.dto.medication.PharmacyFillResponseDTO;
import com.example.hms.repository.DrugInteractionRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PharmacyFillRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.MedicationHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MedicationHistoryServiceImpl implements MedicationHistoryService {
    private static final String NON_DIGIT_REGEX = "\\D";


    private final PharmacyFillRepository pharmacyFillRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final DrugInteractionRepository drugInteractionRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final PharmacyFillMapper pharmacyFillMapper;

    @Override
    @Transactional(readOnly = true)
    public MedicationTimelineResponseDTO getMedicationTimeline(
            UUID patientId,
            UUID hospitalId,
            LocalDate startDate,
            LocalDate endDate,
            Locale locale) {

        log.info("Generating medication timeline for patient: {}, hospital: {}", patientId, hospitalId);

        // Validate patient and hospital exist
        patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));
        hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + hospitalId));

        // Fetch prescriptions and pharmacy fills
        List<Prescription> prescriptions = fetchPrescriptions(patientId, hospitalId, startDate, endDate);
        List<PharmacyFill> pharmacyFills = fetchPharmacyFills(patientId, hospitalId, startDate, endDate);

        log.debug("Found {} prescriptions and {} pharmacy fills", prescriptions.size(), pharmacyFills.size());

        // Convert to timeline entries
        List<MedicationTimelineEntryDTO> timeline = new ArrayList<>();
        timeline.addAll(convertPrescriptionsToTimelineEntries(prescriptions));
        timeline.addAll(convertFillsToTimelineEntries(pharmacyFills));

        // Sort by start date
        timeline.sort((e1, e2) -> {
            if (e1.getStartDate() == null && e2.getStartDate() == null) return 0;
            if (e1.getStartDate() == null) return 1;
            if (e2.getStartDate() == null) return -1;
            return e1.getStartDate().compareTo(e2.getStartDate());
        });

        // Run overlap detection
        detectOverlaps(timeline);

        // Extract drug codes for interaction checking
        List<String> drugCodes = extractDrugCodes(timeline);
        
        // Check interactions
        List<DrugInteraction> interactions = drugInteractionRepository.findInteractionsAmongDrugs(drugCodes);
        flagInteractions(timeline, interactions);

        // Convert interactions to DTOs
        List<DrugInteractionDTO> interactionDTOs = interactions.stream()
            .map(this::convertInteractionToDTO)
            .toList();

        // Calculate statistics
        int total = timeline.size();
        int active = countActiveMedications(timeline);
        int controlled = countControlledSubstances(timeline);
        int withOverlaps = (int) timeline.stream().filter(MedicationTimelineEntryDTO::isHasOverlap).count();
        int withInteractions = (int) timeline.stream().filter(MedicationTimelineEntryDTO::isHasInteraction).count();
        int concurrent = countConcurrentMedications(timeline);
        boolean polypharmacy = concurrent >= 5;

        // Build warnings
        List<String> warnings = buildWarnings(withInteractions, withOverlaps, polypharmacy, concurrent);

        log.info("Timeline generated: {} total meds, {} active, {} interactions, {} overlaps",
            total, active, withInteractions, withOverlaps);

        return MedicationTimelineResponseDTO.builder()
            .timeline(timeline)
            .totalMedications(total)
            .activeMedications(active)
            .controlledSubstances(controlled)
            .medicationsWithOverlaps(withOverlaps)
            .medicationsWithInteractions(withInteractions)
            .detectedInteractions(interactionDTOs)
            .polypharmacyDetected(polypharmacy)
            .concurrentMedicationsCount(concurrent)
            .warnings(warnings)
            .build();
    }

    @Override
    @Transactional
    public PharmacyFillResponseDTO createPharmacyFill(PharmacyFillRequestDTO request, Locale locale) {
        log.info("Creating pharmacy fill for patient: {}", request.getPatientId());

        // Validate required entities
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + request.getPatientId()));
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + request.getHospitalId()));

        // Optional prescription link
        Prescription prescription = null;
        if (request.getPrescriptionId() != null) {
            prescription = prescriptionRepository.findById(request.getPrescriptionId())
                .orElse(null); // Don't fail if prescription not found, just leave unlinked
        }

        // Convert and save
        PharmacyFill fill = pharmacyFillMapper.toEntity(request, patient, hospital, prescription);
        PharmacyFill saved = pharmacyFillRepository.save(fill);

        log.info("Pharmacy fill created with ID: {}", saved.getId());

        return pharmacyFillMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PharmacyFillResponseDTO getPharmacyFillById(UUID fillId, Locale locale) {
        PharmacyFill fill = pharmacyFillRepository.findById(fillId)
            .orElseThrow(() -> new ResourceNotFoundException("Pharmacy fill not found with ID: " + fillId));
        return pharmacyFillMapper.toResponseDTO(fill);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PharmacyFillResponseDTO> getPharmacyFillsByPatient(UUID patientId, UUID hospitalId, Locale locale) {
        List<PharmacyFill> fills = pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId);
        return fills.stream()
            .map(pharmacyFillMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public PharmacyFillResponseDTO updatePharmacyFill(UUID fillId, PharmacyFillRequestDTO request, Locale locale) {
        log.info("Updating pharmacy fill: {}", fillId);

        PharmacyFill fill = pharmacyFillRepository.findById(fillId)
            .orElseThrow(() -> new ResourceNotFoundException("Pharmacy fill not found with ID: " + fillId));

        pharmacyFillMapper.updateEntity(fill, request);
        PharmacyFill updated = pharmacyFillRepository.save(fill);

        log.info("Pharmacy fill updated: {}", fillId);

        return pharmacyFillMapper.toResponseDTO(updated);
    }

    @Override
    @Transactional
    public void deletePharmacyFill(UUID fillId, Locale locale) {
        log.info("Deleting pharmacy fill: {}", fillId);

        if (!pharmacyFillRepository.existsById(fillId)) {
            throw new ResourceNotFoundException("Pharmacy fill not found with ID: " + fillId);
        }

        pharmacyFillRepository.deleteById(fillId);
        log.info("Pharmacy fill deleted: {}", fillId);
    }

    // ========== Helper Methods ==========

    private List<Prescription> fetchPrescriptions(UUID patientId, UUID hospitalId, LocalDate startDate, LocalDate endDate) {
        List<Prescription> prescriptions = prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId);
        
        // Filter by date range if provided
        if (startDate != null || endDate != null) {
            prescriptions = prescriptions.stream()
                .filter(p -> isWithinDateRange(p.getCreatedAt().toLocalDate(), startDate, endDate))
                .toList();
        }
        
        return prescriptions;
    }

    private List<PharmacyFill> fetchPharmacyFills(UUID patientId, UUID hospitalId, LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return pharmacyFillRepository.findByPatientAndDateRange(patientId, startDate, endDate);
        } else {
            return pharmacyFillRepository.findByPatient_IdAndHospital_IdOrderByFillDateDesc(patientId, hospitalId);
        }
    }

    private boolean isWithinDateRange(LocalDate date, LocalDate startDate, LocalDate endDate) {
        if (startDate != null && date.isBefore(startDate)) {
            return false;
        }
        return endDate == null || !date.isAfter(endDate);
    }

    private List<MedicationTimelineEntryDTO> convertPrescriptionsToTimelineEntries(List<Prescription> prescriptions) {
        return prescriptions.stream()
            .map(this::prescriptionToTimelineEntry)
            .toList();
    }

    private MedicationTimelineEntryDTO prescriptionToTimelineEntry(Prescription rx) {
        LocalDate startDate = rx.getCreatedAt() != null ? rx.getCreatedAt().toLocalDate() : null;
        LocalDate endDate = calculateEndDate(startDate, rx.getDuration());

        return MedicationTimelineEntryDTO.builder()
            .entryId("RX-" + rx.getId())
            .entryType("PRESCRIPTION")
            .medicationName(rx.getMedicationDisplayName() != null ? rx.getMedicationDisplayName() : rx.getMedicationName())
            .medicationCode(rx.getMedicationCode())
            .strength(rx.getDosage())
            .dosageForm(null) // Not tracked in Prescription
            .startDate(startDate)
            .endDate(endDate)
            .daysSupply(null) // Not directly tracked
            .duration(rx.getDuration())
            .dosage(rx.getDosage())
            .frequency(rx.getFrequency())
            .route(rx.getRoute())
            .quantityDispensed(rx.getQuantity())
            .quantityUnit(rx.getQuantityUnit())
            .source("Internal Prescription")
            .prescriberName(rx.getStaff() != null ? rx.getStaff().getFullName() : null)
            .pharmacyName(rx.getPharmacyName())
            .status(rx.getStatus() != null ? rx.getStatus().name() : null)
            .controlledSubstance(rx.isControlledSubstance())
            .hasOverlap(false)
            .overlappingWith(new ArrayList<>())
            .hasInteraction(false)
            .interactingWith(new ArrayList<>())
            .prescriptionId(rx.getId())
            .documentedAt(rx.getCreatedAt())
            .build();
    }

    private List<MedicationTimelineEntryDTO> convertFillsToTimelineEntries(List<PharmacyFill> fills) {
        return fills.stream()
            .map(this::fillToTimelineEntry)
            .toList();
    }

    private MedicationTimelineEntryDTO fillToTimelineEntry(PharmacyFill fill) {
        LocalDate endDate = null;
        if (fill.getFillDate() != null && fill.getDaysSupply() != null) {
            endDate = fill.getFillDate().plusDays(fill.getDaysSupply());
        }

        String source = "Pharmacy Fill";
        if (fill.getSourceSystem() != null) {
            source = fill.getSourceSystem();
        }

        return MedicationTimelineEntryDTO.builder()
            .entryId("FILL-" + fill.getId())
            .entryType("PHARMACY_FILL")
            .medicationName(fill.getMedicationName())
            .medicationCode(fill.getNdcCode() != null ? fill.getNdcCode() : fill.getRxnormCode())
            .strength(fill.getStrength())
            .dosageForm(fill.getDosageForm())
            .startDate(fill.getFillDate())
            .endDate(endDate)
            .daysSupply(fill.getDaysSupply())
            .duration(fill.getDaysSupply() != null ? fill.getDaysSupply() + " days" : null)
            .dosage(fill.getStrength())
            .frequency(null) // Extracted from directions if needed
            .route(null) // Not in PharmacyFill
            .quantityDispensed(fill.getQuantityDispensed())
            .quantityUnit(fill.getQuantityUnit())
            .source(source)
            .prescriberName(fill.getPrescriberName())
            .pharmacyName(fill.getPharmacyName())
            .status("DISPENSED")
            .controlledSubstance(fill.isControlledSubstance())
            .hasOverlap(false)
            .overlappingWith(new ArrayList<>())
            .hasInteraction(false)
            .interactingWith(new ArrayList<>())
            .pharmacyFillId(fill.getId())
            .documentedAt(fill.getCreatedAt())
            .build();
    }

    /**
     * Detect overlapping medications in the timeline.
     * Two medications overlap if their date ranges intersect AND they are the same/similar medication.
     */
    private void detectOverlaps(List<MedicationTimelineEntryDTO> timeline) {
        for (int i = 0; i < timeline.size(); i++) {
            MedicationTimelineEntryDTO med1 = timeline.get(i);
            
            for (int j = i + 1; j < timeline.size(); j++) {
                MedicationTimelineEntryDTO med2 = timeline.get(j);
                
                if (datesOverlap(med1.getStartDate(), med1.getEndDate(), med2.getStartDate(), med2.getEndDate())
                        && isSameMedication(med1, med2)) {
                        markOverlap(med1, med2);
                }
            }
        }
    }

    private void markOverlap(MedicationTimelineEntryDTO med1, MedicationTimelineEntryDTO med2) {
        int overlapDays = calculateOverlapDays(
            med1.getStartDate(), med1.getEndDate(),
            med2.getStartDate(), med2.getEndDate()
        );
        
        med1.setHasOverlap(true);
        med1.getOverlappingWith().add(med2.getEntryId());
        if (med1.getOverlapDays() == null || overlapDays > med1.getOverlapDays()) {
            med1.setOverlapDays(overlapDays);
        }
        
        med2.setHasOverlap(true);
        med2.getOverlappingWith().add(med1.getEntryId());
        if (med2.getOverlapDays() == null || overlapDays > med2.getOverlapDays()) {
            med2.setOverlapDays(overlapDays);
        }
    }
    private boolean datesOverlap(LocalDate start1, LocalDate end1, LocalDate start2, LocalDate end2) {
        if (start1 == null || start2 == null) {
            return false; // Can't determine overlap without start dates
        }
        
        // Use current date as end if not specified
        LocalDate effectiveEnd1 = end1 != null ? end1 : LocalDate.now();
        LocalDate effectiveEnd2 = end2 != null ? end2 : LocalDate.now();
        
        // Overlap exists if: start1 <= end2 AND end1 >= start2
        return !start1.isAfter(effectiveEnd2) && !effectiveEnd1.isBefore(start2);
    }

    private boolean isSameMedication(MedicationTimelineEntryDTO med1, MedicationTimelineEntryDTO med2) {
        // First try code-based matching (most accurate)
        if (med1.getMedicationCode() != null && med2.getMedicationCode() != null) {
            return med1.getMedicationCode().equalsIgnoreCase(med2.getMedicationCode());
        }
        
        // Fall back to name similarity
        if (med1.getMedicationName() != null && med2.getMedicationName() != null) {
            String name1 = med1.getMedicationName().toLowerCase();
            String name2 = med2.getMedicationName().toLowerCase();
            
            // Simple substring matching (can be enhanced with fuzzy matching)
            return name1.contains(name2) || name2.contains(name1);
        }
        
        return false;
    }

    private int calculateOverlapDays(LocalDate start1, LocalDate end1, LocalDate start2, LocalDate end2) {
        LocalDate effectiveEnd1 = end1 != null ? end1 : LocalDate.now();
        LocalDate effectiveEnd2 = end2 != null ? end2 : LocalDate.now();
        
        LocalDate overlapStart = start1.isAfter(start2) ? start1 : start2;
        LocalDate overlapEnd = effectiveEnd1.isBefore(effectiveEnd2) ? effectiveEnd1 : effectiveEnd2;
        
        return (int) ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
    }

    private List<String> extractDrugCodes(List<MedicationTimelineEntryDTO> timeline) {
        return timeline.stream()
            .map(MedicationTimelineEntryDTO::getMedicationCode)
            .filter(code -> code != null && !code.isEmpty())
            .distinct()
            .toList();
    }

    private void flagInteractions(List<MedicationTimelineEntryDTO> timeline, List<DrugInteraction> interactions) {
        if (interactions.isEmpty()) {
            return;
        }
        
        Map<String, Set<String>> codeToNames = new HashMap<>();
        for (MedicationTimelineEntryDTO entry : timeline) {
            if (entry.getMedicationCode() != null) {
                codeToNames.computeIfAbsent(entry.getMedicationCode(), k -> new HashSet<>())
                    .add(entry.getMedicationName());
            }
        }
        
        for (DrugInteraction interaction : interactions) {
            for (MedicationTimelineEntryDTO entry : timeline) {
                flagEntryInteraction(entry, interaction, codeToNames);
            }
        }
    }

    private void flagEntryInteraction(MedicationTimelineEntryDTO entry, DrugInteraction interaction, Map<String, Set<String>> codeToNames) {
        if (entry.getMedicationCode() == null) return;
        
        if (entry.getMedicationCode().equals(interaction.getDrug1Code())) {
            entry.setHasInteraction(true);
            addInteractingNames(entry, codeToNames.get(interaction.getDrug2Code()), interaction.getDrug2Name());
        } else if (entry.getMedicationCode().equals(interaction.getDrug2Code())) {
            entry.setHasInteraction(true);
            addInteractingNames(entry, codeToNames.get(interaction.getDrug1Code()), interaction.getDrug1Name());
        }
    }

    private void addInteractingNames(MedicationTimelineEntryDTO entry, Set<String> names, String fallbackName) {
        if (names != null) {
            entry.getInteractingWith().addAll(names);
        } else {
            entry.getInteractingWith().add(fallbackName);
        }
    }

    private DrugInteractionDTO convertInteractionToDTO(DrugInteraction interaction) {
        return DrugInteractionDTO.builder()
            .id(interaction.getId())
            .drug1Code(interaction.getDrug1Code())
            .drug1Name(interaction.getDrug1Name())
            .drug2Code(interaction.getDrug2Code())
            .drug2Name(interaction.getDrug2Name())
            .severity(interaction.getSeverity())
            .description(interaction.getDescription())
            .recommendation(interaction.getRecommendation())
            .mechanism(interaction.getMechanism())
            .clinicalEffects(interaction.getClinicalEffects())
            .requiresAvoidance(interaction.isRequiresAvoidance())
            .requiresDoseAdjustment(interaction.isRequiresDoseAdjustment())
            .requiresMonitoring(interaction.isRequiresMonitoring())
            .monitoringParameters(interaction.getMonitoringParameters())
            .monitoringIntervalHours(interaction.getMonitoringIntervalHours())
            .sourceDatabase(interaction.getSourceDatabase())
            .evidenceLevel(interaction.getEvidenceLevel())
            .literatureReferences(interaction.getLiteratureReferences())
            .active(interaction.isActive())
            .build();
    }

    private int countActiveMedications(List<MedicationTimelineEntryDTO> timeline) {
        LocalDate today = LocalDate.now();
        return (int) timeline.stream()
            .filter(entry -> {
                if (entry.getStartDate() == null) return false;
                if (entry.getStartDate().isAfter(today)) return false;
                if (entry.getEndDate() != null && entry.getEndDate().isBefore(today)) return false;
                return "ACTIVE".equals(entry.getStatus()) || "DISPENSED".equals(entry.getStatus());
            })
            .count();
    }

    private int countControlledSubstances(List<MedicationTimelineEntryDTO> timeline) {
        return (int) timeline.stream()
            .filter(MedicationTimelineEntryDTO::isControlledSubstance)
            .count();
    }

    private int countConcurrentMedications(List<MedicationTimelineEntryDTO> timeline) {
        LocalDate today = LocalDate.now();

        Set<String> concurrentMeds = new HashSet<>();
        for (MedicationTimelineEntryDTO entry : timeline) {
            if (isActiveOnDate(entry, today)) {
                String identifier = entry.getMedicationCode() != null ?
                    entry.getMedicationCode() : entry.getMedicationName();
                concurrentMeds.add(identifier);
            }
        }

        return concurrentMeds.size();
    }

    private boolean isActiveOnDate(MedicationTimelineEntryDTO entry, LocalDate date) {
        if (entry.getStartDate() == null || entry.getStartDate().isAfter(date)) {
            return false;
        }
        LocalDate endDate = entry.getEndDate() != null ? entry.getEndDate() : date.plusMonths(3);
        return !endDate.isBefore(date);
    }

    private List<String> buildWarnings(int interactions, int overlaps, boolean polypharmacy, int concurrent) {
        List<String> warnings = new ArrayList<>();
        
        if (interactions > 0) {
            warnings.add("Patient has " + interactions + " drug-drug interaction" + (interactions > 1 ? "s" : "") + " detected");
        }
        
        if (overlaps > 0) {
            warnings.add(overlaps + " medication" + (overlaps > 1 ? "s have" : " has") + " overlapping therapy");
        }
        
        if (polypharmacy) {
            warnings.add("Polypharmacy detected: Patient is taking " + concurrent + " concurrent medications");
        }
        
        return warnings;
    }

    private LocalDate calculateEndDate(LocalDate startDate, String duration) {
        if (startDate == null || duration == null) {
            return null;
        }
        
        try {
            // Parse duration like "10 days", "2 weeks", "1 month"
            String durationLower = duration.toLowerCase().trim();
            
            if (durationLower.contains("day")) {
                int days = Integer.parseInt(durationLower.replaceAll(NON_DIGIT_REGEX, ""));
                return startDate.plusDays(days);
            } else if (durationLower.contains("week")) {
                int weeks = Integer.parseInt(durationLower.replaceAll(NON_DIGIT_REGEX, ""));
                return startDate.plusWeeks(weeks);
            } else if (durationLower.contains("month")) {
                int months = Integer.parseInt(durationLower.replaceAll(NON_DIGIT_REGEX, ""));
                return startDate.plusMonths(months);
            }
        } catch (RuntimeException e) {
            log.debug("Could not parse duration '{}'; returning null for end date calculation.", duration);
        }
        
        return null;
    }
}
