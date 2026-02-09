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

        mapUserFields(dto, staff.getUser());
        mapHospitalFields(dto, staff.getHospital());
        mapDepartmentFields(dto, staff);
        mapRoleFields(dto, staff.getAssignment());

        dto.setJobTitle(staff.getJobTitle());
        dto.setEmploymentType(staff.getEmploymentType());
        mapSpecialization(dto, staff.getSpecialization());
        dto.setLicenseNumber(staff.getLicenseNumber());
        dto.setStartDate(staff.getStartDate());
        dto.setEndDate(staff.getEndDate());
        dto.setActive(staff.isActive());
        dto.setCreatedAt(staff.getCreatedAt());
        dto.setUpdatedAt(staff.getUpdatedAt());

        return dto;
    }

    private void mapUserFields(StaffResponseDTO dto, User user) {
        if (user == null) return;
        dto.setUserId(user.getId() != null ? user.getId().toString() : null);
        dto.setUsername(user.getUsername());
        dto.setName(user.getFirstName() + " " + user.getLastName());
        dto.setEmail(user.getEmail());
        dto.setPhoneNumber(user.getPhoneNumber());
    }

    private void mapHospitalFields(StaffResponseDTO dto, Hospital hospital) {
        if (hospital == null) return;
        dto.setHospitalId(hospital.getId() != null ? hospital.getId().toString() : null);
        dto.setHospitalName(hospital.getName());
        dto.setHospitalEmail(hospital.getEmail());
    }

    private void mapDepartmentFields(StaffResponseDTO dto, Staff staff) {
        Department dept = staff.getDepartment();
        if (dept == null) return;
        dto.setDepartmentId(dept.getId() != null ? dept.getId().toString() : null);
        dto.setDepartmentName(dept.getName());
        dto.setDepartmentEmail(dept.getEmail());
        dto.setDepartmentPhoneNumber(dept.getPhoneNumber());
        boolean isHead = dept.getHeadOfDepartment() != null
            && Objects.equals(dept.getHeadOfDepartment().getId(), staff.getId());
        dto.setHeadOfDepartment(isHead);
    }

    private void mapRoleFields(StaffResponseDTO dto, UserRoleHospitalAssignment assignment) {
        if (assignment == null || assignment.getRole() == null) return;
        dto.setRoleCode(assignment.getRole().getCode());
        dto.setRoleName(assignment.getRole().getName());
    }

    private void mapSpecialization(StaffResponseDTO dto, String specialization) {
        if (specialization == null) return;
        try {
            dto.setSpecialization(Specialization.valueOf(specialization));
        } catch (IllegalArgumentException e) {
            // leave null if enum constant not found
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
