package com.example.hms.service;

import com.example.hms.enums.LabQcEventLevel;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabQcEventMapper;
import com.example.hms.model.LabQcEvent;
import com.example.hms.model.LabQcTarget;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabQcEventRequestDTO;
import com.example.hms.payload.dto.LabQcEventResponseDTO;
import com.example.hms.payload.dto.LabQcSummaryDTO;
import com.example.hms.repository.LabQcEventRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LabQcEventServiceImpl implements LabQcEventService {

    private final LabQcEventRepository qcEventRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final LabQcEventMapper qcEventMapper;
    private final RoleValidator roleValidator;
    private final com.example.hms.repository.LabQcTargetRepository qcTargetRepository;
    private final com.example.hms.repository.UserRepository userRepository;

    @Override
    @Transactional
    public LabQcEventResponseDTO recordQcEvent(LabQcEventRequestDTO request, Locale locale) {
        // QA round 29 sweep (D1318 class): every missing value named in one answer.
        com.example.hms.exception.RequiredFields.check()
            .requireValue(request.getHospitalId(), "hospitalId", "lab.qc.hospital.required")
            .requireValue(request.getQcLevel(), "qcLevel", "lab.qc.levelRequired")
            .requireValue(request.getMeasuredValue(), "measuredValue", "lab.qc.measuredRequired")
            .orThrow();

        LabQcEventLevel level;
        // QA r5 tenancy sweep: a QC event is recorded at the caller's acting hospital; the body's id is a claim.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(request.getHospitalId(), "hospital.notFound", request.getHospitalId());
        try {
            level = LabQcEventLevel.valueOf(request.getQcLevel().trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new FieldBusinessException("qcLevel", "lab.qc.unknownLevel", String.valueOf(request.getQcLevel()));
        }

        LabTestDefinition testDef = null;
        LabQcTarget target = null;
        if (request.getTestDefinitionId() != null) {
            testDef = labTestDefinitionRepository.findById(request.getTestDefinitionId())
                .orElseThrow(() -> new ResourceNotFoundException("labtestdef.notfound"));
            // r63: the test must be one this hospital can see (its own or a global
            // definition); another hospital's definition is not found.
            requireDefinitionVisibleAt(testDef, request.getHospitalId());
            target = qcTargetRepository.findByHospitalIdAndTestDefinition_IdAndQcLevel(
                request.getHospitalId(), testDef.getId(), level).orElse(null);
        }

        // r63 D2304: a run is "latest" by its time, and the latest run per level decides
        // whether results are held, so a run is never dated in the future.
        if (request.getRecordedAt() != null
            && request.getRecordedAt().isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw new FieldBusinessException("recordedAt", "lab.qc.recordedAtFuture");
        }

        // r63 D2304: the expected value is the director's target mean when one is set.
        java.math.BigDecimal expectedValue = request.getExpectedValue();
        if (expectedValue == null && target != null) {
            expectedValue = target.getTargetMean();
        }
        com.example.hms.exception.RequiredFields.check()
            .requireValue(expectedValue, "expectedValue", "lab.qc.expectedRequired")
            .orThrow();

        java.math.BigDecimal targetMean = target != null ? target.getTargetMean() : null;
        java.math.BigDecimal targetSd = target != null ? target.getTargetSd() : null;
        String sdFlag = LabQcStatistics.sdFlag(request.getMeasuredValue(), targetMean, targetSd);

        // r63 D2304: the operator accepts or rejects the run. Without a decision
        // (an API caller predating it) the legacy +/-10% computation stands.
        // QA D4147 (r81, rule set LAB_QC_REJECTION): a control beyond 3 SD of the director's target is out by rule
        // (LabQcAcceptanceRule). It is never stored as accepted, whoever decides and whatever the
        // legacy computation says; the run is recorded as rejected and holds the test's results.
        com.example.hms.service.lab.LabQcAcceptanceRule.requireAcceptable(request.getAccepted(), sdFlag);
        boolean passed;
        if (request.getAccepted() != null) {
            passed = request.getAccepted();
        } else {
            double measured = request.getMeasuredValue().doubleValue();
            double expected = expectedValue.doubleValue();
            passed = expected != 0 && Math.abs((measured - expected) / expected) <= 0.10
                && !com.example.hms.service.lab.LabQcAcceptanceRule.outOfLimitsByRule(sdFlag);
        }

        UUID operatorId = roleValidator.getCurrentUserId();
        LabQcEvent event = LabQcEvent.builder()
            .hospitalId(request.getHospitalId())
            .analyzerId(trimToNull(request.getAnalyzerId()))
            .testDefinition(testDef)
            .qcLevel(level)
            .measuredValue(request.getMeasuredValue())
            .expectedValue(expectedValue)
            .passed(passed)
            .recordedAt(request.getRecordedAt() != null ? request.getRecordedAt() : LocalDateTime.now())
            .recordedById(operatorId)
            .recordedByDisplay(operatorDisplay(operatorId))
            .notes(request.getNotes())
            .lotNumber(trimToNull(request.getLotNumber()))
            .targetMean(targetMean)
            .targetSd(targetSd)
            .sdFlag(sdFlag)
            .build();

        return qcEventMapper.toResponseDTO(qcEventRepository.save(event));
    }

    /** r63: a definition is visible at a hospital when it is that hospital's or global. */
    static void requireDefinitionVisibleAt(LabTestDefinition definition, UUID hospitalId) {
        UUID owner = definition.getHospital() != null ? definition.getHospital().getId() : null;
        if (owner != null && hospitalId != null && !owner.equals(hospitalId)) {
            throw new ResourceNotFoundException("labtestdef.notfound");
        }
    }

    private String operatorDisplay(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
            .map(u -> {
                String full = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                    + (u.getLastName() == null ? "" : u.getLastName())).trim();
                return full.isEmpty() ? u.getUsername() : full;
            })
            .orElse(null);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    @Transactional(readOnly = true)
    public LabQcEventResponseDTO getQcEventById(UUID id, Locale locale) {
        // r63: a QC event is read at its own hospital only (a super-admin in global view reads any).
        UUID scopedHospitalId = roleValidator.requireActiveHospitalId();
        LabQcEvent event = (scopedHospitalId == null
                ? qcEventRepository.findById(id)
                : qcEventRepository.findByIdAndHospitalId(id, scopedHospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("labqcevent.notfound"));
        return qcEventMapper.toResponseDTO(event);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LabQcEventResponseDTO> getQcEventsByHospital(UUID hospitalId, Pageable pageable, Locale locale) {
        // r63: the hospital filter is a claim; a pinned caller lists its own hospital only.
        UUID scopedHospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null) {
            com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(
                hospitalId, "hospital.notFound", hospitalId);
            scopedHospitalId = hospitalId;
        }
        if (scopedHospitalId == null) {
            // Super-admin: return all
            return qcEventRepository.findAll(pageable).map(qcEventMapper::toResponseDTO);
        }
        return qcEventRepository.findByHospitalId(scopedHospitalId, pageable)
            .map(qcEventMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LabQcEventResponseDTO> getQcEventsByDefinition(UUID testDefinitionId, Pageable pageable, Locale locale) {
        UUID scopedHospitalId = roleValidator.requireActiveHospitalId();
        if (scopedHospitalId == null) {
            // SUPER_ADMIN: no hospital scoping — return across all hospitals
            return qcEventRepository.findByTestDefinitionId(testDefinitionId, pageable)
                .map(qcEventMapper::toResponseDTO);
        }
        return qcEventRepository.findByTestDefinitionIdAndHospitalId(testDefinitionId, scopedHospitalId, pageable)
            .map(qcEventMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabQcSummaryDTO> getQcSummary(Locale locale) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        List<Object[]> rows = (hospitalId != null)
            ? qcEventRepository.findQcSummaryByHospitalId(hospitalId)
            : qcEventRepository.findQcSummaryAll();

        return rows.stream().map(row -> {
            UUID testDefId   = (UUID) row[0];
            String testName  = (String) row[1];
            long total       = ((Number) row[2]).longValue();
            long passed      = ((Number) row[3]).longValue();
            long failed      = ((Number) row[4]).longValue();
            LocalDateTime lastDate = (LocalDateTime) row[5];
            double passRate  = total > 0 ? (double) passed / total * 100.0 : 0.0;

            return LabQcSummaryDTO.builder()
                .testDefinitionId(testDefId)
                .testName(testName)
                .totalEvents(total)
                .passedEvents(passed)
                .failedEvents(failed)
                .passRate(Math.round(passRate * 100.0) / 100.0)
                .lastEventDate(lastDate)
                .build();
        }).toList();
    }
}
