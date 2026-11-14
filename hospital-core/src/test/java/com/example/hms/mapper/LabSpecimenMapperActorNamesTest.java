package com.example.hms.mapper;

import com.example.hms.model.LabSpecimen;
import com.example.hms.model.User;
import com.example.hms.payload.dto.LabSpecimenResponseDTO;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lab requests drawer timeline: a specimen names who collected it and who
 * received it in the laboratory, next to the ids it already carried.
 */
class LabSpecimenMapperActorNamesTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final LabSpecimenMapper mapper = new LabSpecimenMapper(userRepository);

    private static User user(UUID id, String first, String last) {
        User u = User.builder().firstName(first).lastName(last).build();
        u.setId(id);
        return u;
    }

    @Test
    void namesTheCollectorAndTheReceiver() {
        UUID collectorId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        LabSpecimen specimen = LabSpecimen.builder().collectedById(collectorId).receivedById(receiverId).build();
        when(userRepository.findAllById(any())).thenReturn(List.of(
            user(collectorId, "Awa", "Traoré"), user(receiverId, " Issa ", "Kaboré")));

        LabSpecimenResponseDTO dto = mapper.toResponseDTO(specimen);

        assertThat(dto.getCollectedByName()).isEqualTo("Awa Traoré");
        assertThat(dto.getReceivedByName()).isEqualTo("Issa Kaboré");
    }

    @Test
    void aSpecimenNobodyStampedReadsNoUserAndNamesNobody() {
        LabSpecimenResponseDTO dto = mapper.toResponseDTO(LabSpecimen.builder().build());

        assertThat(dto.getCollectedByName()).isNull();
        assertThat(dto.getReceivedByName()).isNull();
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void aDeletedUserLeavesTheNameBlank() {
        UUID collectorId = UUID.randomUUID();
        when(userRepository.findAllById(any())).thenReturn(List.of());

        LabSpecimenResponseDTO dto = mapper.toResponseDTO(
            LabSpecimen.builder().collectedById(collectorId).build());

        assertThat(dto.getCollectedById()).isEqualTo(collectorId);
        assertThat(dto.getCollectedByName()).isNull();
    }
}
