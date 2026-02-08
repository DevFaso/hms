package com.example.hms.payload.dto.assignment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentSearchCriteria {

    private String userId;
    private String userEmail;
    private String roleId;
    private String roleCode;
    private String hospitalId;
    private Boolean active;
    private String search;
    private String assignmentCode;
}
