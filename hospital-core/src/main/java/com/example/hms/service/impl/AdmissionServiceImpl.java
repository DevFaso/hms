package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AdmissionType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdmissionMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.*;
import com.example.hms.service.AdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of AdmissionService
 */
@Service
@RequiredArgsConstructor
public class AdmissionServiceImpl implements AdmissionService {

    private final AdmissionRepository admissionRepository;
    private final AdmissionOrderSetRepository orderSetRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final AdmissionMapper admissionMapper;

    @Override
    @Transactional
    public AdmissionResponseDTO admitPatient(AdmissionRequestDTO request) {
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
        
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
        
        Staff admittingProvider = staffRepository.findById(request.getAdmittingProviderId())
            .orElseThrow(() -> new ResourceNotFoundException("Admitting provider not found"));

        Admission admission = new Admission();
        admission.setPatient(patient);
        admission.setHospital(hospital);
        admission.setAdmittingProvider(admittingProvider);
        
        if (request.getDepartmentId() != null) {
            Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found"));
            admission.setDepartment(department);
        }
        
        if (request.getAttendingPhysicianId() != null) {
            Staff attending = staffRepository.findById(request.getAttendingPhysicianId())
                .orElseThrow(() -> new ResourceNotFoundException("Attending physician not found"));
            admission.setAttendingPhysician(attending);
        }

        admission.setRoomBed(request.getRoomBed());
        admission.setAdmissionType(request.getAdmissionType());
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setAcuityLevel(request.getAcuityLevel());
        admission.setAdmissionDateTime(request.getAdmissionDateTime());
        admission.setExpectedDischargeDateTime(request.getExpectedDischargeDateTime());
        admission.setChiefComplaint(request.getChiefComplaint());
        admission.setPrimaryDiagnosisCode(request.getPrimaryDiagnosisCode());
        admission.setPrimaryDiagnosisDescription(request.getPrimaryDiagnosisDescription());
        admission.setSecondaryDiagnoses(request.getSecondaryDiagnoses());
        admission.setAdmissionSource(request.getAdmissionSource());
        admission.setCustomOrders(request.getCustomOrders());
        admission.setAdmissionNotes(request.getAdmissionNotes());
        admission.setInsuranceAuthNumber(request.getInsuranceAuthNumber());
        admission.setMetadata(request.getMetadata());

        // Apply order sets if specified
        if (request.getOrderSetIds() != null && !request.getOrderSetIds().isEmpty()) {
            List<AdmissionOrderSet> orderSets = orderSetRepository.findAllById(request.getOrderSetIds());
            admission.setAppliedOrderSets(orderSets);
        }

        admission = admissionRepository.save(admission);
        return admissionMapper.toResponseDTO(admission);
    }

    @Override
    public AdmissionResponseDTO getAdmission(UUID admissionId) {
        Admission admission = admissionRepository.findById(admissionId)
            .orElseThrow(() -> new ResourceNotFoundException("Admission not found"));
        return admissionMapper.toResponseDTO(admission);
    }

    @Override
    @Transactional
    public AdmissionResponseDTO updateAdmission(UUID admissionId, AdmissionUpdateRequestDTO request) {
        Admission admission = admissionRepository.findById(admissionId)
            .orElseThrow(() -> new ResourceNotFoundException("Admission not found"));

        if (request.getDepartmentId() != null) {
            Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found"));
            admission.setDepartment(department);
        }

        if (request.getAttendingPhysicianId() != null) {
            Staff attending = staffRepository.findById(request.getAttendingPhysicianId())
                .orElseThrow(() -> new ResourceNotFoundException("Attending physician not found"));
            admission.setAttendingPhysician(attending);
        }

        if (request.getRoomBed() != null) admission.setRoomBed(request.getRoomBed());
        if (request.getAcuityLevel() != null) admission.setAcuityLevel(request.getAcuityLevel());
        if (request.getExpectedDischargeDateTime() != null) admission.setExpectedDischargeDateTime(request.getExpectedDischargeDateTime());
        if (request.getAdmissionNotes() != null) admission.setAdmissionNotes(request.getAdmissionNotes());
        if (request.getMetadata() != null) admission.setMetadata(request.getMetadata());

        if (request.getSecondaryDiagnosesToAdd() != null) {
            Admission updatingAdmission = admission;
            request.getSecondaryDiagnosesToAdd().forEach(diagnosis ->
                updatingAdmission.addSecondaryDiagnosis(diagnosis.get("code"), diagnosis.get("description"))
            );
        }

        admission = admissionRepository.save(admission);
        return admissionMapper.toResponseDTO(admission);
    }

    @Override
    @Transactional
    public AdmissionResponseDTO applyOrderSets(UUID admissionId, AdmissionOrderExecutionRequestDTO request) {
        Admission admission = admissionRepository.findById(admissionId)
            .orElseThrow(() -> new ResourceNotFoundException("Admission not found"));

        List<AdmissionOrderSet> orderSets = orderSetRepository.findAllById(request.getOrderSetIds());
        orderSets.forEach(admission::applyOrderSet);

        admission = admissionRepository.save(admission);
        return admissionMapper.toResponseDTO(admission);
    }

