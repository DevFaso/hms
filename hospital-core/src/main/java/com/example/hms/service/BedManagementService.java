package com.example.hms.service;

import com.example.hms.payload.dto.bed.BedRequestDTO;
import com.example.hms.payload.dto.bed.BedResponseDTO;
import com.example.hms.payload.dto.bed.BedStatusUpdateRequestDTO;
import com.example.hms.payload.dto.bed.WardRequestDTO;
import com.example.hms.payload.dto.bed.WardResponseDTO;

import java.util.List;
import java.util.UUID;

/** Ward and bed administration (P0 #4 — the first writers for the V25 schema). */
public interface BedManagementService {

    List<WardResponseDTO> getWards(boolean includeInactive);

    WardResponseDTO createWard(WardRequestDTO request);

    WardResponseDTO updateWard(UUID wardId, WardRequestDTO request);

    void deleteWard(UUID wardId);

    List<BedResponseDTO> getBedsByWard(UUID wardId);

    /** Assignable beds (active + AVAILABLE) across the active hospital — feeds the picker. */
    List<BedResponseDTO> getAvailableBeds();

    /**
     * QA D609 (D431 in the picker): the assignable beds this patient may take —
     * a ward reserved for one population (maternity, paediatrics, NICU) is left
     * out for a patient outside it. {@code null} lists every assignable bed.
     */
    List<BedResponseDTO> getAvailableBeds(UUID patientId);

    BedResponseDTO createBed(UUID wardId, BedRequestDTO request);

    BedResponseDTO updateBed(UUID bedId, BedRequestDTO request);

    BedResponseDTO updateBedStatus(UUID bedId, BedStatusUpdateRequestDTO request);

    void deleteBed(UUID bedId);
}
