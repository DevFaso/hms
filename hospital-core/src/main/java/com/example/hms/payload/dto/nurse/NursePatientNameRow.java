package com.example.hms.payload.dto.nurse;

import java.time.LocalDate;
import java.util.UUID;

/** QA D1314: one active registration, as much of it as a nurse's lists read (see the repository query). */
public record NursePatientNameRow(UUID patientId, String firstName, String middleName, String lastName,
                                  String registrationName, LocalDate registrationDate, UUID readyByStaffId) {
}
