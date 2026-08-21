package com.example.hms.service.scheduling;

import com.example.hms.enums.SlotStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.scheduling.AppointmentSlot;
import com.example.hms.model.scheduling.SessionTemplate;
import com.example.hms.payload.dto.scheduling.SlotGenerationResultDTO;
import com.example.hms.repository.scheduling.AppointmentSlotRepository;
import com.example.hms.repository.scheduling.SessionTemplateRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slot inventory (P2 #11) — foundation pass.
 *
 * <p>Scheduling could already book an Appointment against an arbitrary
 * (staff, date, time). What nothing could do was answer "when is this clinician
 * next free for a 30-minute follow-up?" — the question that blocks patient
 * self-scheduling, waitlist auto-offer and utilisation reporting alike.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlotInventoryServiceImplTest {

    @Mock private SessionTemplateRepository templateRepository;
    @Mock private AppointmentSlotRepository slotRepository;
    @Mock private RoleValidator roleValidator;

    private SlotInventoryServiceImpl service;

    private UUID hospitalId;
    private Hospital hospital;
    private Staff staff;
    private SessionTemplate template;

    @BeforeEach
    void setUp() {
        service = new SlotInventoryServiceImpl(templateRepository, slotRepository, roleValidator);

        hospitalId = UUID.randomUUID();
        hospital = Hospital.builder().name("CHU").code("CHU").build();
        hospital.setId(hospitalId);

        staff = Staff.builder().hospital(hospital).name("Dr Kabore").build();
        staff.setId(UUID.randomUUID());

        Department department = new Department();
        department.setId(UUID.randomUUID());

        // Monday clinic, 09:00-12:00, 30-minute slots => 6 slots per Monday.
        template = SessionTemplate.builder()
            .hospital(hospital)
            .department(department)
            .staff(staff)
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(12, 0))
            .slotMinutes(30)
            .effectiveFrom(LocalDate.of(2026, 1, 1))
            .active(true)
            .build();
        template.setId(UUID.randomUUID());
        template.setDayOfWeekEnum(DayOfWeek.MONDAY);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(templateRepository.findByHospital_IdAndActiveTrue(hospitalId)).thenReturn(List.of(template));
        when(slotRepository.save(any(AppointmentSlot.class))).thenAnswer(i -> i.getArgument(0));
        when(slotRepository.existsByStaff_IdAndStartAt(any(), any())).thenReturn(false);
    }

    /** 2026-08-24 is a Monday. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);

    @Test
    void generateSlicesTheSessionIntoSlots() {
        SlotGenerationResultDTO result = service.generate(MONDAY, MONDAY);

        // 09:00-12:00 at 30 minutes = 6, and the last one ENDS at 12:00 rather
        // than starting there — a session that runs to noon does not offer a
        // 12:00 appointment.
        assertThat(result.getSlotsCreated()).isEqualTo(6);
        assertThat(result.getTemplatesApplied()).isEqualTo(1);
    }

    @Test
    void generateSkipsDaysTheTemplateDoesNotCover() {
        // Tuesday: a Monday template must produce nothing.
        SlotGenerationResultDTO result = service.generate(MONDAY.plusDays(1), MONDAY.plusDays(1));

        assertThat(result.getSlotsCreated()).isZero();
        assertThat(result.getTemplatesApplied()).isZero();
        verify(slotRepository, never()).save(any());
    }

    @Test
    void generateIsIdempotent() {
        // Re-running over an overlapping window is the NORMAL case — the natural
        // way to operate this is a rolling horizon that always re-covers days it
        // already covered.
        when(slotRepository.existsByStaff_IdAndStartAt(any(), any())).thenReturn(true);

        SlotGenerationResultDTO result = service.generate(MONDAY, MONDAY);

        assertThat(result.getSlotsCreated()).isZero();
        assertThat(result.getSkippedExisting()).isEqualTo(6);
        verify(slotRepository, never()).save(any());
    }

    @Test
    void generateRefusesAnUnboundedWindow() {
        // An unbounded range would let one request materialise years of rows and
        // lock the table doing it.
        assertThatThrownBy(() -> service.generate(MONDAY, MONDAY.plusYears(2)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("at most");
    }

    @Test
    void generateRefusesABackwardsWindow() {
        assertThatThrownBy(() -> service.generate(MONDAY, MONDAY.minusDays(1)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ends before it starts");
    }

    @Test
    void generateRespectsTheTemplateEffectiveRange() {
        template.setEffectiveTo(MONDAY.minusDays(1));

        assertThat(service.generate(MONDAY, MONDAY).getSlotsCreated()).isZero();
    }

    /* ── holds ── */

    private AppointmentSlot openSlot() {
        AppointmentSlot slot = AppointmentSlot.builder()
            .hospital(hospital)
            .staff(staff)
            .slotDate(LocalDate.now().plusDays(1))
            .startAt(LocalDateTime.now().plusDays(1))
            .endAt(LocalDateTime.now().plusDays(1).plusMinutes(30))
            .status(SlotStatus.OPEN)
            .build();
        slot.setId(UUID.randomUUID());
        when(slotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        return slot;
    }

    @Test
    void holdReservesTheSlotWithADeadline() {
        // Booking is not instantaneous; without a hold two people can be offered
        // the same moment and only one can have it.
        AppointmentSlot slot = openSlot();

        service.hold(slot.getId(), 10);

        assertThat(slot.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(slot.getHeldUntil()).isAfter(LocalDateTime.now());
    }

    @Test
    void anExpiredHoldCanBeTakenBySomebodyElse() {
        // A patient who abandoned a booking twenty minutes ago must not keep the
        // slot out of circulation until the reclaim sweep happens to run.
        AppointmentSlot slot = openSlot();
        slot.setStatus(SlotStatus.HELD);
        slot.setHeldUntil(LocalDateTime.now().minusMinutes(20));

        service.hold(slot.getId(), 10);

        assertThat(slot.getStatus()).isEqualTo(SlotStatus.HELD);
    }

    @Test
    void aLiveHoldCannotBeStolen() {
        AppointmentSlot slot = openSlot();
        slot.setStatus(SlotStatus.HELD);
        slot.setHeldUntil(LocalDateTime.now().plusMinutes(5));

        UUID id = slot.getId();
        assertThatThrownBy(() -> service.hold(id, 10))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no longer available");
    }

    @Test
    void aSlotInThePastCannotBeHeld() {
        AppointmentSlot slot = openSlot();
        slot.setStartAt(LocalDateTime.now().minusHours(1));

        UUID id = slot.getId();
        assertThatThrownBy(() -> service.hold(id, 10))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("in the past");
    }

    @Test
    void releasingABookedSlotIsRefused() {
        // It would silently strand the appointment pointing at it.
        AppointmentSlot slot = openSlot();
        slot.setStatus(SlotStatus.BOOKED);

        UUID id = slot.getId();
        assertThatThrownBy(() -> service.release(id))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Cancel the appointment");
    }

    @Test
    void blockingABookedSlotIsRefused() {
        AppointmentSlot slot = openSlot();
        slot.setStatus(SlotStatus.BOOKED);

        UUID id = slot.getId();
        assertThatThrownBy(() -> service.block(id, "Leave"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Cancel the appointment");
    }

    @Test
    void reclaimReturnsExpiredHoldsToOpen() {
        AppointmentSlot slot = openSlot();
        slot.setStatus(SlotStatus.HELD);
        slot.setHeldUntil(LocalDateTime.now().minusMinutes(1));
        slot.setHeldByUserId(UUID.randomUUID());
        when(slotRepository.findByStatusAndHeldUntilBefore(any(), any())).thenReturn(List.of(slot));

        assertThat(service.reclaimExpiredHolds()).isEqualTo(1);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat(slot.getHeldUntil()).isNull();
        assertThat(slot.getHeldByUserId()).isNull();
    }

    @Test
    void slotWorkRequiresAnActiveHospital() {
        // Slot inventory is inherently per-hospital: a super-admin has no rota of
        // their own, so there is nothing sensible to answer.
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        assertThatThrownBy(() -> service.generate(MONDAY, MONDAY))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital");
    }
}
