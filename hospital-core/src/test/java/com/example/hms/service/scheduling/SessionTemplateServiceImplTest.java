package com.example.hms.service.scheduling;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.scheduling.SessionTemplate;
import com.example.hms.model.scheduling.VisitType;
import com.example.hms.payload.dto.scheduling.SessionTemplateRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.scheduling.SessionTemplateRepository;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Session templates (P2 #11) — what the slot generator materialises. Without
 * this writer the generator had nothing to apply.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionTemplateServiceImplTest {

    @Mock private SessionTemplateRepository templateRepository;
    @Mock private VisitTypeRepository visitTypeRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private RoleValidator roleValidator;

    private SessionTemplateServiceImpl service;

    private UUID hospitalId;
    private Hospital hospital;
    private Staff staff;
    private Department department;

    @BeforeEach
    void setUp() {
        service = new SessionTemplateServiceImpl(
            templateRepository, visitTypeRepository, staffRepository, departmentRepository,
            roleValidator);

        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);

        staff = Staff.builder().build();
        staff.setId(UUID.randomUUID());
        staff.setHospital(hospital);

        department = new Department();
        department.setId(UUID.randomUUID());
        department.setHospital(hospital);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(department.getId())).thenReturn(Optional.of(department));
        when(templateRepository.save(any(SessionTemplate.class))).thenAnswer(i -> i.getArgument(0));
    }

    private SessionTemplateRequestDTO valid() {
        return SessionTemplateRequestDTO.builder()
            .staffId(staff.getId())
            .departmentId(department.getId())
            .dayOfWeek(1)
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(12, 0))
            .slotMinutes(20)
            .effectiveFrom(LocalDate.of(2026, 9, 1))
            .build();
    }

    @Test
    void createStoresATemplateForTheCallersHospital() {
        service.create(valid());

        ArgumentCaptor<SessionTemplate> captor = ArgumentCaptor.forClass(SessionTemplate.class);
        verify(templateRepository).save(captor.capture());
        assertThat(captor.getValue().getHospital()).isEqualTo(hospital);
        assertThat(captor.getValue().getStaff()).isEqualTo(staff);
        assertThat(captor.getValue().getDayOfWeek()).isEqualTo((short) 1);
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void aSessionMustEndAfterItStarts() {
        SessionTemplateRequestDTO request = valid();
        request.setEndTime(LocalTime.of(9, 0));
        request.setStartTime(LocalTime.of(12, 0));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("end after it starts");
    }

    @Test
    void aWindowShorterThanOneSlotIsRefusedRatherThanGeneratingNothing() {
        // The generator would silently produce zero slots from it — the exact
        // silent-emptiness failure this feature exists to close.
        SessionTemplateRequestDTO request = valid();
        request.setStartTime(LocalTime.of(9, 0));
        request.setEndTime(LocalTime.of(9, 15));
        request.setSlotMinutes(20);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("shorter than one slot");
    }

    @Test
    void theTemplateCannotEndBeforeItTakesEffect() {
        SessionTemplateRequestDTO request = valid();
        request.setEffectiveTo(request.getEffectiveFrom().minusDays(1));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot end before");
    }

    @Test
    void aForeignStaffMemberReadsAsNotFound() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        staff.setHospital(other);

        assertThatThrownBy(() -> service.create(valid()))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(templateRepository, never()).save(any());
    }

    @Test
    void aRetiredVisitTypeCannotBeScheduled() {
        VisitType retired = VisitType.builder().hospital(hospital).active(false).build();
        retired.setId(UUID.randomUUID());
        when(visitTypeRepository.findById(retired.getId())).thenReturn(Optional.of(retired));

        SessionTemplateRequestDTO request = valid();
        request.setVisitTypeId(retired.getId());

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("retired");
    }

    @Test
    void deactivateStopsFutureGenerationWithoutDeleting() {
        SessionTemplate entity = SessionTemplate.builder()
            .hospital(hospital).staff(staff).department(department)
            .dayOfWeek((short) 1)
            .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0))
            .slotMinutes(20).effectiveFrom(LocalDate.of(2026, 9, 1))
            .active(true)
            .build();
        entity.setId(UUID.randomUUID());
        when(templateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(service.deactivate(entity.getId()).isActive()).isFalse();
        verify(templateRepository, never()).delete(any());
    }

    @Test
    void listIncludesRetiredRowsOnlyWhenAsked() {
        SessionTemplate active = template(hospital, true);
        SessionTemplate retired = template(hospital, false);
        when(templateRepository.findByHospital_IdAndActiveTrue(hospitalId))
            .thenReturn(java.util.List.of(active));
        when(templateRepository.findByHospital_IdOrderByDayOfWeekAscStartTimeAsc(hospitalId))
            .thenReturn(java.util.List.of(active, retired));

        assertThat(service.list(false)).hasSize(1);
        assertThat(service.list(true)).hasSize(2);
    }

    @Test
    void updateChangesWhatFutureGenerationProduces() {
        SessionTemplate entity = template(hospital, true);
        when(templateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        VisitType visitType = VisitType.builder().hospital(hospital).active(true).name("New consult").build();
        visitType.setId(UUID.randomUUID());
        when(visitTypeRepository.findById(visitType.getId())).thenReturn(Optional.of(visitType));

        SessionTemplateRequestDTO request = valid();
        request.setVisitTypeId(visitType.getId());
        request.setDayOfWeek(3);
        request.setSlotMinutes(30);
        request.setNotes("Wednesday clinic");

        var dto = service.update(entity.getId(), request);

        assertThat(entity.getDayOfWeek()).isEqualTo((short) 3);
        assertThat(entity.getSlotMinutes()).isEqualTo(30);
        assertThat(entity.getVisitType()).isEqualTo(visitType);
        assertThat(dto.getVisitTypeName()).isEqualTo("New consult");
        assertThat(dto.getNotes()).isEqualTo("Wednesday clinic");
    }

    @Test
    void reactivateRestoresARetiredTemplateToTheGenerator() {
        SessionTemplate entity = template(hospital, false);
        when(templateRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(service.reactivate(entity.getId()).isActive()).isTrue();
        assertThat(entity.isActive()).isTrue();
    }

    private SessionTemplate template(Hospital owner, boolean active) {
        SessionTemplate entity = SessionTemplate.builder()
            .hospital(owner).staff(staff).department(department)
            .dayOfWeek((short) 1)
            .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0))
            .slotMinutes(20).effectiveFrom(LocalDate.of(2026, 9, 1))
            .active(active)
            .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }

    @Test
    void aForeignTemplateReadsAsNotFound() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        SessionTemplate foreign = SessionTemplate.builder()
            .hospital(other).staff(staff).department(department)
            .dayOfWeek((short) 1)
            .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0))
            .slotMinutes(20).effectiveFrom(LocalDate.of(2026, 9, 1))
            .build();
        foreign.setId(UUID.randomUUID());
        when(templateRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.deactivate(foreign.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
