package com.example.hms.controller;

import com.example.hms.model.Appointment;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.payload.dto.AppointmentSummaryDTO;
import java.util.List;
import org.springframework.http.ResponseEntity;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/lookup")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class LookupController {

    private final AppointmentRepository appointmentRepository;

    // Lookup appointments by patient email
    @GetMapping("/appointment/patient/email/{email}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByPatientEmail(@PathVariable String email) {
        List<AppointmentSummaryDTO> appointments = appointmentRepository.findByPatientEmail(email)
            .stream().map(this::toAppointmentSummaryDTO).toList();
        return ResponseEntity.ok(appointments);
    }

    // Lookup appointments by patient phone
    @GetMapping("/appointment/patient/phone/{phone}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByPatientPhone(@PathVariable String phone) {
        List<AppointmentSummaryDTO> appointments = appointmentRepository.findByPatientPhoneNumber(phone)
            .stream().map(this::toAppointmentSummaryDTO).toList();
        return ResponseEntity.ok(appointments);
    }

    // Lookup appointments by patient MRI
    @GetMapping("/appointment/patient/mri/{mri}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByPatientMri(@PathVariable String mri) {
        List<AppointmentSummaryDTO> appointments = appointmentRepository.findByPatientMri(mri)
            .stream().map(this::toAppointmentSummaryDTO).toList();
        return ResponseEntity.ok(appointments);
    }

    // Lookup appointments by staff number
    @GetMapping("/appointment/staff/number/{number}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByStaffNumber(@PathVariable String number) {
        List<AppointmentSummaryDTO> appointments = appointmentRepository.findByStaffNumber(number)
            .stream().map(this::toAppointmentSummaryDTO).toList();
        return ResponseEntity.ok(appointments);
    }

    // Lookup appointments by staff email
    @GetMapping("/appointment/staff/email/{email}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByStaffEmail(@PathVariable String email) {
        List<AppointmentSummaryDTO> appointments = appointmentRepository.findByStaffEmail(email)
            .stream().map(this::toAppointmentSummaryDTO).toList();
        return ResponseEntity.ok(appointments);
    }

    // Lookup appointments by staff id
    @GetMapping("/appointment/staff/id/{id}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByStaffId(@PathVariable UUID id) {
        List<AppointmentSummaryDTO> appointments = appointmentRepository.findByStaffId(id)
            .stream().map(this::toAppointmentSummaryDTO).toList();
        return ResponseEntity.ok(appointments);
    }

    // --- DTO mapping helper ---
    private AppointmentSummaryDTO toAppointmentSummaryDTO(Appointment a) {
        Patient patient = a.getPatient();
        Staff staff = a.getStaff();
        Department department = a.getDepartment();
        Hospital hospital = a.getHospital();
        return AppointmentSummaryDTO.builder()
            .id(a.getId())
            .status(a.getStatus())
            .appointmentDate(a.getAppointmentDate())
            .startTime(a.getStartTime())
            .endTime(a.getEndTime())
            .patientId(safeId(patient))
            .patientName(patient != null ? patient.getFullName() : null)
            .patientEmail(patient != null ? patient.getEmail() : null)
            .patientPhone(patient != null ? patient.getPhoneNumberPrimary() : null)
            .staffId(safeId(staff))
            .staffName(staff != null ? staff.getFullName() : null)
            .staffEmail(staffEmail(staff))
            .departmentId(safeId(department))
            .departmentName(department != null ? department.getName() : null)
            .departmentPhone(department != null ? department.getPhoneNumber() : null)
            .departmentEmail(department != null ? department.getEmail() : null)
            .hospitalId(safeId(hospital))
            .hospitalName(hospital != null ? hospital.getName() : null)
            .hospitalAddress(hospital != null ? hospital.getAddress() : null)
            .hospitalPhone(hospital != null ? hospital.getPhoneNumber() : null)
            .hospitalEmail(hospital != null ? hospital.getEmail() : null)
            .notes(a.getReason())
            .build();
    }

    private static UUID safeId(Patient p) { return p != null ? p.getId() : null; }
    private static UUID safeId(Staff s)   { return s != null ? s.getId() : null; }
    private static UUID safeId(Department d) { return d != null ? d.getId() : null; }
    private static UUID safeId(Hospital h) { return h != null ? h.getId() : null; }

    private static String staffEmail(Staff staff) {
        return staff != null && staff.getUser() != null ? staff.getUser().getEmail() : null;
    }
}
