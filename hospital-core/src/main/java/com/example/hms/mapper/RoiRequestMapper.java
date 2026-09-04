package com.example.hms.mapper;

import com.example.hms.model.RoiRequest;
import com.example.hms.payload.dto.roi.RoiRequestResponseDTO;
import org.springframework.stereotype.Component;

/**
 * ROI request row → DTO (Tier 2 item 39b), extracted from the service per
 * the house convention so the mapping the three response surfaces share
 * (worklist, chart, /me) stays independently testable.
 *
 * <p>{@code patientName} comes from the row's own encrypted snapshot, not
 * a join — the request is a legal record that outlives the patient row
 * (V141 pattern), so it must render without one.
 */
@Component
public class RoiRequestMapper {

    public RoiRequestResponseDTO toDto(RoiRequest r) {
        if (r == null) {
            return null;
        }
        return RoiRequestResponseDTO.builder()
            .id(r.getId())
            .patientId(r.getPatientId())
            .patientName(r.getPatientName())
            .hospitalName(r.getHospital() != null ? r.getHospital().getName() : null)
            .requesterType(r.getRequesterType())
            .requesterName(r.getRequesterName())
            .requesterContact(r.getRequesterContact())
            .purpose(r.getPurpose())
            .scopeDescription(r.getScopeDescription())
            .status(r.getStatus())
            .requestedOn(r.getRequestedOn())
            .decidedAt(r.getDecidedAt())
            .decidedByName(r.getDecidedBy() != null ? r.getDecidedBy().getName() : null)
            .decisionNote(r.getDecisionNote())
            .build();
    }
}