    @Override
    @Transactional
    public AdmissionResponseDTO dischargePatient(UUID admissionId, AdmissionDischargeRequestDTO request) {
        Admission admission = admissionRepository.findById(admissionId)
            .orElseThrow(() -> new ResourceNotFoundException("Admission not found"));

        Staff dischargingProvider = staffRepository.findById(request.getDischargingProviderId())
            .orElseThrow(() -> new ResourceNotFoundException("Discharging provider not found"));

        admission.discharge(
            request.getDischargeDisposition(),
            request.getDischargeSummary(),
            request.getDischargeInstructions(),
            dischargingProvider
        );

        if (request.getFollowUpAppointments() != null) {
            admission.setFollowUpAppointments(request.getFollowUpAppointments());
        }

        admission = admissionRepository.save(admission);
        return admissionMapper.toResponseDTO(admission);
    }

    @Override
    @Transactional
    public void cancelAdmission(UUID admissionId) {
        Admission admission = admissionRepository.findById(admissionId)
            .orElseThrow(() -> new ResourceNotFoundException("Admission not found"));
        admission.cancel();
        admissionRepository.save(admission);
    }

    @Override
    public List<AdmissionResponseDTO> getAdmissionsByPatient(UUID patientId) {
        return admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId)
            .stream()
            .map(admissionMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    public List<AdmissionResponseDTO> getAdmissionsByHospital(UUID hospitalId, String status, LocalDateTime startDate, LocalDateTime endDate) {
        if (status != null && !status.isEmpty()) {
            AdmissionStatus admissionStatus = AdmissionStatus.valueOf(status.toUpperCase());
            return admissionRepository.findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(hospitalId, admissionStatus)
                .stream()
                .map(admissionMapper::toResponseDTO)
                .collect(Collectors.toList());
        }
        return admissionRepository.findByHospitalIdOrderByAdmissionDateTimeDesc(hospitalId)
            .stream()
            .map(admissionMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    public AdmissionResponseDTO getCurrentAdmissionForPatient(UUID patientId) {
        return admissionRepository.findCurrentAdmissionByPatient(patientId)
            .map(admissionMapper::toResponseDTO)
            .orElse(null);
    }

    @Override
    @Transactional
    public AdmissionOrderSetResponseDTO createOrderSet(AdmissionOrderSetRequestDTO request) {
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        Staff createdBy = staffRepository.findById(request.getCreatedByStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("Staff not found"));

        AdmissionOrderSet orderSet = new AdmissionOrderSet();
        orderSet.setName(request.getName());
        orderSet.setDescription(request.getDescription());
        orderSet.setAdmissionType(request.getAdmissionType());
        orderSet.setHospital(hospital);
        orderSet.setOrderItems(request.getOrderItems());
        orderSet.setClinicalGuidelines(request.getClinicalGuidelines());
        orderSet.setActive(request.getActive());
        orderSet.setCreatedBy(createdBy);

        if (request.getDepartmentId() != null) {
            Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found"));
            orderSet.setDepartment(department);
        }

        orderSet = orderSetRepository.save(orderSet);
        return admissionMapper.toOrderSetResponseDTO(orderSet);
    }

    @Override
    public AdmissionOrderSetResponseDTO getOrderSet(UUID orderSetId) {
        AdmissionOrderSet orderSet = orderSetRepository.findById(orderSetId)
            .orElseThrow(() -> new ResourceNotFoundException("Order set not found"));
        return admissionMapper.toOrderSetResponseDTO(orderSet);
    }

    @Override
    public List<AdmissionOrderSetResponseDTO> getOrderSetsByHospital(UUID hospitalId, String admissionType) {
        List<AdmissionOrderSet> orderSets;
        if (admissionType != null && !admissionType.isEmpty()) {
            AdmissionType type = AdmissionType.valueOf(admissionType.toUpperCase());
            orderSets = orderSetRepository.findByHospitalIdAndAdmissionTypeAndActiveOrderByNameAsc(hospitalId, type, true);
        } else {
            orderSets = orderSetRepository.findByHospitalIdAndActiveOrderByNameAsc(hospitalId, true);
        }
        return orderSets.stream()
            .map(admissionMapper::toOrderSetResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deactivateOrderSet(UUID orderSetId, String reason, UUID deactivatedByStaffId) {
        AdmissionOrderSet orderSet = orderSetRepository.findById(orderSetId)
            .orElseThrow(() -> new ResourceNotFoundException("Order set not found"));

        Staff deactivatedBy = staffRepository.findById(deactivatedByStaffId)
            .orElseThrow(() -> new ResourceNotFoundException("Staff not found"));

        orderSet.deactivate(reason, deactivatedBy);
        orderSetRepository.save(orderSet);
    }
}
