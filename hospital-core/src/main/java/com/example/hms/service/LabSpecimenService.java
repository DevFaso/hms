package com.example.hms.service;

import com.example.hms.payload.dto.LabSpecimenRequestDTO;
import com.example.hms.payload.dto.LabSpecimenResponseDTO;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface LabSpecimenService {

    /**
     * Create a new specimen record linked to the given lab order.
     * Generates an accession number and initial barcode value automatically.
     */
    LabSpecimenResponseDTO createSpecimen(LabSpecimenRequestDTO request, Locale locale);

    /** Retrieve a specimen by its ID. */
    LabSpecimenResponseDTO getSpecimenById(UUID specimenId, Locale locale);

    /** List all specimens for a given lab order. */
    List<LabSpecimenResponseDTO> getSpecimensByLabOrder(UUID labOrderId, Locale locale);

    /**
     * Mark a specimen as received at the lab, recording the receiving user and timestamp.
     */
    LabSpecimenResponseDTO receiveSpecimen(UUID specimenId, Locale locale);

    /**
     * QA D962: reject a collected, in-transit or received specimen with a
     * mandatory reason. The specimen becomes REJECTED (reason, who, when), the
     * order goes back to awaiting collection when no other live specimen
     * remains, and the ordering clinician is told.
     */
    LabSpecimenResponseDTO rejectSpecimen(UUID specimenId, String reason, Locale locale);
}
