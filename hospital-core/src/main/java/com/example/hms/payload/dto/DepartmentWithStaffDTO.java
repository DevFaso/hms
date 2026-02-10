package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentWithStaffDTO {
    private UUID departmentId;
    private String departmentName;
    private String description;
    private String email;
    private String phoneNumber;
    private StaffMinimalDTO headOfDepartment;
    private List<StaffMinimalDTO> staffMembers;
}

