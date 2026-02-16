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
public class PatientLookupDTO {
    private UUID id;
    private String firstName;
    private String lastName;
    private String email;
    private String phoneNumberPrimary;
    private String medicalRecordIdentifier;
    // Add other non-sensitive fields as needed
}
