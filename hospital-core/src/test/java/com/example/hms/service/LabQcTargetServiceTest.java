package com.example.hms.service;

import com.example.hms.enums.LabQcEventLevel;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabQcTarget;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.User;
import com.example.hms.payload.dto.LabQcTargetRequestDTO;
import com.example.hms.payload.dto.LabQcTargetResponseDTO;
import com.example.hms.repository.LabQcTargetRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * r63 D2304: the lab director's QC target. Saving creates or replaces the
 * hospital's target for one test and level, stamped as approved; the saved
 * row is read back.
 */
@ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
class LabQcTargetServiceTest {

    @Mock private LabQcTargetRepository targetRepository;
    @Mock private LabTestDefinitionRepository definitionRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleValidator roleValidator;
    @InjectMocks private LabQcTargetService service;

    private UUID hospitalId;
    private UUID directorId;
    private LabTestDefinition potassium;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        directorId = UUID.randomUUID();
        potassium = new LabTestDefinition();
        potassium.setId(UUID.randomUUID());
        potassium.setName("Kaliemie");
        lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        lenient().when(roleValidator.getCurrentUserId()).thenReturn(directorId);
        User director = new User();
        director.setFirstName("Ga");
        director.setLastName("Some");
        lenient().when(userRepository.findById(directorId)).thenReturn(Optional.of(director));
        lenient().when(targetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private LabQcTargetRequestDTO request(String mean, String sd) {
        return LabQcTargetRequestDTO.builder().testDefinitionId(potassium.getId()).qcLevel("low_control")
            .targetMean(new BigDecimal(mean)).targetSd(new BigDecimal(sd)).lotNumber(" L-7 ").build();
    }

    @Test
    void savingATargetStoresItAtTheActingHospitalAsApprovedByTheDirector() {
        when(definitionRepository.findById(potassium.getId())).thenReturn(Optional.of(potassium));
        when(targetRepository.findByHospitalIdAndTestDefinition_IdAndQcLevel(hospitalId, potassium.getId(),
            LabQcEventLevel.LOW_CONTROL)).thenReturn(Optional.empty());

        LabQcTargetResponseDTO saved = service.save(request("4.0", "0.1"));

        assertThat(saved.getHospitalId()).isEqualTo(hospitalId);
        assertThat(saved.getQcLevel()).isEqualTo("LOW_CONTROL");
        assertThat(saved.getTargetMean()).isEqualByComparingTo("4.0");
        assertThat(saved.getTargetSd()).isEqualByComparingTo("0.1");
        assertThat(saved.getLotNumber()).isEqualTo("L-7");
        assertThat(saved.getApprovedByDisplay()).isEqualTo("Ga Some");
        assertThat(saved.getApprovedAt()).isNotNull();
    }

    @Test
    void savingAgainReplacesTheExistingTarget() {
        LabQcTarget existing = LabQcTarget.builder().hospitalId(hospitalId).testDefinition(potassium)
            .qcLevel(LabQcEventLevel.LOW_CONTROL).targetMean(BigDecimal.ONE).targetSd(BigDecimal.ONE).build();
        when(definitionRepository.findById(potassium.getId())).thenReturn(Optional.of(potassium));
        when(targetRepository.findByHospitalIdAndTestDefinition_IdAndQcLevel(hospitalId, potassium.getId(),
            LabQcEventLevel.LOW_CONTROL)).thenReturn(Optional.of(existing));

        service.save(request("4.2", "0.15"));

        assertThat(existing.getTargetMean()).isEqualByComparingTo("4.2");
        assertThat(existing.getTargetSd()).isEqualByComparingTo("0.15");
    }

    @Test
    void anSdOfZeroIsRefusedOnItsField() {
        assertThatThrownBy(() -> service.save(request("4.0", "0")))
            .isInstanceOfSatisfying(FieldBusinessException.class, ex -> assertThat(ex.getField()).isEqualTo("targetSd"));
    }

    @Test
    void anotherHospitalsTestIsNotFound() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        potassium.setHospital(other);
        when(definitionRepository.findById(potassium.getId())).thenReturn(Optional.of(potassium));

        assertThatThrownBy(() -> service.save(request("4.0", "0.1"))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void everyMissingValueIsNamedInOneRefusal() {
        assertThatThrownBy(() -> service.save(new LabQcTargetRequestDTO()))
            .isInstanceOfSatisfying(FieldBusinessException.class, ex -> assertThat(ex.getFieldErrors())
                .containsOnlyKeys("testDefinitionId", "qcLevel", "targetMean", "targetSd"));
    }
}
