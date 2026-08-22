package com.example.hms.service.impl;

import com.example.hms.enums.InteractionSeverity;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DrugInteractionMapper;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.payload.dto.medication.DrugInteractionDTO;
import com.example.hms.repository.DrugInteractionRepository;
import com.example.hms.service.DrugInteractionAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DrugInteractionAdminServiceImpl implements DrugInteractionAdminService {

    private final DrugInteractionRepository repository;
    private final DrugInteractionMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<DrugInteractionDTO> list(InteractionSeverity severity, boolean activeOnly) {
        // activeOnly=false means "include retired rows", never "retired rows
        // only". The old severity branch passed activeOnly into the ACTIVE
        // column predicate, so ?severity=MAJOR&activeOnly=false returned only
        // the INACTIVE majors — the one query an admin needs to find a row to
        // reactivate silently excluded the active ones.
        List<DrugInteraction> rows = severity != null
            ? repository.findBySeverityOrderByDrug1NameAsc(severity)
            : repository.findAll();
        return rows.stream()
            .filter(row -> !activeOnly || row.isActive())
            .map(mapper::toDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DrugInteractionDTO> findForDrug(String drugCode) {
        return repository.findInteractionsForDrug(drugCode).stream().map(mapper::toDTO).toList();
    }

    @Override
    @Transactional
    public DrugInteractionDTO create(DrugInteractionDTO request) {
        validate(request);
        // Order-insensitive: an interaction is a property of the PAIR, and
        // storing A+B and B+A separately means one of them eventually drifts.
        // The lookup must ignore the active flag — a retired pair re-created
        // as a new row would leave two rows for one pair.
        repository.findAnyInteractionBetween(request.getDrug1Code(), request.getDrug2Code())
            .ifPresent(existing -> {
                throw new BusinessException(existing.isActive()
                    ? "An interaction between these two drugs already exists; update it instead."
                    : "An interaction between these two drugs exists but was retired; "
                        + "reactivate it instead of re-creating it.");
            });

        DrugInteraction entity = new DrugInteraction();
        apply(entity, request);
        entity.setActive(true);
        return mapper.toDTO(repository.save(entity));
    }

    @Override
    @Transactional
    public DrugInteractionDTO update(UUID id, DrugInteractionDTO request) {
        validate(request);
        DrugInteraction entity = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Drug interaction not found with ID: " + id));
        apply(entity, request);
        return mapper.toDTO(repository.save(entity));
    }

    @Override
    @Transactional
    public DrugInteractionDTO deactivate(UUID id) {
        DrugInteraction entity = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Drug interaction not found with ID: " + id));
        entity.setActive(false);
        return mapper.toDTO(repository.save(entity));
    }

    @Override
    @Transactional
    public DrugInteractionDTO reactivate(UUID id) {
        DrugInteraction entity = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Drug interaction not found with ID: " + id));
        entity.setActive(true);
        return mapper.toDTO(repository.save(entity));
    }

    private void validate(DrugInteractionDTO request) {
        if (request.getDrug1Code() == null || request.getDrug1Code().isBlank()
            || request.getDrug2Code() == null || request.getDrug2Code().isBlank()) {
            throw new BusinessException("Both drug codes are required.");
        }
        if (request.getDrug1Code().equalsIgnoreCase(request.getDrug2Code())) {
            throw new BusinessException("An interaction needs two different drugs.");
        }
        if (request.getSeverity() == null) {
            throw new BusinessException("Severity is required.");
        }
        if (request.getRecommendation() == null || request.getRecommendation().isBlank()) {
            // An alert that says "these interact" and nothing else is the kind
            // clinicians learn to dismiss without reading.
            throw new BusinessException(
                "A recommendation is required — an alert with no action is an alert that gets ignored.");
        }
    }

    private void apply(DrugInteraction entity, DrugInteractionDTO request) {
        entity.setDrug1Code(request.getDrug1Code());
        entity.setDrug1Name(request.getDrug1Name());
        entity.setDrug2Code(request.getDrug2Code());
        entity.setDrug2Name(request.getDrug2Name());
        entity.setSeverity(request.getSeverity());
        entity.setDescription(request.getDescription());
        entity.setRecommendation(request.getRecommendation());
        entity.setMechanism(request.getMechanism());
        entity.setClinicalEffects(request.getClinicalEffects());
        entity.setMonitoringParameters(request.getMonitoringParameters());
        entity.setMonitoringIntervalHours(request.getMonitoringIntervalHours());
        entity.setSourceDatabase(request.getSourceDatabase());
        entity.setEvidenceLevel(request.getEvidenceLevel());
        entity.setLiteratureReferences(request.getLiteratureReferences());
        // Curation note — where a pharmacist records why a row was added,
        // corrected or retired. Was unreachable through the write path.
        entity.setNotes(request.getNotes());

        // Derived from severity so the three action flags cannot contradict it —
        // a CONTRAINDICATED pair that does not require avoidance is a data bug
        // waiting to reach a prescriber.
        entity.setRequiresAvoidance(request.getSeverity() == InteractionSeverity.CONTRAINDICATED);
        entity.setRequiresDoseAdjustment(
            request.getSeverity() == InteractionSeverity.CONTRAINDICATED
                || request.getSeverity() == InteractionSeverity.MAJOR);
        entity.setRequiresMonitoring(true);
    }
}
