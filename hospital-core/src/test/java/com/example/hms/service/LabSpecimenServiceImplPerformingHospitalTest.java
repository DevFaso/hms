package com.example.hms.service;

import com.example.hms.enums.LabSpecimenStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabSpecimenMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.LabSpecimenRequestDTO;
import com.example.hms.payload.dto.LabSpecimenResponseDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit gap B1 on specimens: the performing laboratory collects and receives them; a third hospital gets 404. */
@ExtendWith(MockitoExtension.class)
class LabSpecimenServiceImplPerformingHospitalTest {

    @Mock private LabSpecimenRepository labSpecimenRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabSpecimenMapper labSpecimenMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private InstrumentOutboxService instrumentOutboxService;

    @InjectMocks
    private LabSpecimenServiceImpl service;

    private Hospital ordering;
    private Hospital performing;
    private Hospital third;
    private LabOrder order;
    private LabSpecimen specimen;
    private final LabSpecimenResponseDTO mapped = LabSpecimenResponseDTO.builder().id(UUID.randomUUID()).build();

    @BeforeEach
    void setUp() {
        ordering = hospital();
        performing = hospital();
        third = hospital();
        order = LabOrder.builder().hospital(ordering).performingHospital(performing).build();
        order.setId(UUID.randomUUID());
        specimen = LabSpecimen.builder().labOrder(order).status(LabSpecimenStatus.COLLECTED).build();
        specimen.setId(UUID.randomUUID());
    }

    @Test
    void performingLaboratoryCanCollectASpecimen() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.existsByAccessionNumber(any())).thenReturn(false);
        when(labSpecimenRepository.save(any())).thenReturn(specimen);
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(mapped);

        LabSpecimenResponseDTO result = service.createSpecimen(
            LabSpecimenRequestDTO.builder().labOrderId(order.getId()).specimenType("BLOOD").build(), Locale.ENGLISH);

        assertThat(result).isSameAs(mapped);
    }

    @Test
    void thirdHospitalCannotCollectASpecimen() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(third.getId());

        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder().labOrderId(order.getId()).specimenType("BLOOD").build();
        assertThatThrownBy(() -> service.createSpecimen(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(labSpecimenRepository, never()).save(any());
    }

    @Test
    void orderingHospitalStillListsTheSpecimensOfAnOrderItSentOut() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(labSpecimenRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of(specimen));
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(mapped);

        assertThat(service.getSpecimensByLabOrder(order.getId(), Locale.ENGLISH)).containsExactly(mapped);
    }

    @Test
    void performingLaboratoryListsTheSpecimens() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(labSpecimenRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of(specimen));
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(mapped);

        assertThat(service.getSpecimensByLabOrder(order.getId(), Locale.ENGLISH)).containsExactly(mapped);
    }

    @Test
    void thirdHospitalGets404OnTheSpecimenList() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(third.getId());

        UUID id = order.getId();
        assertThatThrownBy(() -> service.getSpecimensByLabOrder(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void performingLaboratoryReadsAndReceivesASpecimen() {
        when(labSpecimenRepository.findById(specimen.getId())).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(mapped);

        assertThat(service.getSpecimenById(specimen.getId(), Locale.ENGLISH)).isSameAs(mapped);

        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.save(specimen)).thenReturn(specimen);

        assertThat(service.receiveSpecimen(specimen.getId(), Locale.ENGLISH)).isSameAs(mapped);
        assertThat(specimen.getStatus()).isEqualTo(LabSpecimenStatus.RECEIVED);
        verify(instrumentOutboxService).enqueueSpecimenReceived(specimen);
    }

    @Test
    void thirdHospitalGets404OnReadAndReceive() {
        when(labSpecimenRepository.findById(specimen.getId())).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(third.getId());

        UUID id = specimen.getId();
        assertThatThrownBy(() -> service.getSpecimenById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.receiveSpecimen(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(specimen.getStatus()).isEqualTo(LabSpecimenStatus.COLLECTED);
    }

    private static Hospital hospital() {
        Hospital hospital = Hospital.builder().build();
        hospital.setId(UUID.randomUUID());
        return hospital;
    }
}
