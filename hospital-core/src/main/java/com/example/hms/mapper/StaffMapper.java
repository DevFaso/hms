package com.example.hms.mapper;

import com.example.hms.enums.JobTitle;
import com.example.hms.enums.Specialization;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.StaffMinimalDTO;
import com.example.hms.payload.dto.StaffRequestDTO;
import com.example.hms.payload.dto.StaffResponseDTO;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class StaffMapper {

    public StaffResponseDTO toStaffDTO(Staff staff) {
        if (staff == null) return null;

        StaffResponseDTO dto = new StaffResponseDTO();
        dto.setId(staff.getId() != null ? staff.getId().toString() : null);

        populateUserInfo(dto, staff.getUser());
        populateHospitalInfo(dto, staff.getHospital());
        populateDepartmentInfo(dto, staff.getDepartment(), staff.getId());

        if (staff.getAssignment() != null && staff.getAssignment().getRole() != null) {
            dto.setRoleCode(staff.getAssignment().getRole().getCode());
            dto.setRoleName(staff.getAssignment().getRole().getName());
        }
        dto.setJobTitle(staff.getJobTitle());
        dto.setEmploymentType(staff.getEmploymentType());
        dto.setSpecialization(parseSpecialization(staff.getSpecialization()));
        dto.setLicenseNumber(staff.getLicenseNumber());
        dto.setStartDate(staff.getStartDate());
        dto.setEndDate(staff.getEndDate());
        dto.setActive(staff.isActive());
        dto.setCreatedAt(staff.getCreatedAt());
        dto.setUpdatedAt(staff.getUpdatedAt());

        return dto;
    }

    private void populateUserInfo(StaffResponseDTO dto, User user) {
        if (user == null) return;
        dto.setUserId(user.getId() != null ? user.getId().toString() : null);
        dto.setUsername(user.getUsername());
        dto.setName(user.getFirstName() + " " + user.getLastName());
        dto.setEmail(user.getEmail());
        dto.setPhoneNumber(user.getPhoneNumber());
    }

    private void populateHospitalInfo(StaffResponseDTO dto, Hospital hospital) {
        if (hospital == null) return;
        dto.setHospitalId(hospital.getId() != null ? hospital.getId().toString() : null);
        dto.setHospitalName(hospital.getName());
        dto.setHospitalEmail(hospital.getEmail());
    }

    private void populateDepartmentInfo(StaffResponseDTO dto, Department department, java.util.UUID staffId) {
        if (department == null) return;
        dto.setDepartmentId(department.getId() != null ? department.getId().toString() : null);
        dto.setDepartmentName(department.getName());
        dto.setDepartmentEmail(department.getEmail());
        dto.setDepartmentPhoneNumber(department.getPhoneNumber());
        boolean isHead = department.getHeadOfDepartment() != null
            && Objects.equals(department.getHeadOfDepartment().getId(), staffId);
        dto.setHeadOfDepartment(isHead);
    }

    private Specialization parseSpecialization(String value) {
        if (value == null) return null;
        try {
            return Specialization.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
