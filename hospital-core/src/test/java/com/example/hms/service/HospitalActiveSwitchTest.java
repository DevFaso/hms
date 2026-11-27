package com.example.hms.service;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.exception.ConflictException;
import com.example.hms.mapper.HospitalMapper;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.HospitalRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA r81 D4317, the hospital side of the class: the form's "active" switch
 * and the lifecycle state move together, and a hospital that still has staff
 * assigned is not switched off.
 */
class HospitalActiveSwitchTest {

    private HospitalRepository hospitalRepository;
    private HospitalServiceImpl service;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospitalRepository = mock(HospitalRepository.class);
        RoleValidator roleValidator = mock(RoleValidator.class);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        service = new HospitalServiceImpl(hospitalRepository, mock(OrganizationRepository.class),
            new HospitalMapper(), mock(MessageSource.class), roleValidator,
            mock(ProviderVerificationRepository.class));
        hospital = Hospital.builder().name("CMA Test").code("CMA-T").active(true).address("Rue 12.34")
            .facilityType(FacilityType.HOSPITAL).lifecycleState(HospitalLifecycleState.ACTIVE).build();
        hospital.setId(UUID.randomUUID());
        when(hospitalRepository.findByIdForUpdate(hospital.getId())).thenReturn(Optional.of(hospital));
        when(hospitalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private HospitalRequestDTO switchTo(boolean active) {
        HospitalRequestDTO dto = new HospitalRequestDTO();
        dto.setActive(active);
        return dto;
    }

    @Test
    @DisplayName("a hospital with active staff is not deactivated; the refusal names the switch and nothing changes")
    void refusedWithActiveStaff() {
        when(hospitalRepository.countActiveStaffAssignments(hospital.getId())).thenReturn(4L);

        assertThatThrownBy(() -> service.updateHospital(hospital.getId(), switchTo(false), Locale.FRENCH))
            .isInstanceOf(ConflictException.class)
            .hasMessageStartingWith("active:");
        assertThat(hospital.getLifecycleState()).isEqualTo(HospitalLifecycleState.ACTIVE);
    }

    @Test
    @DisplayName("with no staff, both switches move together, there and back")
    void bothSwitchesMoveTogether() {
        when(hospitalRepository.countActiveStaffAssignments(hospital.getId())).thenReturn(0L);

        service.updateHospital(hospital.getId(), switchTo(false), Locale.FRENCH);

        assertThat(hospital.isActive()).isFalse();
        assertThat(hospital.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);

        service.updateHospital(hospital.getId(), switchTo(true), Locale.FRENCH);

        assertThat(hospital.isActive()).isTrue();
        assertThat(hospital.getLifecycleState()).isEqualTo(HospitalLifecycleState.ACTIVE);
        assertThat(hospital.getSuspensionReason()).isNull();
    }

    @Test
    @DisplayName("a hospital suspended through its lifecycle is not reactivated from the form")
    void lifecycleSuspensionIsNotLiftedByTheForm() {
        hospital.setActive(false);
        hospital.setLifecycleState(HospitalLifecycleState.SUSPENDED);
        hospital.setSuspensionReason("Non-paiement");

        assertThatThrownBy(() -> service.updateHospital(hospital.getId(), switchTo(true), Locale.FRENCH))
            .isInstanceOf(ConflictException.class);
        assertThat(hospital.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
    }
}
