package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ImmunizationMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.medicalhistory.ImmunizationRequestDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImmunizationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.ImmunizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ImmunizationServiceImpl implements ImmunizationService {
    private static final String ENCOUNTER_NOT_FOUND_KEY = "encounter.notfound";
    private static final String IMMUNIZATION_NOT_FOUND_KEY = "immunization.notFound";



    private final ImmunizationRepository immunizationRepository;
    private final PatientRepository patientRepository;
    private final com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final ImmunizationMapper immunizationMapper;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;

    @Override
    public ImmunizationResponseDTO createImmunization(ImmunizationRequestDTO requestDTO) {
        log.info("Creating immunization for patient: {}", requestDTO.getPatientId());

        Patient patient = patientRepository.findById(requestDTO.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", requestDTO.getPatientId()));

        Hospital hospital = hospitalRepository.findClinicalById(requestDTO.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", requestDTO.getHospitalId()));
        // QA r5 tenancy sweep: the row is written at the caller's acting hospital; the body's id is a claim.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(hospital, "hospital.notFound", requestDTO.getHospitalId());
        // QA D401: the patient must be registered at the hospital the row is written in.
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospital.getId());

        // QA D503 class: the vaccinator is the caller, not a colleague named in the body.
        Staff administeredBy = com.example.hms.security.CallerIdentity.resolveActor(staffRepository, requestDTO.getAdministeredByStaffId(), hospital.getId());

        Encounter encounter = null;
        if (requestDTO.getEncounterId() != null) {
            encounter = encounterRepository.findById(requestDTO.getEncounterId())
                    .orElseThrow(() -> new ResourceNotFoundException(ENCOUNTER_NOT_FOUND_KEY, requestDTO.getEncounterId()));
            // QA r5 tenancy sweep: the linked encounter is one of the caller's hospital.
            com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(encounter.getHospital(), ENCOUNTER_NOT_FOUND_KEY, requestDTO.getEncounterId());
        }

        PatientImmunization immunization = immunizationMapper.toEntity(requestDTO, patient, hospital, administeredBy, encounter);
        PatientImmunization savedImmunization = immunizationRepository.save(immunization);

        log.info("Immunization created with id: {}", savedImmunization.getId());
        return immunizationMapper.toResponseDTO(savedImmunization);
    }

    @Override
    @Transactional(readOnly = true)
    public ImmunizationResponseDTO getImmunizationById(UUID id) {
        log.debug("Fetching immunization with id: {}", id);
        
        PatientImmunization immunization = immunizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(IMMUNIZATION_NOT_FOUND_KEY, id));

        return immunizationMapper.toResponseDTO(immunization);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImmunizationResponseDTO> getImmunizationsByPatientId(UUID patientId) {
        log.debug("Fetching immunizations for patient: {}", patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }

        // E9 #59d — the immunization record follows the patient across the
        // readable hospitals when the caller acts in one; a super-admin in
        // global view keeps the unscoped read. Foreign rows are accounted.
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID actingHospitalId = ctx.pinnedHospitalId();
        List<PatientImmunization> immunizations;
        if (actingHospitalId == null) {
            immunizations = immunizationRepository.findByPatient_IdOrderByAdministrationDateDesc(patientId);
        } else {
            UUID requesterUserId = ctx.getPrincipalUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, actingHospitalId);
            immunizations = immunizationRepository.findByPatient_IdAndHospital_IdInOrderByAdministrationDateDesc(patientId, readable);
            reachRecorder.recordReach(patientId, actingHospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(immunizations.stream().map(i -> CrossHospitalReachRecorder.hospitalIdOf(i.getHospital())).toList(), actingHospitalId),
                "Cross-hospital immunization read on the treatment relationship");
        }
        
        return immunizations.stream()
                .map(immunizationMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImmunizationResponseDTO> getImmunizationsByVaccineCode(UUID patientId, String vaccineCode) {
        log.debug("Fetching immunizations by vaccine code {} for patient: {}", vaccineCode, patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }

        // E9 #59d — see getImmunizationsByPatientId.
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID actingHospitalId = ctx.pinnedHospitalId();
        List<PatientImmunization> immunizations;
        if (actingHospitalId == null) {
            immunizations = immunizationRepository.findByPatient_IdAndVaccineCodeOrderByAdministrationDateDesc(patientId, vaccineCode);
        } else {
            UUID requesterUserId = ctx.getPrincipalUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, actingHospitalId);
            immunizations = immunizationRepository
                .findByPatient_IdAndHospital_IdInAndVaccineCodeOrderByAdministrationDateDesc(patientId, readable, vaccineCode);
            reachRecorder.recordReach(patientId, actingHospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(immunizations.stream().map(i -> CrossHospitalReachRecorder.hospitalIdOf(i.getHospital())).toList(), actingHospitalId),
                "Cross-hospital immunization read on the treatment relationship");
        }
        
        return immunizations.stream()
                .map(immunizationMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImmunizationResponseDTO> getOverdueImmunizations(UUID patientId) {
        log.debug("Fetching overdue immunizations for patient: {}", patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }

        List<PatientImmunization> overdueImmunizations = immunizationRepository.findOverdueImmunizations(patientId);
        
        return overdueImmunizations.stream()
                .map(immunizationMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImmunizationResponseDTO> getUpcomingImmunizations(UUID patientId, LocalDate startDate, LocalDate endDate) {
        log.debug("Fetching upcoming immunizations for patient: {} between {} and {}", patientId, startDate, endDate);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }

        List<PatientImmunization> upcomingImmunizations = 
                immunizationRepository.findUpcomingDueDates(patientId, startDate, endDate);
        
        return upcomingImmunizations.stream()
                .map(immunizationMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImmunizationResponseDTO> getImmunizationsNeedingReminders(UUID patientId, LocalDate reminderDate) {
        log.debug("Fetching immunizations needing reminders for patient: {}", patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }

        List<PatientImmunization> needingReminders = 
                immunizationRepository.findImmunizationsNeedingReminders(patientId, reminderDate);
        
        return needingReminders.stream()
                .map(immunizationMapper::toResponseDTO)
                .toList();
    }

    @Override
    public void markReminderSent(UUID immunizationId) {
        log.info("Marking reminder sent for immunization: {}", immunizationId);

        PatientImmunization immunization = immunizationRepository.findById(immunizationId)
                .orElseThrow(() -> new ResourceNotFoundException(IMMUNIZATION_NOT_FOUND_KEY, immunizationId));
        // QA r5 tenancy sweep: a write loads under the tenant guard.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(immunization.getHospital(), IMMUNIZATION_NOT_FOUND_KEY, immunizationId);

        immunization.setReminderSent(true);
        immunization.setReminderSentDate(LocalDate.now());
        immunizationRepository.save(immunization);

        log.info("Reminder marked as sent for immunization: {}", immunizationId);
    }

    @Override
    public ImmunizationResponseDTO updateImmunization(UUID id, ImmunizationRequestDTO requestDTO) {
        log.info("Updating immunization with id: {}", id);

        PatientImmunization existingImmunization = immunizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(IMMUNIZATION_NOT_FOUND_KEY, id));
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(existingImmunization.getHospital(), IMMUNIZATION_NOT_FOUND_KEY, id);

        // Update staff if changed
        if (requestDTO.getAdministeredByStaffId() != null && 
            (existingImmunization.getAdministeredBy() == null || 
             !existingImmunization.getAdministeredBy().getId().equals(requestDTO.getAdministeredByStaffId()))) {
            Staff administeredBy = com.example.hms.security.CallerIdentity.resolveActor(staffRepository, requestDTO.getAdministeredByStaffId(),
                    existingImmunization.getHospital() == null ? null : existingImmunization.getHospital().getId());
            existingImmunization.setAdministeredBy(administeredBy);
        }

        // Update encounter if changed
        if (requestDTO.getEncounterId() != null && 
            (existingImmunization.getEncounter() == null || 
             !existingImmunization.getEncounter().getId().equals(requestDTO.getEncounterId()))) {
            Encounter encounter = encounterRepository.findById(requestDTO.getEncounterId())
                    .orElseThrow(() -> new ResourceNotFoundException(ENCOUNTER_NOT_FOUND_KEY, requestDTO.getEncounterId()));
            // The linked encounter is one of the immunization's own hospital.
            com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(encounter.getHospital(), ENCOUNTER_NOT_FOUND_KEY, requestDTO.getEncounterId());
            existingImmunization.setEncounter(encounter);
        }

        immunizationMapper.updateEntity(existingImmunization, requestDTO);
        PatientImmunization updatedImmunization = immunizationRepository.save(existingImmunization);

        log.info("Immunization updated successfully: {}", id);
        return immunizationMapper.toResponseDTO(updatedImmunization);
    }

    @Override
    public void deleteImmunization(UUID id) {
        log.info("Deleting immunization with id: {}", id);

        PatientImmunization immunization = immunizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(IMMUNIZATION_NOT_FOUND_KEY, id));
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(immunization.getHospital(), IMMUNIZATION_NOT_FOUND_KEY, id);

        immunization.setActive(false);
        immunizationRepository.save(immunization);

        log.info("Immunization soft deleted: {}", id);
    }
}
