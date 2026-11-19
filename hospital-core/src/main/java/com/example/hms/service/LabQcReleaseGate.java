package com.example.hms.service;

import com.example.hms.enums.LabQcEventLevel;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabQcEvent;
import com.example.hms.repository.LabQcEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * r63 D2304: the one question every path that releases a laboratory result
 * asks first: is the test's quality control currently rejected at the
 * laboratory that ran it?
 *
 * <p>For each control level, the latest run of the test at the running
 * hospital is read; if any level's latest run was rejected, results for that
 * test are not released until a passing run is recorded for that level or a
 * person releases one with a documented override reason. A test with no QC
 * run at all is not blocked: a laboratory that runs no QC for a test (or has
 * not started recording it in e-Keneya) is not stopped from working.
 *
 * <p>Automatic paths (autoverification, an analyzer's explicit normal) never
 * override: they leave the result on the release worklist for a person.
 * {@code LabQcReleaseGateGuardTest} pins that every class releasing a result
 * goes through here.
 */
@Component
@RequiredArgsConstructor
public class LabQcReleaseGate {

    private final LabQcEventRepository qcEventRepository;

    /** The rejected run that blocks release of this order's results, if any. */
    public Optional<LabQcEvent> blockingRun(LabOrder order) {
        if (order == null || order.getLabTestDefinition() == null) {
            return Optional.empty();
        }
        UUID hospitalId = runningHospitalId(order);
        UUID testDefinitionId = order.getLabTestDefinition().getId();
        if (hospitalId == null || testDefinitionId == null) {
            return Optional.empty();
        }
        for (LabQcEventLevel level : LabQcEventLevel.values()) {
            Optional<LabQcEvent> latest = qcEventRepository
                .findFirstByHospitalIdAndTestDefinition_IdAndQcLevelOrderByRecordedAtDescCreatedAtDesc(
                    hospitalId, testDefinitionId, level);
            if (latest.isPresent() && !latest.get().isPassed()) {
                return latest;
            }
        }
        return Optional.empty();
    }

    /** True when the order's results may not be released automatically. */
    public boolean isBlocked(LabOrder order) {
        return blockingRun(order).isPresent();
    }

    /** B1: the laboratory that ran the order: the performing hospital, else the ordering one. */
    static UUID runningHospitalId(LabOrder order) {
        UUID performing = com.example.hms.persistence.JpaProxyUtils.idOf(order.getPerformingHospital());
        if (performing != null) {
            return performing;
        }
        return order.getHospital() != null ? order.getHospital().getId() : null;
    }
}
