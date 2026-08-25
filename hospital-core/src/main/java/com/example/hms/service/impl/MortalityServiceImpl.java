package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.MannerOfDeath;
import com.example.hms.enums.MaternalDeathTiming;
import com.example.hms.enums.PlaceOfDeath;
import com.example.hms.enums.RecallStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MortalityMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.Appointment;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.payload.dto.mortality.DeathClosureSummaryDTO;
import com.example.hms.payload.dto.mortality.DeathRecordAmendmentDTO;
import com.example.hms.payload.dto.mortality.DeathRecordRequestDTO;
import com.example.hms.payload.dto.mortality.DeathRecordResponseDTO;
import com.example.hms.payload.dto.mortality.MortalityRegisterDTO;
import com.example.hms.payload.dto.mortality.RecordDeathResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DeathRecordRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import com.example.hms.service.MortalityService;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Death and mortality (Tier 2 item 29).
 *
 * <p>See {@link MortalityService} for why this exists. The interesting part is
 * {@link #recordDeath}: it is a cascade, and each thing it closes is something
 * that would otherwise keep running against a person who has died.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MortalityServiceImpl implements MortalityService {

    private static final String MSG_DEATH_NOT_FOUND = "mortality.deathRecord.notFound";

    /** Admissions still running when the patient dies. */
    private static final Set<AdmissionStatus> OPEN_ADMISSIONS = Set.of(
        AdmissionStatus.PENDING,
        AdmissionStatus.ACTIVE,
        AdmissionStatus.ON_LEAVE,
        AdmissionStatus.AWAITING_DISCHARGE);

    /** Encounters still running. COMPLETED and CANCELLED are already settled. */
    private static final Set<EncounterStatus> OPEN_ENCOUNTERS = Set.of(
        EncounterStatus.SCHEDULED,
        EncounterStatus.ARRIVED,
        EncounterStatus.TRIAGE,
        EncounterStatus.WAITING_FOR_PHYSICIAN,
        EncounterStatus.IN_PROGRESS,
        EncounterStatus.AWAITING_RESULTS,
        EncounterStatus.READY_FOR_DISCHARGE);

    /** Appointments that would still be honoured, and reminded about. */
    private static final Set<AppointmentStatus> LIVE_APPOINTMENTS = Set.of(
        AppointmentStatus.SCHEDULED,
        AppointmentStatus.CONFIRMED,
        AppointmentStatus.RESCHEDULED,
        AppointmentStatus.PENDING);

    /** Recalls that would still chase the patient up. */
    private static final Set<RecallStatus> OPEN_RECALLS = Set.of(
        RecallStatus.PENDING,
        RecallStatus.NOTIFIED,
        RecallStatus.SCHEDULED);

    private static final String CLOSURE_REASON = "Patient deceased";

    private final Clock clock;
    private final DeathRecordRepository deathRecordRepository;
    private final PatientRepository patientRepository;
    private final AdmissionRepository admissionRepository;
    private final EncounterRepository encounterRepository;
    private final AppointmentRepository appointmentRepository;
    private final PatientRecallRepository recallRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final PatientChartAccess patientChartAccess;
    private final MortalityMapper mapper;
    private final RoleValidator roleValidator;

    @Override
    public RecordDeathResponseDTO recordDeath(DeathRecordRequestDTO request) {
        UUID hospitalId = requireHospital();
        Patient patient = patientChartAccess.require(request.getPatientId(), hospitalId);

        if (deathRecordRepository.existsByPatient_Id(patient.getId())) {
            throw new BusinessException(
                "A death is already recorded for this patient. Amend that record rather than "
                    + "filing a second one.");
        }
        if (request.getDiedAt().isAfter(LocalDateTime.now(clock))) {
            throw new BusinessException("The time of death cannot be in the future.");
        }
        validateMortalityFlags(request);

        DeathRecord deathRecord = DeathRecord.builder()
            .patient(patient)
            .hospital(hospitalRef(hospitalId))
            .diedAt(request.getDiedAt())
            .placeOfDeath(request.getPlaceOfDeath() != null ? request.getPlaceOfDeath() : PlaceOfDeath.FACILITY)
            .mannerOfDeath(request.getMannerOfDeath() != null ? request.getMannerOfDeath() : MannerOfDeath.NATURAL)
            .immediateCause(request.getImmediateCause())
            .immediateCauseCode(request.getImmediateCauseCode())
            .underlyingCause(request.getUnderlyingCause())
            .underlyingCauseCode(request.getUnderlyingCauseCode())
            .contributingCauses(request.getContributingCauses())
            .maternalDeath(Boolean.TRUE.equals(request.getMaternalDeath()))
            .maternalDeathTiming(request.getMaternalDeathTiming())
            .perinatalDeath(Boolean.TRUE.equals(request.getPerinatalDeath()))
            .perinatalType(request.getPerinatalType())
            .autopsyRequested(Boolean.TRUE.equals(request.getAutopsyRequested()))
            .certifiedBy(resolveCertifier(request.getCertifiedByStaffId(), hospitalId))
            .certifiedAt(request.getCertifiedByStaffId() != null ? LocalDateTime.now(clock) : null)
            .notes(request.getNotes())
            .recordedBy(currentStaff(hospitalId))
            .build();

        // The flag every sweep and worklist reads. Set before the cascade so
        // anything re-reading the patient mid-transaction already sees it.
        patient.setDeceasedAt(request.getDiedAt());
        patientRepository.save(patient);

        DeathRecord saved = deathRecordRepository.save(deathRecord);
        DeathClosureSummaryDTO closure = closeOpenRecords(patient, hospitalId, saved);

        if (saved.isWhoMaternalDeath()) {
            log.warn("MATERNAL DEATH recorded for patient {} at hospital {} — timing {}",
                patient.getId(), hospitalId, saved.getMaternalDeathTiming());
        }

        return RecordDeathResponseDTO.builder()
            .deathRecord(mapper.toDto(saved))
            .closure(closure)
            .build();
    }

    @Override
    public DeathRecordResponseDTO amendDeathRecord(UUID recordId, DeathRecordAmendmentDTO request) {
        if (!StringUtils.hasText(request.getAmendmentReason())) {
            throw new BusinessException("An amendment reason is required.");
        }
        DeathRecord deathRecord = loadScoped(recordId);

        if (request.getImmediateCause() != null) {
            deathRecord.setImmediateCause(request.getImmediateCause());
        }
        if (request.getImmediateCauseCode() != null) {
            deathRecord.setImmediateCauseCode(request.getImmediateCauseCode());
        }
        if (request.getUnderlyingCause() != null) {
            deathRecord.setUnderlyingCause(request.getUnderlyingCause());
        }
        if (request.getUnderlyingCauseCode() != null) {
            deathRecord.setUnderlyingCauseCode(request.getUnderlyingCauseCode());
        }
        if (request.getContributingCauses() != null) {
            deathRecord.setContributingCauses(request.getContributingCauses());
        }
        if (request.getMannerOfDeath() != null) {
            deathRecord.setMannerOfDeath(request.getMannerOfDeath());
        }
        if (request.getMaternalDeath() != null) {
            deathRecord.setMaternalDeath(request.getMaternalDeath());
            deathRecord.setMaternalDeathTiming(request.getMaternalDeathTiming());
        }
        if (request.getPerinatalDeath() != null) {
            deathRecord.setPerinatalDeath(request.getPerinatalDeath());
            deathRecord.setPerinatalType(request.getPerinatalType());
        }
        if (request.getNotes() != null) {
            deathRecord.setNotes(request.getNotes());
        }

        // Re-check the pairing AFTER the edits: an amendment that turns a death
        // maternal without saying when would break the reporting query.
        if (Boolean.TRUE.equals(deathRecord.getMaternalDeath())
            && deathRecord.getMaternalDeathTiming() == null) {
            throw new BusinessException(
                "A maternal death must state when it occurred relative to the pregnancy.");
        }
        if (Boolean.TRUE.equals(deathRecord.getPerinatalDeath()) && deathRecord.getPerinatalType() == null) {
            throw new BusinessException(
                "A perinatal death must state whether it was a stillbirth or a neonatal death.");
        }

        deathRecord.setAmendedAt(LocalDateTime.now(clock));
        deathRecord.setAmendmentReason(request.getAmendmentReason());

        return mapper.toDto(deathRecordRepository.save(deathRecord));
    }

    @Override
    @Transactional(readOnly = true)
    public DeathRecordResponseDTO getForPatient(UUID patientId) {
        UUID hospitalId = requireHospital();
        patientChartAccess.require(patientId, hospitalId);
        return deathRecordRepository.findByPatient_Id(patientId)
            .map(mapper::toDto)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_DEATH_NOT_FOUND, patientId));
    }

    @Override
    @Transactional(readOnly = true)
    public MortalityRegisterDTO getRegister(LocalDate from, LocalDate to) {
        UUID hospitalId = requireHospital();
        if (from == null || to == null) {
            throw new BusinessException("A period is required to read the mortality register.");
        }
        if (to.isBefore(from)) {
            throw new BusinessException("The end of the period cannot precede its start.");
        }
        // Half-open on the upper bound so a death at 23:59 on the last day is
        // inside the period rather than silently dropped.
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();

        List<DeathRecord> all = deathRecordRepository.findRegister(hospitalId, start, end);

        int maternal = (int) all.stream().filter(DeathRecord::isWhoMaternalDeath).count();
        int lateMaternal = (int) all.stream()
            .filter(d -> Boolean.TRUE.equals(d.getMaternalDeath())
                && d.getMaternalDeathTiming() == MaternalDeathTiming.LATE_MATERNAL)
            .count();
        int perinatal = (int) all.stream()
            .filter(d -> Boolean.TRUE.equals(d.getPerinatalDeath())).count();
        int stillbirths = (int) all.stream()
            .filter(d -> Boolean.TRUE.equals(d.getPerinatalDeath())
                && d.getPerinatalType() == com.example.hms.enums.PerinatalDeathType.STILLBIRTH)
            .count();

        return MortalityRegisterDTO.builder()
            .from(from)
            .to(to)
            .totalDeaths(all.size())
            .maternalDeaths(maternal)
            .lateMaternalDeaths(lateMaternal)
            .perinatalDeaths(perinatal)
            .stillbirths(stillbirths)
            .deaths(all.stream().map(mapper::toDto).toList())
            .build();
    }

    // ── The cascade ─────────────────────────────────────────────────────

    /**
     * Close everything that would otherwise keep running against a person who
     * has died.
     *
     * <p>Each of these is a real failure mode, not tidiness — see the individual
     * methods for what each one would otherwise cost. Every count is reported
     * back to the caller rather than closed silently.
     */
    private DeathClosureSummaryDTO closeOpenRecords(Patient patient, UUID hospitalId, DeathRecord deathRecord) {
        return DeathClosureSummaryDTO.builder()
            .admissionsClosed(closeAdmissions(patient, hospitalId, deathRecord))
            .encountersClosed(closeEncounters(patient, hospitalId, deathRecord))
            .appointmentsCancelled(cancelFutureAppointments(patient, hospitalId, deathRecord))
            .recallsClosed(closeRecalls(patient, hospitalId))
            .build();
    }

    /** An open admission holds a bed and keeps the patient on the ward board. */
    private int closeAdmissions(Patient patient, UUID hospitalId, DeathRecord deathRecord) {
        int closed = 0;
        for (Admission admission : admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patient.getId())) {
            if (!inScope(admission.getHospital(), hospitalId) || !OPEN_ADMISSIONS.contains(admission.getStatus())) {
                continue;
            }
            // AdmissionStatus.DECEASED has existed since V1 and nothing has ever
            // written it — the MLLP visit projection only reads it as an
            // exclusion. This is its first writer.
            admission.setStatus(AdmissionStatus.DECEASED);
            admissionRepository.save(admission);
            if (deathRecord.getAdmissionId() == null) {
                deathRecord.setAdmissionId(admission.getId());
            }
            closed++;
        }
        return closed;
    }

    /** An encounter left open keeps the patient on a worklist someone will work. */
    private int closeEncounters(Patient patient, UUID hospitalId, DeathRecord deathRecord) {
        int closed = 0;
        for (Encounter encounter : encounterRepository.findByPatient_Id(patient.getId())) {
            if (!inScope(encounter.getHospital(), hospitalId) || !OPEN_ENCOUNTERS.contains(encounter.getStatus())) {
                continue;
            }
            encounter.setStatus(EncounterStatus.COMPLETED);
            encounterRepository.save(encounter);
            if (deathRecord.getEncounterId() == null) {
                deathRecord.setEncounterId(encounter.getId());
            }
            closed++;
        }
        return closed;
    }

    /**
     * A live appointment is a slot nobody will use and a reminder to a bereaved
     * family. Only FUTURE ones are cancelled — a past appointment the patient
     * actually attended is history, and rewriting it would falsify the record.
     */
    private int cancelFutureAppointments(Patient patient, UUID hospitalId, DeathRecord deathRecord) {
        int cancelled = 0;
        LocalDate diedOn = deathRecord.getDiedAt().toLocalDate();
        for (Appointment appointment
            : appointmentRepository.findByPatient_IdAndAppointmentDateAfter(patient.getId(), diedOn)) {
            if (!inScope(appointment.getHospital(), hospitalId)
                || !LIVE_APPOINTMENTS.contains(appointment.getStatus())) {
                continue;
            }
            appointment.setStatus(AppointmentStatus.CANCELLED);
            appointment.setNotes(appendReason(appointment.getNotes()));
            appointmentRepository.save(appointment);
            cancelled++;
        }
        return cancelled;
    }

    /** An open recall is a follow-up chase for someone who cannot be chased. */
    private int closeRecalls(Patient patient, UUID hospitalId) {
        int closed = 0;
        for (PatientRecall recall : recallRepository.findByPatient_IdAndStatusIn(patient.getId(), OPEN_RECALLS)) {
            if (!inScope(recall.getHospital(), hospitalId)) {
                continue;
            }
            recall.setStatus(RecallStatus.CANCELLED);
            recallRepository.save(recall);
            closed++;
        }
        return closed;
    }

    private String appendReason(String existing) {
        return StringUtils.hasText(existing) ? existing + " — " + CLOSURE_REASON : CLOSURE_REASON;
    }

    // ── Guards and helpers ──────────────────────────────────────────────

    private void validateMortalityFlags(DeathRecordRequestDTO request) {
        if (Boolean.TRUE.equals(request.getMaternalDeath()) && request.getMaternalDeathTiming() == null) {
            throw new BusinessException(
                "A maternal death must state when it occurred relative to the pregnancy — the WHO "
                    + "definition depends on it, and a late maternal death is reported separately.");
        }
        if (Boolean.TRUE.equals(request.getPerinatalDeath()) && request.getPerinatalType() == null) {
            throw new BusinessException(
                "A perinatal death must state whether it was a stillbirth or a neonatal death: a "
                    + "stillborn infant was never born alive, so the two are never summed.");
        }
    }

    /** 404-not-403 — a record at another hospital is indistinguishable from a missing one. */
    private DeathRecord loadScoped(UUID recordId) {
        DeathRecord deathRecord = deathRecordRepository.findById(recordId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_DEATH_NOT_FOUND, recordId));
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope != null && deathRecord.getHospital() != null
            && !scope.equals(deathRecord.getHospital().getId())) {
            throw new ResourceNotFoundException(MSG_DEATH_NOT_FOUND, recordId);
        }
        return deathRecord;
    }

    /**
     * A patient linked to several facilities can have open records at each. This
     * cascade only closes what belongs to the recording hospital — reaching into
     * another facility's ward board would be acting outside the caller's scope.
     */
    private boolean inScope(Hospital hospital, UUID hospitalId) {
        return hospital == null || Objects.equals(hospital.getId(), hospitalId);
    }

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException(
                "An active hospital is required: a death is certified at a facility.");
        }
        return hospitalId;
    }

    private Hospital hospitalRef(UUID hospitalId) {
        return hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));
    }

    private Staff resolveCertifier(UUID staffId, UUID hospitalId) {
        if (staffId == null) {
            return null;
        }
        return staffRepository.findById(staffId)
            .filter(s -> s.getHospital() == null || Objects.equals(s.getHospital().getId(), hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", staffId));
    }

    private Staff currentStaff(UUID hospitalId) {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || hospitalId == null) {
            return null;
        }
        return staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
    }
}
