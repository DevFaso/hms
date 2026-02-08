package com.example.hms.payload.dto;

import lombok.*;

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

