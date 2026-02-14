package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientPrimaryCareMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientPrimaryCare;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientPrimaryCareRequestDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientPrimaryCareRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientPrimaryCareServiceImpl implements PatientPrimaryCareService {

    private final PatientPrimaryCareRepository pcpRepo;
    private final PatientRepository patientRepo;
    private final HospitalRepository hospitalRepo;
    private final UserRoleHospitalAssignmentRepository assignmentRepo;
    private final PatientPrimaryCareMapper mapper;
    private final RoleValidator roleValidator;   // you already have it
    private final MessageSource messageSource;   // for i18n messages if needed

    @Override
    @Transactional
    public PatientPrimaryCareResponseDTO assignPrimaryCare(UUID patientId, PatientPrimaryCareRequestDTO req) {
        Patient patient = patientRepo.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + patientId));

        Hospital hospital = hospitalRepo.findById(req.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + req.getHospitalId()));

        UserRoleHospitalAssignment assignment = assignmentRepo.findById(req.getAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Assignment not found: " + req.getAssignmentId()));

        // Validate: assignment hospital must match requested hospital
        if (!assignment.getHospital().getId().equals(hospital.getId())) {
            throw new BusinessException("Assignment hospital mismatch");
        }

        // Validate role: must be a doctor (or allowed set)
        roleValidator.validateRoleOrThrow(assignment.getId(), hospital.getId(), "ROLE_DOCTOR", Locale.getDefault(), messageSource);

        // End current PCP for this patient at this hospital, if exists
        pcpRepo.findCurrentByPatientAndHospital(patient.getId(), hospital.getId())
            .ifPresent(curr -> {
                curr.setEndDate(LocalDate.now().minusDays(1));
                curr.setCurrent(false);
                pcpRepo.save(curr);
            });

        PatientPrimaryCare entity = mapper.toEntity(req, patient, hospital, assignment);
        return mapper.toDto(pcpRepo.save(entity));
    }

    @Override
    @Transactional
    public PatientPrimaryCareResponseDTO updatePrimaryCare(UUID pcpId, PatientPrimaryCareRequestDTO req) {
        PatientPrimaryCare entity = pcpRepo.findById(pcpId)
            .orElseThrow(() -> new ResourceNotFoundException("Primary care link not found: " + pcpId));

        // Optional updates
        if (req.getStartDate() != null) entity.setStartDate(req.getStartDate());
        if (req.getEndDate() != null) entity.setEndDate(req.getEndDate());
        if (req.getNotes() != null) entity.setNotes(req.getNotes());

        // Optionally support hospital/assignment switch
        if (req.getHospitalId() != null && !req.getHospitalId().equals(entity.getHospital().getId())) {
            Hospital h = hospitalRepo.findById(req.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + req.getHospitalId()));
            entity.setHospital(h);
        }
        if (req.getAssignmentId() != null && !req.getAssignmentId().equals(entity.getAssignment().getId())) {
            UserRoleHospitalAssignment a = assignmentRepo.findById(req.getAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found: " + req.getAssignmentId()));
            // validate reassignment
            if (!a.getHospital().getId().equals(entity.getHospital().getId())) {
                throw new BusinessException("Assignment hospital mismatch");
            }
            roleValidator.validateRoleOrThrow(a.getId(), entity.getHospital().getId(), "ROLE_DOCTOR", Locale.getDefault(), messageSource);
            entity.setAssignment(a);
        }

        // Recompute "current" from dates in @PreUpdate
        return mapper.toDto(pcpRepo.save(entity));
    }

    @Override
    @Transactional
    public PatientPrimaryCareResponseDTO endPrimaryCare(UUID pcpId, LocalDate endDate) {
        if (endDate == null) endDate = LocalDate.now();
        PatientPrimaryCare entity = pcpRepo.findById(pcpId)
            .orElseThrow(() -> new ResourceNotFoundException("Primary care link not found: " + pcpId));

        if (entity.getStartDate() != null && endDate.isBefore(entity.getStartDate())) {
            throw new BusinessException("PCP endDate cannot be before startDate");
        }

        entity.setEndDate(endDate);
        entity.setCurrent(false);
        return mapper.toDto(pcpRepo.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientPrimaryCareResponseDTO> getCurrentPrimaryCare(UUID patientId) {
        return pcpRepo.findByPatient_IdAndCurrentTrue(patientId).map(mapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientPrimaryCareResponseDTO> getPrimaryCareHistory(UUID patientId) {
        return pcpRepo.findByPatient_IdOrderByStartDateDesc(patientId).stream().map(mapper::toDto).toList();
    }

    @Override
    @Transactional
    public void deletePrimaryCare(UUID pcpId) {
        if (!pcpRepo.existsById(pcpId)) {
            throw new ResourceNotFoundException("Primary care link not found: " + pcpId);
        }
        pcpRepo.deleteById(pcpId);
    }
}
