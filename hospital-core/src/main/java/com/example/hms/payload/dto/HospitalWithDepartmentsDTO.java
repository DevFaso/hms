package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HospitalWithDepartmentsDTO {

    private UUID hospitalId;
    private String hospitalName;
    private String hospitalCode;
    private String city;
    private String state;
    private String country;
    private boolean active;
    private String phoneNumber;
    private String email;
    private String website;
    private List<DepartmentSummaryDTO> departments;
}
