package com.example.hms.mapper;

import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.LabSpecimenResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class LabSpecimenMapper {

    public LabSpecimenResponseDTO toResponseDTO(LabSpecimen specimen) {
        if (specimen == null) return null;
        return LabSpecimenResponseDTO.builder()
            .id(specimen.getId())
            .labOrderId(specimen.getLabOrder() != null ? specimen.getLabOrder().getId() : null)
            .accessionNumber(specimen.getAccessionNumber())
            .barcodeValue(specimen.getBarcodeValue())
            .specimenType(specimen.getSpecimenType())
            .collectedAt(specimen.getCollectedAt())
            .collectedById(specimen.getCollectedById())
            .receivedAt(specimen.getReceivedAt())
            .receivedById(specimen.getReceivedById())
            .currentLocation(specimen.getCurrentLocation())
            .status(specimen.getStatus() != null ? specimen.getStatus().name() : null)
            .notes(specimen.getNotes())
            .createdAt(specimen.getCreatedAt())
            .updatedAt(specimen.getUpdatedAt())
            .build();
    }
}
