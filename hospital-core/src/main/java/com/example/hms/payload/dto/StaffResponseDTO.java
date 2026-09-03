package com.example.hms.payload.dto;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.Specialization;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StaffResponseDTO {

    private String id;
    private String userId;
    private String username;
    private String name;
    private String email;
    private String phoneNumber;
    private String hospitalId;
    private String hospitalName;
    private String hospitalEmail;
    private String departmentId;
    private String departmentName;
    private String departmentEmail;
    private String departmentPhoneNumber;
    private String roleCode;
    private String roleName;
    private JobTitle jobTitle;
    private EmploymentType employmentType;
    private Specialization specialization;
    private String licenseNumber;
    /**
     * The licence expiry, or null for a qualification that does not expire.
     *
     * <p>Null is the normal case here: clinicians are credentialed on a
     * diploma. It is exposed so the staff screen can offer credentialing to
     * exactly the people the expiry-alert list can never contain — that list
     * requires a non-null expiry, so before V145 the credentialing form had no
     * way to be opened for a diploma holder.
     */
    private LocalDate licenseExpiryDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean active;
    private Boolean headOfDepartment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
