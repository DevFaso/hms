package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartmentSummaryDTO {

    private UUID id;
    private UUID hospitalId;
    private String hospitalName;
    private String name;
    private String code;
    private String email;
    private String phoneNumber;
    private boolean active;
    private Integer staffCount;
    private Integer bedCount;
    private String headOfDepartmentName;
    private String headOfDepartmentEmail;
    private String description;
}
