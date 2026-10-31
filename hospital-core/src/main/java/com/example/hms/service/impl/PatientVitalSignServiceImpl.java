package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientVitalSignMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.PatientVitalSignService;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.support.PatientChartAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PatientVitalSignServiceImpl implements PatientVitalSignService {

    /** The key PatientChartAccess throws: a refused staff read reads as "no such patient". */
    private static final String MSG_PATIENT_NOT_FOUND = "patient.notFound";

    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final PatientVitalSignRepository vitalSignRepository;
    private final PatientVitalSignMapper vitalSignMapper;
    /** QA D401: a vital lands only at a hospital the patient is registered at. */
    private final com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;
    private final PatientChartAccess patientChartAccess;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final com.example.hms.service.vitals.News2EscalationService news2EscalationService;
    private final com.example.hms.service.support.DischargedPatientGuard dischargedPatientGuard;

    @Override
    public PatientVitalSignResponseDTO recordVital(UUID patientId,
                                                   PatientVitalSignRequestDTO request,
                                                   UUID recorderUserId) {
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patientId));

        PatientHospitalRegistration registration = resolveRegistration(patient, request.getRegistrationId(), request.getHospitalId());
        Hospital hospital = resolveHospital(request.getHospitalId(), registration, patient);
        // A hospital named in the body (no registration found) is a claim: the
        // patient must be registered there, as for every other chart write.
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospital == null ? null : hospital.getId());
        // QA D525: the chart's vitals endpoint skipped the nurse station's
        // discharged-patient check; both now go through the same guard. A
        // patient reporting their own home reading (the portal path) is not
        // being charted on a ward, so it is not refused.
        boolean selfReported = recorderUserId != null && patient.getUser() != null
            && recorderUserId.equals(patient.getUser().getId());
        if (hospital != null && !selfReported) {
            dischargedPatientGuard.requireNotDischargedWithoutVisit(
                patient.getId(), hospital.getId(), "nurse.vitals.patientDischarged");
        }
        Staff staff = resolveRecorderStaff(request.getRecordedByStaffId(), recorderUserId, hospital);
        UserRoleHospitalAssignment assignment = resolveAssignment(request.getRecordedByAssignmentId(), staff);

        if (assignment != null && assignment.getHospital() != null && hospital != null
            && !assignment.getHospital().getId().equals(hospital.getId())) {
            throw new BusinessException("Recorder assignment is not associated with the resolved hospital context.");
        }
        if (staff != null && hospital != null && staff.getHospital() != null
            && !staff.getHospital().getId().equals(hospital.getId())) {
            throw new BusinessException("Recorder staff is not assigned to the resolved hospital context.");
        }

        PatientVitalSign entity = PatientVitalSign.builder()
            .patient(patient)
            .registration(registration)
            .hospital(hospital)
            .recordedByStaff(staff)
            .recordedByAssignment(assignment)
            .build();

        vitalSignMapper.applyRequestToEntity(request, entity);
        // QA D1077 (round 19): this endpoint stored a head circumference for a
        // 67-year-old; the nurse station's rule now holds on every vitals write.
        com.example.hms.service.support.PatientMeasureRules.requireVitalSignApplicable(
            entity, null, java.time.LocalDate.now(java.time.ZoneId.systemDefault()));

        // NEWS2 MEDIUM+ auto-flags the bundle significant (P3 #25b): the
        // aggregate catches multi-parameter deterioration that no single
        // caller-supplied flag or per-vital threshold would.
        if (com.example.hms.utility.NewsScoreCalculator.applies(entity)
                && com.example.hms.utility.NewsScoreCalculator.score(entity).total() >= 5) {
            entity.setClinicallySignificant(true);
        }

        PatientVitalSign saved = vitalSignRepository.save(entity);
        // QA D207: the same NEWS2 escalation as the nurse station's capture.
        String recorderUsername = staff != null && staff.getUser() != null ? staff.getUser().getUsername() : null;
        news2EscalationService.escalateIfNeeded(saved, patientId, hospital != null ? hospital.getId() : null,
            recorderUsername);
        return vitalSignMapper.toResponse(saved);
    }

    /**
     * Staff read. This used to be {@code hospitalId != null ? scoped :
     * patient-wide} with no chart gate and no reach recording at all: a
     * super-admin in global view got every hospital's vitals for any patient
     * id, undisclosed; and a scoped caller got the vitals of a patient their
     * hospital had no registration or treatment relationship for. Now the
     * chart gate, the readable hospitals the rest of the chart uses (E9 #60 —
     * the snapshot drawer already reads vitals this way), and every foreign
     * row accounted.
     */
    @Override
    @Transactional(readOnly = true)
    public List<PatientVitalSignResponseDTO> getRecentVitals(UUID patientId, UUID hospitalId, int limit) {
        Patient patient = requireScopedChart(patientId, hospitalId);
        UUID requesterUserId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patient.getId(), hospitalId);
        List<PatientVitalSign> vitals = vitalSignRepository.findByPatient_IdAndHospital_IdInOrderByRecordedAtDesc(
            patient.getId(), readable, PageRequest.of(0, Math.max(1, limit)));
        reachRecorder.recordReach(patient.getId(), hospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(
                vitals.stream().map(v -> CrossHospitalReachRecorder.hospitalIdOf(v.getHospital())).toList(), hospitalId),
            "Cross-hospital vital signs read on the treatment relationship");
        return vitals.stream()
            .map(vitalSignMapper::toResponse)
            .toList();
    }

    /**
     * The patient's own vitals. The portal has derived the patient from the
     * principal (or verified the proxy) already; the staff gate is the wrong
     * question here — see {@link PatientChartAccess#requireOwnRecord}.
     */
    @Override
    @Transactional(readOnly = true)
    public List<PatientVitalSignResponseDTO> getRecentVitalsForPatientPortal(UUID patientId, int limit) {
        Patient patient = patientChartAccess.requireOwnRecord(patientId);
        return vitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(patient.getId(), PageRequest.of(0, Math.max(1, limit)))
            .stream()
            .map(vitalSignMapper::toResponse)
            .toList();
    }

    /**
     * Staff history read, behind the same controller as {@link #getRecentVitals}
     * and with the same hole: {@code findWithinRange} drops the hospital
     * predicate on a null id. Closing only the recent read would leave this
     * door open on the same rows. It stays at the caller's hospital, as it was.
     */
    @Override
    @Transactional(readOnly = true)
    public List<PatientVitalSignResponseDTO> getVitals(UUID patientId,
                                                       UUID hospitalId,
                                                       LocalDateTime from,
                                                       LocalDateTime to,
                                                       int page,
                                                       int size) {
        Patient patient = requireScopedChart(patientId, hospitalId);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1));
        return vitalSignRepository.findWithinRange(patient.getId(), hospitalId, from, to, pageable).stream()
            .map(vitalSignMapper::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientResponseDTO.VitalSnapshot> getLatestSnapshot(UUID patientId, UUID hospitalId) {
        Optional<PatientVitalSign> latest = (hospitalId != null)
            ? vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId)
            : vitalSignRepository.findFirstByPatient_IdOrderByRecordedAtDesc(patientId);
        return latest.map(vitalSignMapper::toSnapshot);
    }

    /**
     * The chart gate, then refuse a missing scope. {@code require} already
     * refuses a null scope for anyone but a super-admin; a super-admin in
     * global view has no acting hospital to read at or to account a
     * disclosure against, so the read is refused as the lab and medication
     * reads are (#735) — with the key {@code require} throws, so it reads as
     * "no such patient". Refused before any vitals are read.
     */
    private Patient requireScopedChart(UUID patientId, UUID hospitalId) {
        Patient patient = patientChartAccess.require(patientId, hospitalId);
        if (hospitalId == null) {
            log.warn("Staff vital-sign read refused: no hospital scope resolved for patient {}", patient.getId());
            throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patient.getId());
        }
        return patient;
    }

    private PatientHospitalRegistration resolveRegistration(Patient patient, UUID registrationId, UUID hospitalId) {
        if (registrationId == null) {
            if (hospitalId != null) {
                return registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patient.getId(), hospitalId)
                    .orElse(null);
            }
            return registrationRepository.findByPatientId(patient.getId()).stream()
                .filter(PatientHospitalRegistration::isActive)
                .findFirst()
                .orElse(null);
        }
        PatientHospitalRegistration registration = registrationRepository.findById(registrationId)
            .orElseThrow(() -> new ResourceNotFoundException("registration.notFound", registrationId));
        if (!registration.getPatient().getId().equals(patient.getId())) {
            throw new BusinessException("Registration does not belong to the specified patient.");
        }
        return registration;
    }

    private Hospital resolveHospital(UUID requestedHospitalId,
                                     PatientHospitalRegistration registration,
                                     Patient patient) {
        if (registration != null && registration.getHospital() != null) {
            return registration.getHospital();
        }
        if (requestedHospitalId != null) {
            return hospitalRepository.findClinicalById(requestedHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", requestedHospitalId));
        }
        if (patient.getHospitalId() != null) {
            return hospitalRepository.findClinicalById(patient.getHospitalId()).orElse(null);
        }
        throw new BusinessException("Unable to resolve hospital context for vital sign capture.");
    }

    private Staff resolveRecorderStaff(UUID staffId, UUID recorderUserId, Hospital hospital) {
        if (staffId != null) {
            Staff staff = staffRepository.findByIdAndActiveTrue(staffId)
                .orElseThrow(() -> new ResourceNotFoundException("staff.not.found.or.inactive", staffId));
            // QA D503 class: the recorder is the caller, not a colleague named in the body.
            // (the hospital check on the recorder follows, with its own message)
            com.example.hms.security.CallerIdentity.requireCallerUnlessSuperAdmin(staff);
            return staff;
        }
        if (recorderUserId == null) {
            return null;
        }
        if (hospital != null) {
            return staffRepository.findByUserIdAndHospitalId(recorderUserId, hospital.getId()).orElse(null);
        }
        return staffRepository.findFirstByUserIdOrderByCreatedAtAsc(recorderUserId).orElse(null);
    }

    private UserRoleHospitalAssignment resolveAssignment(UUID assignmentId, Staff staff) {
        if (assignmentId != null) {
            UserRoleHospitalAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("roleAssignment.notFound", assignmentId));
            if (!Boolean.TRUE.equals(assignment.getActive())) {
                throw new BusinessException("Assignment is not active for vital sign capture.");
            }
            return assignment;
        }
        if (staff != null) {
            return staff.getAssignment();
        }
        return null;
    }
}
