package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.OnCallScheduleRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * On-call rota writes (P2 #13).
 *
 * <p>{@code GET /me/on-call-status} has read this table since it shipped and
 * NOTHING ever wrote to it, so the endpoint could only ever answer "no". An
 * on-call schedule nobody can fill in is one that says everyone is off duty.
 */
@ExtendWith(MockitoExtension.class)
class OnCallScheduleServiceImplTest {

    @Mock private OnCallScheduleRepository onCallRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private RoleValidator roleValidator;

    @InjectMocks private OnCallScheduleServiceImpl service;

    private UUID hospitalId;
    private UUID staffId;
    private Staff staff;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();

        Hospital hospital = Hospital.builder().name("CHU").code("CHU").build();
        hospital.setId(hospitalId);

        staff = Staff.builder().hospital(hospital).name("Dr Kabore").build();
        staff.setId(staffId);
    }

    private OnCallScheduleRequestDTO request(OffsetDateTime start, OffsetDateTime end) {
        return OnCallScheduleRequestDTO.builder()
            .staffId(staffId)
            .startTime(start)
            .endTime(end)
            .build();
    }

    @Test
    void createPersistsARotaEntry() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OnCallScheduleRequestDTO req = request(start, start.plusHours(8));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(onCallRepository.findOverlapping(eq(staffId), any(), any(), eq(null)))
            .thenReturn(List.of());
        when(onCallRepository.save(any(OnCallSchedule.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.create(req).getStaffId()).isEqualTo(staffId);
        verify(onCallRepository).save(any(OnCallSchedule.class));
    }

    @Test
    void createRefusesAnOverlappingShiftForTheSameClinician() {
        // Two overlapping entries mean two rotas each believe they have cover,
        // which is worse than one rota knowing it has none.
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OnCallScheduleRequestDTO req = request(start, start.plusHours(8));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(onCallRepository.findOverlapping(eq(staffId), any(), any(), eq(null)))
            .thenReturn(List.of(new OnCallSchedule()));

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already on call");
        verify(onCallRepository, never()).save(any());
    }

    @Test
    void createRefusesAShiftThatEndsBeforeItStarts() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(4);
        OnCallScheduleRequestDTO req = request(start, start.minusHours(2));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("end after it starts");
    }

    @Test
    void createRefusesToRosterAnotherHospitalsStaff() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OnCallScheduleRequestDTO req = request(start, start.plusHours(8));

        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateDoesNotCollideWithItself() {
        // The overlap check has to exclude the row being edited, or nudging a
        // shift by five minutes would report it clashing with itself.
        UUID entryId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OnCallScheduleRequestDTO req = request(start, start.plusHours(8));

        OnCallSchedule existing = new OnCallSchedule();
        existing.setId(entryId);
        existing.setStaff(staff);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(onCallRepository.findById(entryId)).thenReturn(Optional.of(existing));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(onCallRepository.findOverlapping(eq(staffId), any(), any(), eq(entryId)))
            .thenReturn(List.of());
        when(onCallRepository.save(any(OnCallSchedule.class))).thenAnswer(i -> i.getArgument(0));

        service.update(entryId, req);

        verify(onCallRepository).findOverlapping(eq(staffId), any(), any(), eq(entryId));
    }

    @Test
    void listMarksTheEntryCoveringRightNow() {
        OffsetDateTime now = OffsetDateTime.now();
        OnCallSchedule current = new OnCallSchedule();
        current.setId(UUID.randomUUID());
        current.setStaff(staff);
        current.setStartTime(now.minusHours(1));
        current.setEndTime(now.plusHours(1));

        OnCallSchedule later = new OnCallSchedule();
        later.setId(UUID.randomUUID());
        later.setStaff(staff);
        later.setStartTime(now.plusDays(1));
        later.setEndTime(now.plusDays(1).plusHours(8));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(onCallRepository.findByHospitalAndWindow(eq(hospitalId), any(), any()))
            .thenReturn(List.of(current, later));

        assertThat(service.listForHospital(null, null))
            .extracting("currentlyOnCall")
            .containsExactly(true, false);
    }
}
