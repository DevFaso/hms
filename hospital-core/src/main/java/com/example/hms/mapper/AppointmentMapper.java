package com.example.hms.mapper;


import com.example.hms.model.*;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class AppointmentMapper {

    public AppointmentResponseDTO toAppointmentResponseDTO(Appointment appointment) {
    if (appointment == null) return null;

    AppointmentResponseDTO dto = new AppointmentResponseDTO();
        dto.setId(appointment.getId());
        dto.setAppointmentDate(appointment.getAppointmentDate());
        dto.setStartTime(appointment.getStartTime());
        dto.setEndTime(appointment.getEndTime());
        dto.setStatus(appointment.getStatus());
        dto.setReason(appointment.getReason());
        dto.setNotes(appointment.getNotes());
        dto.setCreatedAt(appointment.getCreatedAt());
        dto.setUpdatedAt(appointment.getUpdatedAt());

        if (appointment.getDepartment() != null) {
            dto.setDepartmentId(appointment.getDepartment().getId());
            // Optionally add department name if DTO supports it
        }

        Patient patient = appointment.getPatient();
        if (patient != null) {
            dto.setPatientId(patient.getId());
            dto.setPatientName(getFullName(patient.getFirstName(), patient.getLastName()));

            if (patient.getUser() != null) {
                dto.setPatientEmail(patient.getUser().getEmail());
                dto.setPatientPhone(patient.getUser().getPhoneNumber());
            }
        }

        Staff staff = appointment.getStaff();
        if (staff != null) {
            dto.setStaffId(staff.getId());
            if (staff.getUser() != null) {
                dto.setStaffName(getFullName(staff.getUser().getFirstName(), staff.getUser().getLastName()));
                dto.setStaffEmail(staff.getUser().getEmail());
            }
        }

        Hospital hospital = appointment.getHospital();
        if (hospital != null) {
            dto.setHospitalId(hospital.getId());
            dto.setHospitalName(hospital.getName());

            String fullAddress = Stream.of(
                    hospital.getAddress(),
                    hospital.getCity(),
                    hospital.getState(),
                    hospital.getZipCode(),
                    hospital.getCountry(),
                    hospital.getProvince(),
                    hospital.getRegion(),
                    hospital.getSector(),
                    hospital.getPoBox()
            )
            .filter(Objects::nonNull)
            .filter(s -> !s.isBlank())
            .collect(Collectors.joining(", "));

            dto.setHospitalAddress(fullAddress.isEmpty() ? null : fullAddress);
        }

        User createdBy = appointment.getCreatedBy();
        if (createdBy != null) {
            dto.setCreatedById(createdBy.getId());
            dto.setCreatedByName(getFullName(createdBy.getFirstName(), createdBy.getLastName()));
        }

        return dto;
    }

    public Appointment toAppointment(
            AppointmentRequestDTO dto,
            Patient patient,
            Staff staff,
            Hospital hospital,
            // Treatment removed
            UserRoleHospitalAssignment resolvedAssignment,
            User createdBy
    ) {
    return Appointment.builder()
        .appointmentDate(dto.getAppointmentDate())
        .startTime(dto.getStartTime())
        .endTime(dto.getEndTime())
        .status(dto.getStatus())
        .reason(dto.getReason())
        .notes(dto.getNotes())
        .patient(patient)
        .staff(staff)
        .hospital(hospital)
        .assignment(resolvedAssignment)
        .createdBy(createdBy)
        .build();
    }

    public void updateAppointmentFromDto(
        AppointmentRequestDTO dto,
        Appointment appointment,
        Patient patient,
        Staff staff,
        Hospital hospital
    ) {
        if (dto == null || appointment == null) return;

        appointment.setAppointmentDate(dto.getAppointmentDate());
        appointment.setStartTime(dto.getStartTime());
        appointment.setEndTime(dto.getEndTime());
        appointment.setStatus(dto.getStatus());
        appointment.setReason(dto.getReason());
        appointment.setNotes(dto.getNotes());

        if (patient != null) appointment.setPatient(patient);
        if (staff != null) appointment.setStaff(staff);
        if (hospital != null) appointment.setHospital(hospital);
    }

    private String getFullName(String first, String last) {
        return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
    }
}
