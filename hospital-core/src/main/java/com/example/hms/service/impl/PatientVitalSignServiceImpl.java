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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class PatientVitalSignServiceImpl implements PatientVitalSignService {

    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final PatientVitalSignRepository vitalSignRepository;
    private final PatientVitalSignMapper vitalSignMapper;

    @Override
    public PatientVitalSignResponseDTO recordVital(UUID patientId,
                                                   PatientVitalSignRequestDTO request,
                                                   UUID recorderUserId) {
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));

        PatientHospitalRegistration registration = resolveRegistration(patient, request.getRegistrationId(), request.getHospitalId());
        Hospital hospital = resolveHospital(request.getHospitalId(), registration, patient);
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

        PatientVitalSign saved = vitalSignRepository.save(entity);
        return vitalSignMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientVitalSignResponseDTO> getRecentVitals(UUID patientId, UUID hospitalId, int limit) {
        int size = Math.max(1, limit);
        List<PatientVitalSign> vitals = (hospitalId != null)
            ? vitalSignRepository.findByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId, PageRequest.of(0, size))
            : vitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(patientId, PageRequest.of(0, size));

        return vitals.stream()
            .map(vitalSignMapper::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientVitalSignResponseDTO> getVitals(UUID patientId,
                                                       UUID hospitalId,
                                                       LocalDateTime from,
                                                       LocalDateTime to,
                                                       int page,
                                                       int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1));
        return vitalSignRepository.findWithinRange(patientId, hospitalId, from, to, pageable).stream()
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
            .orElseThrow(() -> new ResourceNotFoundException("Registration not found with ID: " + registrationId));
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
            return hospitalRepository.findById(requestedHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + requestedHospitalId));
        }
        if (patient.getHospitalId() != null) {
            return hospitalRepository.findById(patient.getHospitalId()).orElse(null);
        }
        throw new BusinessException("Unable to resolve hospital context for vital sign capture.");
    }

    private Staff resolveRecorderStaff(UUID staffId, UUID recorderUserId, Hospital hospital) {
        if (staffId != null) {
            return staffRepository.findByIdAndActiveTrue(staffId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff not found or inactive with ID: " + staffId));
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
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found with ID: " + assignmentId));
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
