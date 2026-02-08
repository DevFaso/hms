package com.example.hms.mapper;

import com.example.hms.enums.JobTitle;
import com.example.hms.enums.Specialization;
import com.example.hms.model.*;
import com.example.hms.payload.dto.*;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class StaffMapper {

    public StaffResponseDTO toStaffDTO(Staff staff) {
        if (staff == null) return null;

        StaffResponseDTO dto = new StaffResponseDTO();
        dto.setId(staff.getId() != null ? staff.getId().toString() : null);
        dto.setUserId(staff.getUser() != null && staff.getUser().getId() != null ? staff.getUser().getId().toString() : null);
        dto.setUsername(staff.getUser() != null ? staff.getUser().getUsername() : null);
        dto.setName(staff.getUser() != null ? staff.getUser().getFirstName() + " " + staff.getUser().getLastName() : null);
        dto.setEmail(staff.getUser() != null ? staff.getUser().getEmail() : null);
        dto.setPhoneNumber(staff.getUser() != null ? staff.getUser().getPhoneNumber() : null);
        dto.setHospitalId(staff.getHospital() != null && staff.getHospital().getId() != null ? staff.getHospital().getId().toString() : null);
        dto.setHospitalName(staff.getHospital() != null ? staff.getHospital().getName() : null);
        dto.setHospitalEmail(staff.getHospital() != null ? staff.getHospital().getEmail() : null);
        dto.setDepartmentId(staff.getDepartment() != null && staff.getDepartment().getId() != null ? staff.getDepartment().getId().toString() : null);
        dto.setDepartmentName(staff.getDepartment() != null ? staff.getDepartment().getName() : null);
        dto.setDepartmentEmail(staff.getDepartment() != null ? staff.getDepartment().getEmail() : null);
        dto.setDepartmentPhoneNumber(staff.getDepartment() != null ? staff.getDepartment().getPhoneNumber() : null);
        if (staff.getAssignment() != null && staff.getAssignment().getRole() != null) {
            dto.setRoleCode(staff.getAssignment().getRole().getCode());
            dto.setRoleName(staff.getAssignment().getRole().getName());
        }
        dto.setJobTitle(staff.getJobTitle());
        dto.setEmploymentType(staff.getEmploymentType());
        if (staff.getSpecialization() != null) {
            try {
                dto.setSpecialization(Specialization.valueOf(staff.getSpecialization()));
            } catch (IllegalArgumentException e) {
                // leave null if enum constant not found
            }
        }
        dto.setLicenseNumber(staff.getLicenseNumber());
        dto.setStartDate(staff.getStartDate());
        dto.setEndDate(staff.getEndDate());
        dto.setActive(staff.isActive());
        boolean isHead = staff.getDepartment() != null
            && staff.getDepartment().getHeadOfDepartment() != null
            && Objects.equals(staff.getDepartment().getHeadOfDepartment().getId(), staff.getId());
        dto.setHeadOfDepartment(isHead);
        dto.setCreatedAt(staff.getCreatedAt());
        dto.setUpdatedAt(staff.getUpdatedAt());

        return dto;
    }

    public Staff toStaff(StaffRequestDTO dto, User user, Hospital hospital, Department department, UserRoleHospitalAssignment assignment) {
        if (dto == null) return null;

    return Staff.builder()
        .user(user)
        .hospital(hospital)
        .department(department)
        .assignment(assignment)
        .jobTitle(dto.getJobTitle() != null ? JobTitle.valueOf(dto.getJobTitle()) : null)
        .employmentType(dto.getEmploymentType())
        .specialization(dto.getSpecialization() != null ? dto.getSpecialization().name() : null)
        .licenseNumber(dto.getLicenseNumber())
        .startDate(dto.getStartDate())
        .endDate(dto.getEndDate())
        .active(dto.isActive())
        .build();
    }

    public void updateStaffFromDto(StaffRequestDTO dto, Staff staff, User user, Hospital hospital, Department department, UserRoleHospitalAssignment assignment) {
        if (dto == null || staff == null) return;

        staff.setUser(user);
        staff.setHospital(hospital);
        staff.setDepartment(department);
        staff.setAssignment(assignment);

    staff.setJobTitle(dto.getJobTitle() != null ? JobTitle.valueOf(dto.getJobTitle()) : null);
        staff.setEmploymentType(dto.getEmploymentType());
    staff.setSpecialization(dto.getSpecialization() != null ? dto.getSpecialization().name() : null);
        staff.setLicenseNumber(dto.getLicenseNumber());
        staff.setStartDate(dto.getStartDate());
        staff.setEndDate(dto.getEndDate());
        staff.setActive(dto.isActive());

    }

    public StaffMinimalDTO toMinimalDTO(Staff staff) {
        if (staff == null || staff.getUser() == null) return null;

        return new StaffMinimalDTO(
                staff.getId(),
                staff.getUser().getFirstName() + " " + staff.getUser().getLastName(),
                staff.getJobTitle()
        );
    }
}
