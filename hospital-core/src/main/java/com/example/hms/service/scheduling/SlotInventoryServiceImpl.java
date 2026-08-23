package com.example.hms.service.scheduling;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.SlotStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.scheduling.AppointmentSlot;
import com.example.hms.model.scheduling.SessionTemplate;
import com.example.hms.model.scheduling.VisitType;
import com.example.hms.payload.dto.scheduling.AppointmentSlotDTO;
import com.example.hms.payload.dto.scheduling.SlotGenerationResultDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.scheduling.AppointmentSlotRepository;
import com.example.hms.repository.scheduling.SessionTemplateRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlotInventoryServiceImpl implements SlotInventoryService {

    /**
     * How far ahead one call may materialise. A rolling horizon is the intended
     * operating mode; an unbounded range would let one request generate years of
     * rows and lock the table doing it.
     */
    private static final int MAX_GENERATION_DAYS = 180;

    private static final int MAX_SEARCH_RESULTS = 200;

    private final SessionTemplateRepository templateRepository;
    private final AppointmentSlotRepository slotRepository;
    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final RoleValidator roleValidator;

    @Override
    @Transactional
    public SlotGenerationResultDTO generate(LocalDate from, LocalDate to) {
        UUID hospitalId = requireHospital();
        LocalDate start = from != null ? from : LocalDate.now();
        LocalDate end = to != null ? to : start.plusWeeks(4);

        if (end.isBefore(start)) {
            throw new BusinessException("The generation window ends before it starts.");
        }
        if (start.plusDays(MAX_GENERATION_DAYS).isBefore(end)) {
            throw new BusinessException(
                "Generate at most " + MAX_GENERATION_DAYS + " days at a time.");
        }

        List<SessionTemplate> templates = templateRepository.findByHospital_IdAndActiveTrue(hospitalId);
        int created = 0;
        int skipped = 0;
        int applied = 0;

        for (SessionTemplate template : templates) {
            boolean used = false;
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                if (!template.appliesOn(date)) {
                    continue;
                }
                used = true;
                for (LocalTime t = template.getStartTime();
                     !t.plusMinutes(template.getSlotMinutes()).isAfter(template.getEndTime());
                     t = t.plusMinutes(template.getSlotMinutes())) {

                    LocalDateTime startAt = LocalDateTime.of(date, t);
                    // Idempotent, and the same check the unique index enforces:
                    // one clinician cannot be in two places at one moment.
                    if (slotRepository.existsByStaff_IdAndStartAt(template.getStaff().getId(), startAt)) {
                        skipped++;
                        continue;
                    }
                    slotRepository.save(buildSlot(template, date, startAt));
                    created++;
                }
            }
            if (used) {
                applied++;
            }
        }

        log.info("Slot generation for hospital {}: {} template(s), {} created, {} already present",
            hospitalId, applied, created, skipped);

        return SlotGenerationResultDTO.builder()
            .from(start).to(end)
            .templatesApplied(applied)
            .slotsCreated(created)
            .skippedExisting(skipped)
            .build();
    }

    private AppointmentSlot buildSlot(SessionTemplate template, LocalDate date, LocalDateTime startAt) {
        return AppointmentSlot.builder()
            .sessionTemplate(template)
            .hospital(template.getHospital())
            .department(template.getDepartment())
            .staff(template.getStaff())
            .visitType(template.getVisitType())
            .slotDate(date)
            .startAt(startAt)
            .endAt(startAt.plusMinutes(template.getSlotMinutes()))
            .status(SlotStatus.OPEN)
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentSlotDTO> searchOpen(UUID departmentId, UUID staffId, UUID visitTypeId,
                                               LocalDate from, LocalDate to, int limit) {
        UUID hospitalId = requireHospital();
        LocalDateTime now = LocalDateTime.now();
        LocalDate start = from != null ? from : LocalDate.now();
        LocalDate end = to != null ? to : start.plusWeeks(2);
        int capped = limit <= 0 ? 50 : Math.min(limit, MAX_SEARCH_RESULTS);

        return slotRepository.searchOpen(
                hospitalId, departmentId, staffId, visitTypeId, start, end,
                // Never offer a slot in the past, even on today's date.
                now, now, PageRequest.of(0, capped))
            .stream()
            .map(this::toDto)
            .toList();
    }

    @Override
    @Transactional
    public AppointmentSlotDTO hold(UUID slotId, int holdMinutes) {
        AppointmentSlot slot = loadScoped(slotId);
        LocalDateTime now = LocalDateTime.now();

        if (!slot.isOfferable(now)) {
            throw new BusinessException("That slot is no longer available.");
        }
        if (slot.getStartAt().isBefore(now)) {
            throw new BusinessException("That slot is in the past.");
        }

        int minutes = holdMinutes > 0 ? holdMinutes : 10;
        slot.setStatus(SlotStatus.HELD);
        slot.setHeldUntil(now.plusMinutes(minutes));
        slot.setHeldByUserId(roleValidator.getCurrentUserId());
        return toDto(slotRepository.save(slot));
    }

    @Override
    @Transactional
    public AppointmentSlotDTO release(UUID slotId) {
        AppointmentSlot slot = loadScoped(slotId);
        if (slot.getStatus() == SlotStatus.BOOKED) {
            // Releasing a booked slot would silently strand the appointment
            // pointing at it. Cancelling the appointment is the way back.
            throw new BusinessException(
                "That slot is booked. Cancel the appointment to free it.");
        }
        clearHold(slot);
        slot.setStatus(SlotStatus.OPEN);
        return toDto(slotRepository.save(slot));
    }

    @Override
    @Transactional
    public AppointmentSlotDTO block(UUID slotId, String reason) {
        AppointmentSlot slot = loadScoped(slotId);
        if (slot.getStatus() == SlotStatus.BOOKED) {
            throw new BusinessException(
                "That slot is booked. Cancel the appointment before blocking the time.");
        }
        clearHold(slot);
        slot.setStatus(SlotStatus.BLOCKED);
        slot.setBlockedReason(reason);
        return toDto(slotRepository.save(slot));
    }

    @Override
    @Transactional
    public int reclaimExpiredHolds() {
        List<AppointmentSlot> expired =
            slotRepository.findByStatusAndHeldUntilBefore(SlotStatus.HELD, LocalDateTime.now());
        for (AppointmentSlot slot : expired) {
            clearHold(slot);
            slot.setStatus(SlotStatus.OPEN);
            slotRepository.save(slot);
        }
        if (!expired.isEmpty()) {
            log.info("Reclaimed {} expired slot hold(s)", expired.size());
        }
        return expired.size();
    }

    @Override
    @Transactional
    public AppointmentSlotDTO book(UUID slotId, UUID patientId, String reason) {
        AppointmentSlot slot = loadScoped(slotId);
        LocalDateTime now = LocalDateTime.now();
        UUID currentUserId = roleValidator.getCurrentUserId();

        // A live hold placed by the caller is exactly the state booking is
        // meant to complete from, so it counts as available to them alone.
        boolean heldByCaller = slot.getStatus() == SlotStatus.HELD
            && slot.getHeldUntil() != null
            && slot.getHeldUntil().isAfter(now)
            && Objects.equals(slot.getHeldByUserId(), currentUserId);
        if (!slot.isOfferable(now) && !heldByCaller) {
            throw new BusinessException("That slot is no longer available.");
        }
        if (slot.getStartAt().isBefore(now)) {
            throw new BusinessException("That slot is in the past.");
        }

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));
        UUID hospitalId = slot.getHospital().getId();
        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("The patient is not registered at this hospital.");
        }

        Staff staff = slot.getStaff();
        if (staff.getUser() == null) {
            throw new BusinessException(
                "The slot's clinician has no user account, so an appointment cannot be created.");
        }
        // Same contract as AppointmentServiceImpl: the assignment is mandatory,
        // and a missing one is a refusal, not a degraded appointment.
        UserRoleHospitalAssignment assignment = assignmentRepository
            .findByUserIdAndHospitalId(staff.getUser().getId(), hospitalId)
            .orElseThrow(() -> new BusinessException(
                "The clinician has no role assignment at this hospital."));

        Appointment appointment = appointmentRepository.save(Appointment.builder()
            .patient(patient)
            .staff(staff)
            .assignment(assignment)
            .hospital(slot.getHospital())
            .department(slot.getDepartment())
            .appointmentDate(slot.getSlotDate())
            .startTime(slot.getStartAt().toLocalTime())
            .endTime(slot.getEndAt().toLocalTime())
            .status(AppointmentStatus.SCHEDULED)
            .reason(reason == null || reason.isBlank() ? defaultReason(slot) : reason)
            .build());

        clearHold(slot);
        slot.setStatus(SlotStatus.BOOKED);
        slot.setAppointment(appointment);
        try {
            // Flush inside the try: the version check happens at flush time,
            // and surfacing it here (not at commit, outside this method) is
            // what turns a double-book race into a friendly refusal. The
            // rollback takes the appointment insert with it.
            return toDto(slotRepository.saveAndFlush(slot));
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException("That slot was just taken. Pick another time.");
        }
    }

    @Override
    @Transactional
    public int releaseForAppointment(UUID appointmentId) {
        return slotRepository.findByAppointment_Id(appointmentId)
            .filter(slot -> slot.getStatus() == SlotStatus.BOOKED)
            .map(slot -> {
                slot.setAppointment(null);
                clearHold(slot);
                // A slot whose time has passed must not re-enter circulation:
                // searchOpen would never return it, but OPEN would misstate it.
                slot.setStatus(slot.getStartAt().isBefore(LocalDateTime.now())
                    ? SlotStatus.BLOCKED : SlotStatus.OPEN);
                if (slot.getStatus() == SlotStatus.BLOCKED) {
                    slot.setBlockedReason("Appointment cancelled after the slot time passed");
                }
                slotRepository.save(slot);
                log.info("Freed slot {} after appointment {} was cancelled or moved",
                    slot.getId(), appointmentId);
                return 1;
            })
            .orElse(0);
    }

    private String defaultReason(AppointmentSlot slot) {
        VisitType visitType = slot.getVisitType();
        return visitType != null && visitType.getName() != null
            ? visitType.getName() : "Booked from slot";
    }

    /* ---------------- helpers ---------------- */

    private void clearHold(AppointmentSlot slot) {
        slot.setHeldUntil(null);
        slot.setHeldByUserId(null);
    }

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // Slot inventory is inherently per-hospital: a super-admin has no
            // rota of their own, so there is nothing sensible to answer.
            throw new BusinessException("An active hospital is required to work with slots.");
        }
        return hospitalId;
    }

    private AppointmentSlot loadScoped(UUID slotId) {
        AppointmentSlot slot = slotRepository.findById(slotId)
            .orElseThrow(() -> new ResourceNotFoundException("Slot not found with ID: " + slotId));
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && (slot.getHospital() == null
            || !hospitalId.equals(slot.getHospital().getId()))) {
            throw new ResourceNotFoundException("Slot not found with ID: " + slotId);
        }
        return slot;
    }

    private AppointmentSlotDTO toDto(AppointmentSlot slot) {
        VisitType visitType = slot.getVisitType();
        return AppointmentSlotDTO.builder()
            .id(slot.getId())
            .staffId(slot.getStaff() != null ? slot.getStaff().getId() : null)
            .staffName(slot.getStaff() != null ? slot.getStaff().getName() : null)
            .departmentId(slot.getDepartment() != null ? slot.getDepartment().getId() : null)
            .departmentName(slot.getDepartment() != null ? slot.getDepartment().getName() : null)
            .visitTypeId(visitType != null ? visitType.getId() : null)
            .visitTypeName(visitType != null ? visitType.getName() : null)
            .durationMinutes(visitType != null ? visitType.getDurationMinutes() : null)
            .slotDate(slot.getSlotDate())
            .startAt(slot.getStartAt())
            .endAt(slot.getEndAt())
            .status(slot.getStatus())
            .appointmentId(slot.getAppointment() != null ? slot.getAppointment().getId() : null)
            .build();
    }
}
