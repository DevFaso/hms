package com.example.hms.payload.dto;

import com.example.hms.enums.JobTitle;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor @AllArgsConstructor
public class StaffMinimalDTO {
    private UUID id;
    private String fullName;
    private JobTitle jobTitle;
}


