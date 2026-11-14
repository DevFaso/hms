package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientPrimaryCareMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientPrimaryCare;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientPrimaryCareRequestDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientPrimaryCareRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientPrimaryCareServiceImpl implements PatientPrimaryCareService {
    /** QA D401: a patient write lands only at a hospital the patient is registered at. */
    private final com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;

    private static final String PRIMARY_CARE_LINK_NOT_FOUND_KEY = "primaryCare.link.notFound";

    private final PatientPrimaryCareRepository pcpRepo;
    private final PatientRepository patientRepo;
    private final PatientHospitalRegistrationRepository registrationRepo;
    private final HospitalRepository hospitalRepo;
    private final UserRoleHospitalAssignmentRepository assignmentRepo;
    private final PatientPrimaryCareMapper mapper;
    private final RoleValidator roleValidator;   // you already have it

    @Override
    @Transactional
    public PatientPrimaryCareResponseDTO assignPrimaryCare(UUID patientId, PatientPrimaryCareRequestDTO req) {
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(
            req.getHospitalId(), "hospital.notFound", req.getHospitalId());
        Patient patient = patientRepo.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));

        Hospital hospital = hospitalRepo.findClinicalById(req.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", req.getHospitalId()));
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospital.getId());

        com.example.hms.service.support.DeceasedPatientGuard.refuseNewCare(patient);
        UserRoleHospitalAssignment assignment = assignmentRepo.findById(req.getAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("roleAssignment.notFound", req.getAssignmentId()));

        // Validate: assignment hospital must match requested hospital
        if (!assignment.getHospital().getId().equals(hospital.getId())) {
            throw new com.example.hms.exception.FieldBusinessException("hospitalId", "primaryCare.assignmentHospitalMismatch");
        }

        // Validate role: the chosen assignment itself is an active doctor assignment at this hospital
        // (#1096 review: its id used to be passed as a user id, then its user was checked instead).
        roleValidator.requireAssignmentHolds(assignment, hospital.getId(), "ROLE_DOCTOR");

        // End current PCP for this patient at this hospital, if exists
        pcpRepo.findCurrentByPatientAndHospital(patient.getId(), hospital.getId())
            .ifPresent(curr -> {
                curr.setEndDate(endDateOnReassignment(curr.getStartDate(), LocalDate.now()));
                curr.setCurrent(false);
                pcpRepo.save(curr);
            });

        PatientPrimaryCare entity = mapper.toEntity(req, patient, hospital, assignment);
        return mapper.toDto(pcpRepo.save(entity));
    }

    /**
     * The end date of a link a reassignment replaces: the day before today, never before the
     * link's own start. A same-day reassignment used to end a link the day before it began
     * (« 14 nov. 2026 – 13 nov. 2026 », QA D1851); it now ends the day it started.
     */
    static LocalDate endDateOnReassignment(LocalDate start, LocalDate today) {
        LocalDate end = today.minusDays(1);
        return start != null && end.isBefore(start) ? start : end;
    }

    @Override
    @Transactional
    public PatientPrimaryCareResponseDTO updatePrimaryCare(UUID pcpId, PatientPrimaryCareRequestDTO req) {
        PatientPrimaryCare entity = pcpRepo.findById(pcpId)
            .orElseThrow(() -> new ResourceNotFoundException(PRIMARY_CARE_LINK_NOT_FOUND_KEY, pcpId));

        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(
            entity.getHospital(), PRIMARY_CARE_LINK_NOT_FOUND_KEY, pcpId);
        if (req.getHospitalId() != null) {
            com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(
                req.getHospitalId(), "hospital.notFound", req.getHospitalId());
        }

        // Optional updates
        if (req.getStartDate() != null) entity.setStartDate(req.getStartDate());
        if (req.getEndDate() != null) entity.setEndDate(req.getEndDate());
        if (req.getNotes() != null) entity.setNotes(req.getNotes());

        // Optionally support hospital/assignment switch
        if (req.getHospitalId() != null && !req.getHospitalId().equals(entity.getHospital().getId())) {
            Hospital h = hospitalRepo.findClinicalById(req.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", req.getHospitalId()));
            patientRegistrationGuard.requireRegisteredAt(entity.getPatient().getId(), h.getId());
            entity.setHospital(h);
        }
        if (req.getAssignmentId() != null && !req.getAssignmentId().equals(entity.getAssignment().getId())) {
            UserRoleHospitalAssignment a = assignmentRepo.findById(req.getAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("roleAssignment.notFound", req.getAssignmentId()));
            // validate reassignment
            if (!a.getHospital().getId().equals(entity.getHospital().getId())) {
                throw new com.example.hms.exception.FieldBusinessException("assignmentId", "primaryCare.assignmentHospitalMismatch");
            }
            roleValidator.requireAssignmentHolds(a, entity.getHospital().getId(), "ROLE_DOCTOR");
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
            .orElseThrow(() -> new ResourceNotFoundException(PRIMARY_CARE_LINK_NOT_FOUND_KEY, pcpId));
        // QA r5 tenancy sweep: a write loads under the tenant guard.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(entity.getHospital(), PRIMARY_CARE_LINK_NOT_FOUND_KEY, pcpId);

        if (entity.getStartDate() != null && endDate.isBefore(entity.getStartDate())) {
            throw new com.example.hms.exception.FieldBusinessException("endDate", "primaryCare.endBeforeStart");
        }

        entity.setEndDate(endDate);
        entity.setCurrent(false);
        return mapper.toDto(pcpRepo.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientPrimaryCareResponseDTO> getCurrentPrimaryCare(UUID patientId) {
        // QA r41: a hospital reads only its own patients' links; another hospital's link reads as
        // no link at all (as a missing one does). A verified super-admin in global view reads them all.
        // QA D1850: global view (or the patient's own record) has one current link per hospital
        // since #1096, so it reads the list and answers the newest; it used to read an Optional
        // and answer 500 for a patient with a doctor at two hospitals.
        UUID acting = com.example.hms.security.tenant.TenantOwnership.actingHospitalIdOrNullForGlobalOrOwnPatient();
        Optional<PatientPrimaryCare> link = acting == null
            ? pcpRepo.findByPatient_IdAndCurrentTrueOrderByStartDateDesc(patientId).stream().findFirst()
            : pcpRepo.findCurrentByPatientAndHospital(patientId, acting);
        return link.map(mapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientPrimaryCareResponseDTO> getOwnPrimaryCareLinks(UUID patientId) {
        // QA D1850: the patient's own care team spans every hospital that registers them, whatever
        // hospital the request names, and never a hospital where they hold no active registration.
        Set<UUID> registeredAt = registrationRepo.findByPatientId(patientId).stream()
            .filter(PatientHospitalRegistration::isActive)
            .map(PatientHospitalRegistration::getHospital)
            .filter(Objects::nonNull)
            .map(Hospital::getId)
            .collect(Collectors.toSet());
        if (registeredAt.isEmpty()) {
            return List.of();
        }
        return pcpRepo.findByPatient_IdOrderByStartDateDesc(patientId).stream()
            .filter(link -> link.getHospital() != null && registeredAt.contains(link.getHospital().getId()))
            .map(mapper::toDto)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientPrimaryCareResponseDTO> getPrimaryCareHistory(UUID patientId) {
        // QA r41: the acting hospital's own links only; global view keeps every link.
        UUID acting = com.example.hms.security.tenant.TenantOwnership.actingHospitalIdOrNullForGlobalOrOwnPatient();
        return pcpRepo.findByPatient_IdOrderByStartDateDesc(patientId).stream()
            .filter(link -> acting == null || (link.getHospital() != null && acting.equals(link.getHospital().getId())))
            .map(mapper::toDto)
            .toList();
    }

    @Override
    @Transactional
    public void deletePrimaryCare(UUID pcpId) {
        // QA r5 tenancy sweep: a delete loads under the tenant guard first.
        PatientPrimaryCare entity = pcpRepo.findById(pcpId)
            .orElseThrow(() -> new ResourceNotFoundException(PRIMARY_CARE_LINK_NOT_FOUND_KEY, pcpId));
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(entity.getHospital(), PRIMARY_CARE_LINK_NOT_FOUND_KEY, pcpId);
        pcpRepo.delete(entity);
    }
}
