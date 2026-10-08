package com.example.hms.service;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.HospitalMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.HospitalRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /code-review of #832, round 4: the generic super-admin hospital writes
 * (PUT /hospitals/{id}, the organisation link and unlink) refuse a provider
 * facility exactly as an unknown id, because they would skip the provider
 * rules (VERIFY is the only way to ACTIVE; never attached to a hospital
 * organisation). DELETE removes a provider only while it has never been
 * verified. Hospitals are unaffected.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HospitalServiceProviderWritesTest {

    @Mock private HospitalRepository hospitalRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private MessageSource messageSource;
    @Mock private RoleValidator roleValidator;
    @Mock private ProviderVerificationRepository verificationRepository;

    private HospitalServiceImpl service;
    private final Hospital pharmacy = facility(FacilityType.PHARMACY);
    private final Hospital hospital = facility(FacilityType.HOSPITAL);
    private final Organization organization = new Organization();

    @BeforeEach
    void setUp() {
        service = new HospitalServiceImpl(hospitalRepository, organizationRepository, new HospitalMapper(),
            messageSource, roleValidator, verificationRepository);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        pharmacy.setActive(false);
        pharmacy.setLifecycleState(HospitalLifecycleState.SUSPENDED);
        for (Hospital h : new Hospital[]{pharmacy, hospital}) {
            when(hospitalRepository.findById(h.getId())).thenReturn(Optional.of(h));
        }
        organization.setId(UUID.randomUUID());
        when(organizationRepository.findById(organization.getId())).thenReturn(Optional.of(organization));
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("PUT on a pending pharmacy: 404 as for an unknown id, still inactive, nothing saved")
    void updateRefusesAProvider() {
        HospitalRequestDTO dto = request();
        UUID id = pharmacy.getId();

        assertThatThrownBy(() -> service.updateHospital(id, dto, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .satisfies(ex -> assertThat(((ResourceNotFoundException) ex).getMessageKey())
                .isEqualTo("hospital.notFound"));
        assertThat(pharmacy.isActive()).isFalse();
        assertThat(pharmacy.getOrganization()).isNull();
        verify(hospitalRepository, never()).save(any());
    }

    @Test
    @DisplayName("linking a provider to an organisation is refused; unlinking too")
    void organisationLinkRefusesAProvider() {
        UUID id = pharmacy.getId();
        UUID organizationId = organization.getId();

        assertThatThrownBy(() -> service.assignHospitalToOrganization(id, organizationId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.unassignHospitalFromOrganization(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(pharmacy.getOrganization()).isNull();
        verify(hospitalRepository, never()).save(any());
    }

    @Test
    @DisplayName("hospitals are unaffected: update and organisation link still work")
    void hospitalsUnaffected() {
        service.updateHospital(hospital.getId(), request(), Locale.ENGLISH);
        service.assignHospitalToOrganization(hospital.getId(), organization.getId(), Locale.ENGLISH);

        assertThat(hospital.getOrganization()).isSameAs(organization);
    }

    @Test
    @DisplayName("DELETE of a never-verified provider removes it")
    void deleteUnverifiedProvider() {
        when(verificationRepository.existsByHospital_IdAndStatusIn(eq(pharmacy.getId()), anyCollection()))
            .thenReturn(false);

        service.deleteHospital(pharmacy.getId(), Locale.ENGLISH);

        verify(hospitalRepository).deleteById(pharmacy.getId());
    }

    @Test
    @DisplayName("DELETE of a provider that has been verified is a 409: it is retired through the lifecycle")
    void deleteVerifiedProviderIsRefused() {
        when(verificationRepository.existsByHospital_IdAndStatusIn(eq(pharmacy.getId()), anyCollection()))
            .thenReturn(true);
        UUID id = pharmacy.getId();

        assertThatThrownBy(() -> service.deleteHospital(id, Locale.ENGLISH)).isInstanceOf(ConflictException.class);
        verify(hospitalRepository, never()).deleteById(any());
    }

    private static HospitalRequestDTO request() {
        HospitalRequestDTO dto = new HospitalRequestDTO();
        dto.setName("Renamed");
        dto.setAddress("1 Main St");
        dto.setCity("Ouagadougou");
        dto.setCountry("Burkina Faso");
        dto.setActive(true);
        return dto;
    }

    private static Hospital facility(FacilityType type) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setName("F");
        h.setCode("F-" + UUID.randomUUID());
        h.setFacilityType(type);
        return h;
    }
}
