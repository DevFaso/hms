package com.example.hms.service.registry;

import com.example.hms.enums.CareProgram;
import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.ProgramEnrollment;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.registry.ProgramEnrollmentRequestDTO;
import com.example.hms.payload.dto.registry.ProgramEnrollmentResponseDTO;
import com.example.hms.payload.dto.registry.ProgramStatusUpdateDTO;
import com.example.hms.payload.dto.registry.ProgramVisitDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ProgramEnrollmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Disease-programme registries (Tier 2 item 35).
 *
 * <p>The registry answers "who is in the HIV programme here, and who among
 * them is overdue" — the question the paper register answers today and the
 * question item 36's defaulter tracing automates next. This service owns the
 * enrolment lifecycle; it deliberately does NOT own outreach (that is item
 * 36, on {@code PatientOutreachNotifier}'s existing transport) and does NOT
 * decide visit cadence (typed in by the clinician — see {@link CareProgram}
 * for why the server refuses to have an opinion).
 *
 * <p>Tenancy: 404-not-403 throughout (#483's stance). A patient not
 * registered at the caller's hospital is indistinguishable from one that
 * does not exist, and so is another hospital's enrolment row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProgramEnrollmentService {

    private final ProgramEnrollmentRepository enrollmentRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final RoleValidator roleValidator;
    private final Clock clock;

    @Transactional
    public ProgramEnrollmentResponseDTO enroll(UUID patientId, ProgramEnrollmentRequestDTO request) {
        UUID hospitalId = requireHospital();
        Patient patient = requirePatientInTenant(patientId, hospitalId);

        enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
                patientId, hospitalId, request.getProgram(), ProgramEnrollmentStatus.ACTIVE)
            .ifPresent(existing -> {
                throw new BusinessException(
                    "The patient is already enrolled in this programme. Close the existing "
                        + "enrolment first if this is a new episode.");
            });

        LocalDate enrolledOn = request.getEnrolledOn() != null
            ? request.getEnrolledOn() : LocalDate.now(clock);

        Hospital hospital = hospitalRepository.getReferenceById(hospitalId);
        Staff enrolledBy = resolveCurrentStaff(hospitalId);

        ProgramEnrollment saved = enrollmentRepository.save(ProgramEnrollment.builder()
            .patient(patient)
            .hospital(hospital)
            .program(request.getProgram())
            .status(ProgramEnrollmentStatus.ACTIVE)
            .enrolledOn(enrolledOn)
            .enrolledBy(enrolledBy)
            .visitCadenceDays(request.getVisitCadenceDays())
            .nextExpectedVisit(enrolledOn.plusDays(request.getVisitCadenceDays()))
            .notes(trimToNull(request.getNotes()))
            .build());

        log.info("Programme enrolment: patient {} into {} at hospital {} (cadence {}d)",
            patientId, request.getProgram(), hospitalId, request.getVisitCadenceDays());
        return toDto(saved, hospitalId);
    }

    @Transactional
    public ProgramEnrollmentResponseDTO updateStatus(UUID patientId, UUID enrollmentId,
                                                     ProgramStatusUpdateDTO request) {
        UUID hospitalId = requireHospital();
        ProgramEnrollment enrollment = requireEnrollmentInTenant(enrollmentId, patientId, hospitalId);

        ProgramEnrollmentStatus target = request.getStatus();
        if (enrollment.getStatus() == target) {
            throw new BusinessException("The enrolment is already in that state.");
        }

        String reason = trimToNull(request.getReason());
        if (target == ProgramEnrollmentStatus.ACTIVE) {
            // Re-opening a wrongly closed enrolment. The reason field is
            // refused rather than ignored: text supplied here would vanish
            // silently, and the caller deserves to know it has nowhere to go.
            if (reason != null) {
                throw new BusinessException(
                    "A move back to ACTIVE takes no reason - use the enrolment notes.");
            }
            enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
                    patientId, hospitalId, enrollment.getProgram(), ProgramEnrollmentStatus.ACTIVE)
                .ifPresent(other -> {
                    throw new BusinessException(
                        "The patient already has an active enrolment in this programme.");
                });
            enrollment.setStatus(ProgramEnrollmentStatus.ACTIVE);
            enrollment.setClosedOn(null);
            enrollment.setClosureReason(null);
        } else {
            if (reason == null) {
                throw new BusinessException(
                    "Closing an enrolment needs a reason - it is the outcome the "
                        + "programme reports, not bookkeeping.");
            }
            enrollment.setStatus(target);
            enrollment.setClosedOn(LocalDate.now(clock));
            enrollment.setClosureReason(reason);
        }

        ProgramEnrollment saved = enrollmentRepository.save(enrollment);
        log.info("Programme enrolment {} -> {} (patient {}, {})",
            enrollmentId, target, patientId, enrollment.getProgram());
        return toDto(saved, hospitalId);
    }

    @Transactional
    public ProgramEnrollmentResponseDTO recordVisit(UUID patientId, UUID enrollmentId,
                                                    ProgramVisitDTO request) {
        UUID hospitalId = requireHospital();
        ProgramEnrollment enrollment = requireEnrollmentInTenant(enrollmentId, patientId, hospitalId);

        if (enrollment.getStatus() != ProgramEnrollmentStatus.ACTIVE) {
            throw new BusinessException(
                "Only an active enrolment records programme visits. Re-open it first.");
        }

        LocalDate visitDate = request != null && request.getVisitDate() != null
            ? request.getVisitDate() : LocalDate.now(clock);
        if (visitDate.isBefore(enrollment.getEnrolledOn())) {
            throw new BusinessException("A programme visit cannot predate the enrolment.");
        }

        enrollment.setLastVisitOn(visitDate);
        enrollment.setNextExpectedVisit(visitDate.plusDays(enrollment.getVisitCadenceDays()));
        return toDto(enrollmentRepository.save(enrollment), hospitalId);
    }

    @Transactional(readOnly = true)
    public List<ProgramEnrollmentResponseDTO> registry(CareProgram program,
                                                       ProgramEnrollmentStatus status) {
        UUID hospitalId = requireHospital();
        ProgramEnrollmentStatus effective = status != null ? status : ProgramEnrollmentStatus.ACTIVE;
        return enrollmentRepository.findRegistry(hospitalId, program, effective).stream()
            .map(e -> toDto(e, hospitalId))
            .toList();
    }

    @Transactional(readOnly = true)
    public Map<ProgramEnrollmentStatus, Long> registryCounts(CareProgram program) {
        UUID hospitalId = requireHospital();
        Map<ProgramEnrollmentStatus, Long> counts = new EnumMap<>(ProgramEnrollmentStatus.class);
        for (Object[] row : enrollmentRepository.countByStatus(hospitalId, program)) {
            counts.put((ProgramEnrollmentStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public List<ProgramEnrollmentResponseDTO> patientEnrollments(UUID patientId) {
        UUID hospitalId = requireHospital();
        requirePatientInTenant(patientId, hospitalId);
        return enrollmentRepository.findByPatient(hospitalId, patientId).stream()
            .map(e -> toDto(e, hospitalId))
            .toList();
    }

    // ── guards ──────────────────────────────────────────────────────────

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // A registry without a hospital is every hospital's registry.
            throw new BusinessException("An active hospital context is required.");
        }
        return hospitalId;
    }

    private Patient requirePatientInTenant(UUID patientId, UUID hospitalId) {
        return patientRepository.findById(patientId)
            .filter(p -> p.isRegisteredInHospital(hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));
    }

    private ProgramEnrollment requireEnrollmentInTenant(UUID enrollmentId, UUID patientId,
                                                        UUID hospitalId) {
        return enrollmentRepository.findById(enrollmentId)
            .filter(e -> e.getHospital() != null && hospitalId.equals(e.getHospital().getId()))
            .filter(e -> e.getPatient() != null && patientId.equals(e.getPatient().getId()))
            .orElseThrow(() -> new ResourceNotFoundException("program.enrollment.notfound"));
    }

    private Staff resolveCurrentStaff(UUID hospitalId) {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
    }

    // ── mapping ─────────────────────────────────────────────────────────

    private ProgramEnrollmentResponseDTO toDto(ProgramEnrollment e, UUID hospitalId) {
        Patient patient = e.getPatient();
        String enrolledByName = null;
        if (e.getEnrolledBy() != null && e.getEnrolledBy().getUser() != null) {
            var user = e.getEnrolledBy().getUser();
            enrolledByName = (user.getFirstName() + " " + user.getLastName()).trim();
        }
        long overdueDays = 0;
        if (e.getStatus() == ProgramEnrollmentStatus.ACTIVE) {
            LocalDate today = LocalDate.now(clock);
            if (e.getNextExpectedVisit() != null && e.getNextExpectedVisit().isBefore(today)) {
                overdueDays = ChronoUnit.DAYS.between(e.getNextExpectedVisit(), today);
            }
        }
        return ProgramEnrollmentResponseDTO.builder()
            .id(e.getId())
            .hospitalId(e.getHospital().getId())
            .patientId(patient.getId())
            .patientName(patient.getFullName())
            .mrn(patient.getMrnForHospital(hospitalId))
            .phoneNumber(patient.getPhoneNumberPrimary())
            .program(e.getProgram())
            .status(e.getStatus())
            .enrolledOn(e.getEnrolledOn())
            .enrolledByName(enrolledByName)
            .visitCadenceDays(e.getVisitCadenceDays())
            .lastVisitOn(e.getLastVisitOn())
            .nextExpectedVisit(e.getNextExpectedVisit())
            .overdueDays(overdueDays)
            .notes(e.getNotes())
            .closedOn(e.getClosedOn())
            .closureReason(e.getClosureReason())
            .createdAt(e.getCreatedAt())
            .build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
