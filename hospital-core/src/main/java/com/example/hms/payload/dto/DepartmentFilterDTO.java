package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartmentFilterDTO {

    private UUID hospitalId;
    private UUID organizationId;
    private Boolean unassignedOnly;
    private String name;
    private String email;
    private String phoneNumber;
    private UUID headOfDepartmentId;
    private Boolean active;
    private String city;
    private String state;
}

