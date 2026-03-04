package com.example.hms.controller;

import com.example.hms.model.Appointment;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.utility.RoleValidator;
import com.example.hms.payload.dto.AppointmentSummaryDTO;
import java.util.List;
import org.springframework.http.ResponseEntity;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lookup")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class LookupController {

    private final AppointmentRepository appointmentRepository;
    private final RoleValidator roleValidator;

    // Lookup appointments by patient email
    @GetMapping("/appointment/patient/email/{email}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByPatientEmail(@PathVariable String email) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<Appointment> appointments;
        if (activeHospitalId != null) {
            appointments = appointmentRepository.findByPatientEmailAndHospitalId(email, activeHospitalId);
        } else {
            appointments = appointmentRepository.findByPatientEmail(email);
        }
        return ResponseEntity.ok(appointments.stream().map(this::toAppointmentSummaryDTO).toList());
    }

    // Lookup appointments by patient phone
    @GetMapping("/appointment/patient/phone/{phone}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByPatientPhone(@PathVariable String phone) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<Appointment> appointments;
        if (activeHospitalId != null) {
            appointments = appointmentRepository.findByPatientPhoneNumberAndHospitalId(phone, activeHospitalId);
        } else {
            appointments = appointmentRepository.findByPatientPhoneNumber(phone);
        }
        return ResponseEntity.ok(appointments.stream().map(this::toAppointmentSummaryDTO).toList());
    }

    // Lookup appointments by patient MRI
    @GetMapping("/appointment/patient/mri/{mri}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByPatientMri(@PathVariable String mri) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<Appointment> appointments;
        if (activeHospitalId != null) {
            appointments = appointmentRepository.findByPatientMriAndHospitalId(mri, activeHospitalId);
        } else {
            appointments = appointmentRepository.findByPatientMri(mri);
        }
        return ResponseEntity.ok(appointments.stream().map(this::toAppointmentSummaryDTO).toList());
    }

    // Lookup appointments by staff number
    @GetMapping("/appointment/staff/number/{number}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByStaffNumber(@PathVariable String number) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<Appointment> appointments;
        if (activeHospitalId != null) {
            appointments = appointmentRepository.findByStaffNumberAndHospitalId(number, activeHospitalId);
        } else {
            appointments = appointmentRepository.findByStaffNumber(number);
        }
        return ResponseEntity.ok(appointments.stream().map(this::toAppointmentSummaryDTO).toList());
    }

    // Lookup appointments by staff email
    @GetMapping("/appointment/staff/email/{email}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByStaffEmail(@PathVariable String email) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<Appointment> appointments;
        if (activeHospitalId != null) {
            appointments = appointmentRepository.findByStaffEmailAndHospitalId(email, activeHospitalId);
        } else {
            appointments = appointmentRepository.findByStaffEmail(email);
        }
        return ResponseEntity.ok(appointments.stream().map(this::toAppointmentSummaryDTO).toList());
    }

    // Lookup appointments by staff id
    @GetMapping("/appointment/staff/id/{id}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentSummaryDTO>> getAppointmentsByStaffId(@PathVariable UUID id) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<Appointment> appointments;
        if (activeHospitalId != null) {
            appointments = appointmentRepository.findByStaffIdAndHospitalId(id, activeHospitalId);
        } else {
            appointments = appointmentRepository.findByStaffId(id);
        }
        return ResponseEntity.ok(appointments.stream().map(this::toAppointmentSummaryDTO).toList());
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
