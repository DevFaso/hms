package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientGuarantor;
import com.example.hms.payload.dto.GuarantorRequestDTO;
import com.example.hms.payload.dto.GuarantorResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientGuarantorRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.PatientGuarantorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Guarantor accounts (P3 #21). Deactivate-never-delete; at most one PRIMARY
 * guarantor per patient+hospital (setting a new primary demotes the old).
 * Linking guarantors onto invoices is a billing-workflow decision this
 * service deliberately does not make.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PatientGuarantorServiceImpl implements PatientGuarantorService {

    private static final String MSG_PATIENT_NOT_FOUND = "Patient not found with ID: ";
    private static final String MSG_GUARANTOR_NOT_FOUND = "Guarantor not found with ID: ";

    private final PatientGuarantorRepository guarantorRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;

    @Override
    public GuarantorResponseDTO add(UUID patientId, UUID hospitalId, GuarantorRequestDTO request) {
        if (hospitalId == null) {
            throw new BusinessException("An active hospital is required to add a guarantor.");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId));
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + hospitalId));
        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("Patient is not registered at this hospital.");
        }

        boolean primary = Boolean.TRUE.equals(request.getPrimary());
        if (primary) {
            demoteExistingPrimary(patientId, hospitalId);
        }
        PatientGuarantor guarantor = PatientGuarantor.builder()
            .patient(patient)
            .hospital(hospital)
            .fullName(request.getFullName().trim())
            .relationship(request.getRelationship())
            .phone(request.getPhone())
            .email(request.getEmail())
            .address(request.getAddress())
            .primary(primary)
            .notes(request.getNotes())
            .build();
        return toDto(guarantorRepository.save(guarantor));
    }

    @Override
    public GuarantorResponseDTO update(UUID patientId, UUID guarantorId, UUID hospitalId,
                                       GuarantorRequestDTO request) {
        PatientGuarantor guarantor = loadScoped(patientId, guarantorId, hospitalId);
        boolean wantsPrimary = Boolean.TRUE.equals(request.getPrimary());
        if (wantsPrimary && !guarantor.isPrimary()) {
            demoteExistingPrimary(patientId, guarantor.getHospital().getId());
        }
        guarantor.setFullName(request.getFullName().trim());
        guarantor.setRelationship(request.getRelationship());
        guarantor.setPhone(request.getPhone());
        guarantor.setEmail(request.getEmail());
        guarantor.setAddress(request.getAddress());
        if (request.getPrimary() != null) {
            guarantor.setPrimary(wantsPrimary);
        }
        guarantor.setNotes(request.getNotes());
        return toDto(guarantorRepository.save(guarantor));
    }

    @Override
    @Transactional(readOnly = true)
    public List<GuarantorResponseDTO> list(UUID patientId, UUID hospitalId) {
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId));
        // 404-not-403: same message whether unknown or merely unregistered here.
        if (hospitalId != null && !patient.isRegisteredInHospital(hospitalId)) {
            throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId);
        }
        return guarantorRepository.findForPatient(patientId, hospitalId).stream()
            .map(this::toDto)
            .toList();
    }

    @Override
    public GuarantorResponseDTO deactivate(UUID patientId, UUID guarantorId, UUID hospitalId) {
        PatientGuarantor guarantor = loadScoped(patientId, guarantorId, hospitalId);
        if (!guarantor.isActive()) {
            throw new BusinessException("This guarantor is already inactive.");
        }
        guarantor.setActive(false);
        guarantor.setPrimary(false);
        return toDto(guarantorRepository.save(guarantor));
    }

    @Override
    public GuarantorResponseDTO reactivate(UUID patientId, UUID guarantorId, UUID hospitalId) {
        PatientGuarantor guarantor = loadScoped(patientId, guarantorId, hospitalId);
        if (guarantor.isActive()) {
            throw new BusinessException("This guarantor is already active.");
        }
        guarantor.setActive(true);
        return toDto(guarantorRepository.save(guarantor));
    }

    private PatientGuarantor loadScoped(UUID patientId, UUID guarantorId, UUID hospitalId) {
        PatientGuarantor guarantor = guarantorRepository.findById(guarantorId)
            .filter(g -> g.getPatient() != null && Objects.equals(g.getPatient().getId(), patientId))
            .orElseThrow(() -> new ResourceNotFoundException(MSG_GUARANTOR_NOT_FOUND + guarantorId));
        if (hospitalId != null
            && (guarantor.getHospital() == null
                || !Objects.equals(guarantor.getHospital().getId(), hospitalId))) {
            throw new ResourceNotFoundException(MSG_GUARANTOR_NOT_FOUND + guarantorId);
        }
        return guarantor;
    }

    private void demoteExistingPrimary(UUID patientId, UUID hospitalId) {
        guarantorRepository.findByPatient_IdAndHospital_IdAndActiveTrue(patientId, hospitalId).stream()
            .filter(PatientGuarantor::isPrimary)
            .forEach(existing -> {
                existing.setPrimary(false);
                guarantorRepository.save(existing);
            });
    }

    private GuarantorResponseDTO toDto(PatientGuarantor guarantor) {
        return GuarantorResponseDTO.builder()
            .id(guarantor.getId())
            .patientId(guarantor.getPatient() != null ? guarantor.getPatient().getId() : null)
            .hospitalId(guarantor.getHospital() != null ? guarantor.getHospital().getId() : null)
            .fullName(guarantor.getFullName())
            .relationship(guarantor.getRelationship())
            .phone(guarantor.getPhone())
            .email(guarantor.getEmail())
            .address(guarantor.getAddress())
            .primary(guarantor.isPrimary())
            .active(guarantor.isActive())
            .notes(guarantor.getNotes())
            .createdAt(guarantor.getCreatedAt())
            .updatedAt(guarantor.getUpdatedAt())
            .build();
    }
}
