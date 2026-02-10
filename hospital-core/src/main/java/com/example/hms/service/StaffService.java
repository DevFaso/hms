package com.example.hms.service;

import com.example.hms.model.Staff;
import com.example.hms.payload.dto.StaffMinimalDTO;
import com.example.hms.payload.dto.StaffRequestDTO;
import com.example.hms.payload.dto.StaffResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface StaffService {

    // Existing
    List<StaffResponseDTO> getAllStaff(Locale locale);
    StaffResponseDTO getStaffById(UUID id, Locale locale);
    StaffResponseDTO createStaff(StaffRequestDTO staffRequestDTO, Locale locale);
    StaffResponseDTO updateStaff(UUID id, StaffRequestDTO staffRequestDTO, Locale locale);
    void updateStaffDepartment(String staffEmail, String departmentName, String hospitalName, Locale locale);
    Staff getStaffEntityById(UUID id, Locale locale);
    void deleteStaff(UUID id, Locale locale);
    StaffMinimalDTO toMinimalDTO(Staff staff);

    // Newly exposed contracts (were in Impl only)
    List<StaffResponseDTO> getStaffByUserEmail(String email, Locale locale);
    List<StaffResponseDTO> getStaffByUserPhoneNumber(String phone, Locale locale);

    Optional<String> getAnyLicenseByUserId(UUID userId);
    Optional<StaffResponseDTO> getStaffByIdAndActiveTrue(UUID id, Locale locale);

    List<StaffResponseDTO> getActiveStaffByUserId(UUID userId, Locale locale);

    boolean existsByIdAndHospitalIdAndActiveTrue(UUID id, UUID hospitalId);
    boolean existsByLicenseNumberAndUserId(String licenseNumber, UUID userId);

    Page<StaffResponseDTO> getStaffByHospitalId(UUID hospitalId, Pageable pageable);
    Page<StaffResponseDTO> getStaffByHospitalIdAndActiveTrue(UUID hospitalId, Pageable pageable);

    Optional<StaffResponseDTO> getFirstStaffByUserIdOrderByCreatedAtAsc(UUID userId, Locale locale);
}
