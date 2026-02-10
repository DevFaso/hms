package com.example.hms.patient.service;

import com.example.hms.patient.dto.AddressDto;
import com.example.hms.patient.dto.MedicalHistoryDto;
import com.example.hms.patient.dto.PatientInsuranceDto;
import com.example.hms.patient.dto.PatientRequest;
import com.example.hms.patient.dto.PatientResponse;
import com.example.hms.patient.model.Patient;
import com.example.hms.patient.model.PatientInsurance;
import com.example.hms.patient.model.PatientMedicalHistory;
import com.example.hms.patient.repository.PatientRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PatientService {
    private final PatientRepository patientRepository;

    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    @Transactional
    public PatientResponse create(PatientRequest request) {
        if (patientRepository.existsByMrnIgnoreCase(request.getMrn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MRN already exists");
        }

        Patient patient = new Patient();
        applyRequest(patient, request);
        return toResponse(patientRepository.save(patient));
    }

    @Transactional(readOnly = true)
    public PatientResponse getById(UUID id) {
        Patient patient = patientRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found"));
        return toResponse(patient);
    }

    @Transactional
    public PatientResponse update(UUID id, PatientRequest request) {
        Patient patient = patientRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found"));

        if (!patient.getMrn().equalsIgnoreCase(request.getMrn())
            && patientRepository.existsByMrnIgnoreCase(request.getMrn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MRN already exists");
        }

        applyRequest(patient, request);
        return toResponse(patientRepository.save(patient));
    }

    @Transactional(readOnly = true)
    public List<PatientResponse> search(String query) {
        List<Patient> patients;
        if (query == null || query.isBlank()) {
            patients = patientRepository.findAll();
        } else {
            patients = patientRepository.search(query.trim());
        }
        return patients.stream().map(this::toResponse).collect(Collectors.toList());
    }

    private void applyRequest(Patient patient, PatientRequest request) {
        patient.setMrn(request.getMrn());
        patient.setFirstName(request.getFirstName());
        patient.setLastName(request.getLastName());
        patient.setMiddleName(request.getMiddleName());
        patient.setDateOfBirth(request.getDateOfBirth());
        patient.setGender(request.getGender());
        patient.setPhone(request.getPhone());
        patient.setEmail(request.getEmail());

        applyAddress(patient, request.getAddress());
        applyMedicalHistory(patient, request.getMedicalHistory());
        patient.setInsurances(mapInsurances(request.getInsurances()));
    }

    private void applyAddress(Patient patient, AddressDto address) {
        if (address == null) {
            patient.setAddressLine1(null);
            patient.setAddressLine2(null);
            patient.setCity(null);
            patient.setState(null);
            patient.setPostalCode(null);
            patient.setCountry(null);
            return;
        }
        patient.setAddressLine1(address.getLine1());
        patient.setAddressLine2(address.getLine2());
        patient.setCity(address.getCity());
        patient.setState(address.getState());
        patient.setPostalCode(address.getPostalCode());
        patient.setCountry(address.getCountry());
    }

    private void applyMedicalHistory(Patient patient, MedicalHistoryDto medicalHistoryDto) {
        if (medicalHistoryDto == null) {
            patient.setMedicalHistory(null);
            return;
        }
        PatientMedicalHistory history = patient.getMedicalHistory();
        if (history == null) {
            history = new PatientMedicalHistory();
        }
        history.setAllergies(medicalHistoryDto.getAllergies());
        history.setConditions(medicalHistoryDto.getConditions());
        history.setMedications(medicalHistoryDto.getMedications());
        history.setSurgeries(medicalHistoryDto.getSurgeries());
        history.setNotes(medicalHistoryDto.getNotes());
        patient.setMedicalHistory(history);
    }

    private List<PatientInsurance> mapInsurances(List<PatientInsuranceDto> insuranceDtos) {
        if (insuranceDtos == null) {
            return new ArrayList<>();
        }
        return insuranceDtos.stream()
            .filter(Objects::nonNull)
            .map(this::toInsurance)
            .collect(Collectors.toList());
    }

    private PatientInsurance toInsurance(PatientInsuranceDto dto) {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setProviderName(dto.getProviderName());
        insurance.setPolicyNumber(dto.getPolicyNumber());
        insurance.setMemberId(dto.getMemberId());
        insurance.setGroupNumber(dto.getGroupNumber());
        insurance.setCoverageStart(dto.getCoverageStart());
        insurance.setCoverageEnd(dto.getCoverageEnd());
        insurance.setPrimaryPlan(dto.isPrimaryPlan());
        return insurance;
    }

    private PatientResponse toResponse(Patient patient) {
        PatientResponse response = new PatientResponse();
        response.setId(patient.getId());
        response.setMrn(patient.getMrn());
        response.setFirstName(patient.getFirstName());
        response.setLastName(patient.getLastName());
        response.setMiddleName(patient.getMiddleName());
        response.setDateOfBirth(patient.getDateOfBirth());
        response.setGender(patient.getGender());
        response.setPhone(patient.getPhone());
        response.setEmail(patient.getEmail());
        response.setAddress(toAddress(patient));
        response.setMedicalHistory(toMedicalHistory(patient.getMedicalHistory()));
        response.setInsurances(patient.getInsurances().stream()
            .map(this::toInsuranceDto)
            .collect(Collectors.toList()));
        response.setCreatedAt(patient.getCreatedAt());
        response.setUpdatedAt(patient.getUpdatedAt());
        return response;
    }

    private AddressDto toAddress(Patient patient) {
        if (patient.getAddressLine1() == null
            && patient.getAddressLine2() == null
            && patient.getCity() == null
            && patient.getState() == null
            && patient.getPostalCode() == null
            && patient.getCountry() == null) {
            return null;
        }

        AddressDto address = new AddressDto();
        address.setLine1(patient.getAddressLine1());
        address.setLine2(patient.getAddressLine2());
        address.setCity(patient.getCity());
        address.setState(patient.getState());
        address.setPostalCode(patient.getPostalCode());
        address.setCountry(patient.getCountry());
        return address;
    }

    private MedicalHistoryDto toMedicalHistory(PatientMedicalHistory history) {
        if (history == null) {
            return null;
        }
        MedicalHistoryDto dto = new MedicalHistoryDto();
        dto.setAllergies(history.getAllergies());
        dto.setConditions(history.getConditions());
        dto.setMedications(history.getMedications());
        dto.setSurgeries(history.getSurgeries());
        dto.setNotes(history.getNotes());
        return dto;
    }

    private PatientInsuranceDto toInsuranceDto(PatientInsurance insurance) {
        PatientInsuranceDto dto = new PatientInsuranceDto();
        dto.setId(insurance.getId());
        dto.setProviderName(insurance.getProviderName());
        dto.setPolicyNumber(insurance.getPolicyNumber());
        dto.setMemberId(insurance.getMemberId());
        dto.setGroupNumber(insurance.getGroupNumber());
        dto.setCoverageStart(insurance.getCoverageStart());
        dto.setCoverageEnd(insurance.getCoverageEnd());
        dto.setPrimaryPlan(insurance.isPrimaryPlan());
        return dto;
    }
}
