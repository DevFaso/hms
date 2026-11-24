package com.example.hms.payload.dto.identifiersearch;

/**
 * The identifiers of {@code POST /patients/search}. Every field is optional, as the query
 * parameters were; {@code dob} keeps the formats the service reads (yyyy-mm-dd, dd/mm/yyyy,
 * dd-mm-yyyy). Paging and the hospital scope stay query parameters: they identify no one.
 */
public record PatientSearchRequestDTO(String mrn, String name, String dob, String phone, String email) {
}
