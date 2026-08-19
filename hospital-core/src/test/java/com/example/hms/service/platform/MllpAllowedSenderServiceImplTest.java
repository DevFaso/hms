package com.example.hms.service.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MllpAllowedSenderMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.MllpAllowedSender;
import com.example.hms.payload.dto.platform.MllpAllowedSenderRequestDTO;
import com.example.hms.payload.dto.platform.MllpAllowedSenderResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.platform.MllpAllowedSenderRepository;
import com.example.hms.service.platform.impl.MllpAllowedSenderServiceImpl;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MllpAllowedSenderServiceImpl}. Uses the real
 * {@link MllpAllowedSenderMapper} so the upper-case normalisation
 * contract introduced for V62 is exercised end-to-end through the
 * service.
 */
@ExtendWith(MockitoExtension.class)
class MllpAllowedSenderServiceImplTest {

    @Mock private MllpAllowedSenderRepository senderRepository;
    @Mock private HospitalRepository hospitalRepository;

    private final MllpAllowedSenderMapper mapper = new MllpAllowedSenderMapper();

    private MllpAllowedSenderServiceImpl service;

    private Hospital hospital;
    private UUID hospitalId;

    @BeforeEach
    void setUp() {
        service = new MllpAllowedSenderServiceImpl(senderRepository, hospitalRepository, mapper);
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Allowlisted Hospital");
    }

    private MllpAllowedSender persisted(UUID id, String app, String facility, boolean active) {
        MllpAllowedSender s = MllpAllowedSender.builder()
            .hospital(hospital)
            .sendingApplication(app)
            .sendingFacility(facility)
            .description(null)
            .active(active)
            .build();
        s.setId(id);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        return s;
    }

    @Test
    @DisplayName("resolveHospital returns empty when either field is blank")
    void resolveHospitalEmptyOnBlank() {
        assertThat(service.resolveHospital(null, "LAB_A")).isEmpty();
        assertThat(service.resolveHospital("APP", "")).isEmpty();
        assertThat(service.resolveHospital("  ", "  ")).isEmpty();
        verify(senderRepository, never())
            .findBySendingApplicationAndSendingFacilityAndActiveTrue(any(), any());
    }

    @Test
    @DisplayName("resolveHospital uppercases inputs to match canonical storage")
    void resolveHospitalUppercasesInputs() {
        when(senderRepository.findBySendingApplicationAndSendingFacilityAndActiveTrue("ROCHE_COBAS", "LAB_A"))
            .thenReturn(Optional.of(persisted(UUID.randomUUID(), "ROCHE_COBAS", "LAB_A", true)));

        Optional<Hospital> result = service.resolveHospital("Roche_Cobas", "lab_a");
        assertThat(result).contains(hospital);
    }

    @Test
    @DisplayName("resolveHospital empty when the lookup misses")
    void resolveHospitalEmptyOnMiss() {
        when(senderRepository.findBySendingApplicationAndSendingFacilityAndActiveTrue(any(), any()))
            .thenReturn(Optional.empty());

        assertThat(service.resolveHospital("UNKNOWN", "LAB_X")).isEmpty();
    }

    @Test
    @DisplayName("create normalises sender fields to upper-case via the mapper and saves")
    void createNormalisesAndSaves() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(senderRepository.save(any(MllpAllowedSender.class))).thenAnswer(inv -> {
            MllpAllowedSender e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            return e;
        });

        MllpAllowedSenderRequestDTO req = new MllpAllowedSenderRequestDTO(
            hospitalId, "Roche_Cobas", "lab_a", "  c8000 main lab  ", null);
        MllpAllowedSenderResponseDTO out = service.create(req, Locale.ENGLISH);

        assertThat(out.sendingApplication()).isEqualTo("ROCHE_COBAS");
        assertThat(out.sendingFacility()).isEqualTo("LAB_A");
        assertThat(out.description()).isEqualTo("c8000 main lab");
        assertThat(out.active()).isTrue();

