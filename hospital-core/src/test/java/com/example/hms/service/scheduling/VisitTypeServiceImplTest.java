package com.example.hms.service.scheduling;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.scheduling.VisitType;
import com.example.hms.payload.dto.scheduling.VisitTypeRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.scheduling.VisitTypeRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Visit-type catalog (P2 #11) — the first writer for the parent table without
 * which POST /slots/generate could only ever answer slotsCreated=0.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisitTypeServiceImplTest {

    @Mock private VisitTypeRepository visitTypeRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private RoleValidator roleValidator;

    private VisitTypeServiceImpl service;

    private UUID hospitalId;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new VisitTypeServiceImpl(
            visitTypeRepository, departmentRepository, hospitalRepository, roleValidator);

        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(visitTypeRepository.save(any(VisitType.class))).thenAnswer(i -> i.getArgument(0));
        when(visitTypeRepository.findByHospital_IdAndCodeIgnoreCase(any(), any()))
            .thenReturn(Optional.empty());
    }

    private VisitTypeRequestDTO valid() {
        return VisitTypeRequestDTO.builder()
            .code("NEW_CONSULT")
            .name("New consultation")
            .durationMinutes(30)
            .build();
    }

    @Test
    void createStoresAVisitTypeInTheCallersHospital() {
        service.create(valid());

        ArgumentCaptor<VisitType> captor = ArgumentCaptor.forClass(VisitType.class);
        verify(visitTypeRepository).save(captor.capture());
        assertThat(captor.getValue().getHospital()).isEqualTo(hospital);
        assertThat(captor.getValue().getCode()).isEqualTo("NEW_CONSULT");
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(captor.getValue().isPatientBookable()).isFalse();
    }

    @Test
    void createRefusesADuplicateCode() {
        VisitType existing = VisitType.builder().hospital(hospital).active(true).build();
        existing.setId(UUID.randomUUID());
        when(visitTypeRepository.findByHospital_IdAndCodeIgnoreCase(hospitalId, "NEW_CONSULT"))
            .thenReturn(Optional.of(existing));

        VisitTypeRequestDTO request = valid();
        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");
        verify(visitTypeRepository, never()).save(any());
    }

    @Test
    void createPointsAtReactivateWhenTheCodeBelongsToARetiredType() {
        VisitType retired = VisitType.builder().hospital(hospital).active(false).build();
        retired.setId(UUID.randomUUID());
        when(visitTypeRepository.findByHospital_IdAndCodeIgnoreCase(hospitalId, "NEW_CONSULT"))
            .thenReturn(Optional.of(retired));

        VisitTypeRequestDTO request = valid();
        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reactivate");
    }

    @Test
    void anUnscopedSuperAdminIsRefused() {
        // Same rule as the slot inventory: the catalog is inherently
        // per-hospital; there is nothing sensible to answer globally.
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        VisitTypeRequestDTO request = valid();
        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital");
    }

    @Test
    void aForeignDepartmentReadsAsNotFound() {
        UUID foreignDeptId = UUID.randomUUID();
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Department foreign = new Department();
        foreign.setId(foreignDeptId);
        foreign.setHospital(other);
        when(departmentRepository.findById(foreignDeptId)).thenReturn(Optional.of(foreign));

        VisitTypeRequestDTO request = valid();
        request.setDepartmentId(foreignDeptId);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deactivateAndReactivateToggleWithoutDeleting() {
        VisitType entity = VisitType.builder().hospital(hospital).active(true).build();
        entity.setId(UUID.randomUUID());
        when(visitTypeRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(service.deactivate(entity.getId()).isActive()).isFalse();
        assertThat(service.reactivate(entity.getId()).isActive()).isTrue();
        verify(visitTypeRepository, never()).delete(any());
    }

    @Test
    void listIncludesRetiredRowsOnlyWhenAsked() {
        VisitType active = VisitType.builder().hospital(hospital).active(true).name("A").build();
        active.setId(UUID.randomUUID());
        VisitType retired = VisitType.builder().hospital(hospital).active(false).name("B").build();
        retired.setId(UUID.randomUUID());
        when(visitTypeRepository.findByHospital_IdAndActiveTrueOrderByNameAsc(hospitalId))
            .thenReturn(java.util.List.of(active));
        when(visitTypeRepository.findByHospital_IdOrderByNameAsc(hospitalId))
            .thenReturn(java.util.List.of(active, retired));

        assertThat(service.list(false)).hasSize(1);
        assertThat(service.list(true)).hasSize(2);
    }

    @Test
    void updateRewritesTheCatalogRowIncludingItsDepartmentScope() {
        VisitType entity = VisitType.builder()
            .hospital(hospital).active(true).code("OLD").name("Old").durationMinutes(15)
            .build();
        entity.setId(UUID.randomUUID());
        when(visitTypeRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        Department department = new Department();
        department.setId(UUID.randomUUID());
        department.setHospital(hospital);
        department.setName("Cardiology");
        when(departmentRepository.findById(department.getId())).thenReturn(Optional.of(department));

        VisitTypeRequestDTO request = valid();
        request.setDepartmentId(department.getId());
        request.setPatientBookable(true);

        var dto = service.update(entity.getId(), request);

        assertThat(entity.getCode()).isEqualTo("NEW_CONSULT");
        assertThat(entity.getDepartment()).isEqualTo(department);
        assertThat(entity.isPatientBookable()).isTrue();
        assertThat(dto.getDepartmentName()).isEqualTo("Cardiology");
    }

    @Test
    void aForeignVisitTypeReadsAsNotFound() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        VisitType foreign = VisitType.builder().hospital(other).active(true).build();
        foreign.setId(UUID.randomUUID());
        when(visitTypeRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        UUID foreignId = foreign.getId();
        assertThatThrownBy(() -> service.deactivate(foreignId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
