package com.example.hms.payload.dto;

import lombok.*;
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