        ArgumentCaptor<MllpAllowedSender> captor = ArgumentCaptor.forClass(MllpAllowedSender.class);
        verify(senderRepository).save(captor.capture());
        assertThat(captor.getValue().getSendingApplication()).isEqualTo("ROCHE_COBAS");
        assertThat(captor.getValue().getSendingFacility()).isEqualTo("LAB_A");
    }

    @Test
    @DisplayName("create throws ResourceNotFoundException when hospital id is unknown")
    void createThrowsWhenHospitalMissing() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        MllpAllowedSenderRequestDTO req = new MllpAllowedSenderRequestDTO(
            hospitalId, "ROCHE_COBAS", "LAB_A", null, true);
        assertThatThrownBy(() -> service.create(req, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(senderRepository, never()).save(any());
    }

    @Test
    @DisplayName("update overwrites fields on the existing entity and persists")
    void updateApplies() {
        UUID id = UUID.randomUUID();
        MllpAllowedSender existing = persisted(id, "OLD_APP", "OLD_FAC", true);
        when(senderRepository.findById(id)).thenReturn(Optional.of(existing));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(senderRepository.save(any(MllpAllowedSender.class))).thenAnswer(inv -> inv.getArgument(0));

        MllpAllowedSenderRequestDTO req = new MllpAllowedSenderRequestDTO(
            hospitalId, "NEW_APP", "NEW_FAC", "updated", false);
        MllpAllowedSenderResponseDTO out = service.update(id, req, Locale.ENGLISH);

        assertThat(out.sendingApplication()).isEqualTo("NEW_APP");
        assertThat(out.sendingFacility()).isEqualTo("NEW_FAC");
        assertThat(out.active()).isFalse();
        assertThat(existing.getDescription()).isEqualTo("updated");
    }

    @Test
    @DisplayName("update throws when sender id is unknown")
    void updateThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(senderRepository.findById(id)).thenReturn(Optional.empty());

        MllpAllowedSenderRequestDTO req = new MllpAllowedSenderRequestDTO(
            hospitalId, "APP", "FAC", null, true);
        assertThatThrownBy(() -> service.update(id, req, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getById returns the mapped DTO")
    void getByIdReturnsDto() {
        UUID id = UUID.randomUUID();
        when(senderRepository.findById(id)).thenReturn(Optional.of(persisted(id, "A", "B", true)));

        MllpAllowedSenderResponseDTO dto = service.getById(id, Locale.ENGLISH);
        assertThat(dto.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("getById throws when the row is missing")
    void getByIdThrows() {
        UUID id = UUID.randomUUID();
        when(senderRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("findAll returns mapped list ordered by repo")
    void findAllReturnsMappedList() {
        when(senderRepository.findAllByOrderBySendingFacilityAscSendingApplicationAsc())
            .thenReturn(List.of(
                persisted(UUID.randomUUID(), "APP1", "FAC1", true),
                persisted(UUID.randomUUID(), "APP2", "FAC2", false)));

        List<MllpAllowedSenderResponseDTO> out = service.findAll(Locale.ENGLISH);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).sendingFacility()).isEqualTo("FAC1");
    }

    @Test
    @DisplayName("findByHospital filters via repository hospital index")
    void findByHospitalDelegatesToRepo() {
        when(senderRepository.findAllByHospital_IdOrderBySendingFacilityAsc(hospitalId))
            .thenReturn(List.of(persisted(UUID.randomUUID(), "APP", "FAC", true)));

        List<MllpAllowedSenderResponseDTO> out = service.findByHospital(hospitalId, Locale.ENGLISH);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).hospitalId()).isEqualTo(hospitalId);
    }

    @Test
    @DisplayName("deactivate flips active=false and saves once")
    void deactivateFlipsActiveAndSaves() {
        UUID id = UUID.randomUUID();
        MllpAllowedSender existing = persisted(id, "APP", "FAC", true);
        when(senderRepository.findById(id)).thenReturn(Optional.of(existing));

        service.deactivate(id, Locale.ENGLISH);

        assertThat(existing.isActive()).isFalse();
        verify(senderRepository).save(existing);
    }

    @Test
    @DisplayName("deactivate is a no-op when the entry is already inactive")
    void deactivateNoOpWhenAlreadyInactive() {
        UUID id = UUID.randomUUID();
        MllpAllowedSender existing = persisted(id, "APP", "FAC", false);
        when(senderRepository.findById(id)).thenReturn(Optional.of(existing));

        service.deactivate(id, Locale.ENGLISH);

        assertThat(existing.isActive()).isFalse();
        verify(senderRepository, never()).save(any());
    }

    @Test
    @DisplayName("deactivate throws when id is unknown")
    void deactivateThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(senderRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
