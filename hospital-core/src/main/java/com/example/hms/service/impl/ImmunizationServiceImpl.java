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

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ImmunizationServiceImpl implements ImmunizationService {
    private static final String IMMUNIZATION_NOT_FOUND_PREFIX = "Immunization not found with id: ";


    private final ImmunizationRepository immunizationRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final ImmunizationMapper immunizationMapper;

    @Override
    public ImmunizationResponseDTO createImmunization(ImmunizationRequestDTO requestDTO) {
        log.info("Creating immunization for patient: {}", requestDTO.getPatientId());

        Patient patient = patientRepository.findById(requestDTO.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + requestDTO.getPatientId()));

        Hospital hospital = hospitalRepository.findById(requestDTO.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with id: " + requestDTO.getHospitalId()));

        Staff administeredBy = null;
        if (requestDTO.getAdministeredByStaffId() != null) {
            administeredBy = staffRepository.findById(requestDTO.getAdministeredByStaffId())
                    .orElseThrow(() -> new ResourceNotFoundException("Staff not found with id: " + requestDTO.getAdministeredByStaffId()));
        }

        Encounter encounter = null;
        if (requestDTO.getEncounterId() != null) {
            encounter = encounterRepository.findById(requestDTO.getEncounterId())
                    .orElseThrow(() -> new ResourceNotFoundException("Encounter not found with id: " + requestDTO.getEncounterId()));
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
                .orElseThrow(() -> new ResourceNotFoundException(IMMUNIZATION_NOT_FOUND_PREFIX + id));

        return immunizationMapper.toResponseDTO(immunization);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImmunizationResponseDTO> getImmunizationsByPatientId(UUID patientId) {
        log.debug("Fetching immunizations for patient: {}", patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
        }

        List<PatientImmunization> immunizations = 
                immunizationRepository.findByPatient_IdOrderByAdministrationDateDesc(patientId);
        
        return immunizations.stream()
                .map(immunizationMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImmunizationResponseDTO> getImmunizationsByVaccineCode(UUID patientId, String vaccineCode) {
        log.debug("Fetching immunizations by vaccine code {} for patient: {}", vaccineCode, patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
        }

        List<PatientImmunization> immunizations = 
                immunizationRepository.findByPatient_IdAndVaccineCodeOrderByAdministrationDateDesc(patientId, vaccineCode);
        
        return immunizations.stream()
                .map(immunizationMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImmunizationResponseDTO> getOverdueImmunizations(UUID patientId) {
        log.debug("Fetching overdue immunizations for patient: {}", patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
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
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
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
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
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
                .orElseThrow(() -> new ResourceNotFoundException(IMMUNIZATION_NOT_FOUND_PREFIX + immunizationId));

        immunization.setReminderSent(true);
        immunization.setReminderSentDate(LocalDate.now());
        immunizationRepository.save(immunization);

        log.info("Reminder marked as sent for immunization: {}", immunizationId);
    }

    @Override
    public ImmunizationResponseDTO updateImmunization(UUID id, ImmunizationRequestDTO requestDTO) {
        log.info("Updating immunization with id: {}", id);

        PatientImmunization existingImmunization = immunizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(IMMUNIZATION_NOT_FOUND_PREFIX + id));

        // Update staff if changed
        if (requestDTO.getAdministeredByStaffId() != null && 
            (existingImmunization.getAdministeredBy() == null || 
             !existingImmunization.getAdministeredBy().getId().equals(requestDTO.getAdministeredByStaffId()))) {
            Staff administeredBy = staffRepository.findById(requestDTO.getAdministeredByStaffId())
                    .orElseThrow(() -> new ResourceNotFoundException("Staff not found with id: " + requestDTO.getAdministeredByStaffId()));
            existingImmunization.setAdministeredBy(administeredBy);
        }

        // Update encounter if changed
        if (requestDTO.getEncounterId() != null && 
            (existingImmunization.getEncounter() == null || 
             !existingImmunization.getEncounter().getId().equals(requestDTO.getEncounterId()))) {
            Encounter encounter = encounterRepository.findById(requestDTO.getEncounterId())
                    .orElseThrow(() -> new ResourceNotFoundException("Encounter not found with id: " + requestDTO.getEncounterId()));
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
                .orElseThrow(() -> new ResourceNotFoundException(IMMUNIZATION_NOT_FOUND_PREFIX + id));

        immunization.setActive(false);
        immunizationRepository.save(immunization);

        log.info("Immunization soft deleted: {}", id);
    }
}
