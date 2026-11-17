package com.example.hms.mapper;

import com.example.hms.model.LabSpecimen;
import com.example.hms.model.User;
import com.example.hms.payload.dto.LabSpecimenResponseDTO;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LabSpecimenMapper {

    /** Names the users who collected and received the specimen (lab requests drawer timeline). */
    private final UserRepository userRepository;

    public LabSpecimenResponseDTO toResponseDTO(LabSpecimen specimen) {
        if (specimen == null) return null;
        Map<UUID, String> names = userNames(specimen.getCollectedById(), specimen.getReceivedById());
        return LabSpecimenResponseDTO.builder()
            .id(specimen.getId())
            .labOrderId(specimen.getLabOrder() != null ? specimen.getLabOrder().getId() : null)
            .patientId(specimen.getLabOrder() != null && specimen.getLabOrder().getPatient() != null
                ? specimen.getLabOrder().getPatient().getId() : null)
            .accessionNumber(specimen.getAccessionNumber())
            .barcodeValue(specimen.getBarcodeValue())
            .specimenType(specimen.getSpecimenType())
            .collectedAt(specimen.getCollectedAt())
            .collectedById(specimen.getCollectedById())
            .collectedByName(names.get(specimen.getCollectedById()))
            .receivedAt(specimen.getReceivedAt())
            .receivedById(specimen.getReceivedById())
            .receivedByName(names.get(specimen.getReceivedById()))
            .currentLocation(specimen.getCurrentLocation())
            .status(specimen.getStatus() != null ? specimen.getStatus().name() : null)
            .notes(specimen.getNotes())
            .rejectionReason(specimen.getRejectionReason())
            .rejectedAt(specimen.getRejectedAt())
            .rejectedById(specimen.getRejectedById())
            .createdAt(specimen.getCreatedAt())
            .updatedAt(specimen.getUpdatedAt())
            .build();
    }

    /** The display names of {@code ids} (nulls skipped), in one read; none read when there is none. */
    private Map<UUID, String> userNames(UUID... ids) {
        Set<UUID> wanted = new HashSet<>();
        for (UUID id : ids) {
            if (id != null) wanted.add(id);
        }
        Map<UUID, String> names = new HashMap<>();
        if (wanted.isEmpty()) {
            return names;
        }
        for (User user : userRepository.findAllById(wanted)) {
            String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
            String last = user.getLastName() != null ? user.getLastName().trim() : "";
            String name = (first + " " + last).trim();
            if (!name.isEmpty()) names.put(user.getId(), name);
        }
        return names;
    }
}
