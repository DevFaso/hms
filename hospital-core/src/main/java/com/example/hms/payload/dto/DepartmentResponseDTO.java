package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartmentResponseDTO {

    private String id;
    private String hospitalId;
    private String hospitalName;
    private String phoneNumber;
    private String email;
    private String headOfDepartmentName;
    private String name;
    private String description;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer staffCount;
    private Integer bedCount;
    private String departmentCode;
    private String hospitalAddress;
    private String hospitalMainPhone;
    private String hospitalEmail;
    private String hospitalWebsite;
    private List<String> translations;
}

