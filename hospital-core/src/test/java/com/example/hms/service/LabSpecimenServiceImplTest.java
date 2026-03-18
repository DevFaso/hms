package com.example.hms.service;

import com.example.hms.enums.LabSpecimenStatus;
import com.example.hms.exception.BusinessException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabSpecimenServiceImplTest {

    @Mock private LabSpecimenRepository labSpecimenRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabSpecimenMapper labSpecimenMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private InstrumentOutboxService instrumentOutboxService;

    @InjectMocks
    private LabSpecimenServiceImpl service;

    private UUID hospitalId;
    private UUID labOrderId;
    private UUID specimenId;
    private Hospital hospital;
    private LabOrder labOrder;
    private LabSpecimenResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        hospitalId  = UUID.randomUUID();
        labOrderId  = UUID.randomUUID();
        specimenId  = UUID.randomUUID();
        hospital    = Hospital.builder().build();
        hospital.setId(hospitalId);
        labOrder    = LabOrder.builder().hospital(hospital).build();
        labOrder.setId(labOrderId);
        responseDTO = LabSpecimenResponseDTO.builder().id(specimenId).build();
    }

    // ── createSpecimen ────────────────────────────────────────────────────────

    @Test
    void createSpecimen_success() {
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .specimenType("BLOOD")
            .build();

        LabSpecimen saved = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.COLLECTED)
            .build();

        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.existsByAccessionNumber(any())).thenReturn(false);
        when(labSpecimenRepository.save(any())).thenReturn(saved);
        when(labSpecimenMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        LabSpecimenResponseDTO result = service.createSpecimen(request, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
        verify(labSpecimenRepository).save(any(LabSpecimen.class));
    }

    @Test
    void createSpecimen_noLabOrderId_throwsBusinessException() {
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder().build(); // labOrderId is null

        assertThatThrownBy(() -> service.createSpecimen(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("labOrderId");
    }

    @Test
    void createSpecimen_labOrderNotFound_throwsResourceNotFoundException() {
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .build();

        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSpecimen(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createSpecimen_hospitalMismatch_throwsResourceNotFoundException() {
        UUID otherHospitalId = UUID.randomUUID();
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .build();

        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId); // different hospital

        assertThatThrownBy(() -> service.createSpecimen(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createSpecimen_superAdmin_skipsHospitalScopeCheck() {
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .specimenType("URINE")
            .build();

        LabSpecimen saved = LabSpecimen.builder().labOrder(labOrder).build();

        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(null); // super admin
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.existsByAccessionNumber(any())).thenReturn(false);
        when(labSpecimenRepository.save(any())).thenReturn(saved);
        when(labSpecimenMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        LabSpecimenResponseDTO result = service.createSpecimen(request, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
    }

    // ── getSpecimenById ───────────────────────────────────────────────────────

    @Test
    void getSpecimenById_success() {
        LabSpecimen specimen = LabSpecimen.builder().labOrder(labOrder).build();
        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(responseDTO);

        LabSpecimenResponseDTO result = service.getSpecimenById(specimenId, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void getSpecimenById_notFound_throwsResourceNotFoundException() {
        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSpecimenById(specimenId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSpecimenById_hospitalMismatch_throwsResourceNotFoundException() {
        UUID otherHospitalId = UUID.randomUUID();
        LabSpecimen specimen = LabSpecimen.builder().labOrder(labOrder).build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);

        assertThatThrownBy(() -> service.getSpecimenById(specimenId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getSpecimensByLabOrder ────────────────────────────────────────────────

    @Test
    void getSpecimensByLabOrder_success() {
        LabSpecimen specimen = LabSpecimen.builder().labOrder(labOrder).build();
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(labSpecimenRepository.findByLabOrder_Id(labOrderId)).thenReturn(List.of(specimen));
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(responseDTO);

        List<LabSpecimenResponseDTO> result = service.getSpecimensByLabOrder(labOrderId, Locale.ENGLISH);

        assertThat(result).containsExactly(responseDTO);
    }

    @Test
    void getSpecimensByLabOrder_labOrderNotFound_throwsResourceNotFoundException() {
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSpecimensByLabOrder(labOrderId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSpecimensByLabOrder_hospitalMismatch_throwsResourceNotFoundException() {
        UUID otherHospitalId = UUID.randomUUID();
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);

        assertThatThrownBy(() -> service.getSpecimensByLabOrder(labOrderId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── receiveSpecimen ───────────────────────────────────────────────────────

    @Test
    void receiveSpecimen_fromCollected_success() {
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.COLLECTED)
            .build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.save(specimen)).thenReturn(specimen);
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(responseDTO);

        LabSpecimenResponseDTO result = service.receiveSpecimen(specimenId, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
        assertThat(specimen.getStatus()).isEqualTo(LabSpecimenStatus.RECEIVED);
        verify(instrumentOutboxService).enqueueSpecimenReceived(specimen);
    }

    @Test
    void receiveSpecimen_fromInTransit_success() {
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.IN_TRANSIT)
            .build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.save(specimen)).thenReturn(specimen);
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(responseDTO);

        service.receiveSpecimen(specimenId, Locale.ENGLISH);

        assertThat(specimen.getStatus()).isEqualTo(LabSpecimenStatus.RECEIVED);
        verify(instrumentOutboxService).enqueueSpecimenReceived(specimen);
    }

    @Test
    void receiveSpecimen_invalidStatus_throwsBusinessException() {
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.RECEIVED) // already received → invalid transition
            .build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.receiveSpecimen(specimenId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be received from status");
    }

    @Test
    void receiveSpecimen_notFound_throwsResourceNotFoundException() {
        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receiveSpecimen(specimenId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void receiveSpecimen_hospitalMismatch_throwsResourceNotFoundException() {
        UUID otherHospitalId = UUID.randomUUID();
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.COLLECTED)
            .build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);

        assertThatThrownBy(() -> service.receiveSpecimen(specimenId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
