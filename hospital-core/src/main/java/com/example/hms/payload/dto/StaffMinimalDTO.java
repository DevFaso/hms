package com.example.hms.payload.dto;

import com.example.hms.enums.JobTitle;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor @AllArgsConstructor
public class StaffMinimalDTO {
    private UUID id;
    private String fullName;
    private JobTitle jobTitle;
}


