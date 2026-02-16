package com.example.hms.mapper;

import com.example.hms.model.DischargeApproval;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.discharge.DischargeApprovalResponseDTO;
import org.springframework.stereotype.Component;


@Component
public class DischargeApprovalMapper {

    public DischargeApprovalResponseDTO toResponse(DischargeApproval entity) {
        if (entity == null) {
            return null;
        }

        var registration = entity.getRegistration();
        Patient patient = entity.getPatient();
        Hospital hospital = entity.getHospital();

        return DischargeApprovalResponseDTO.builder()
            .id(entity.getId())
            .status(entity.getStatus())
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patient != null ? patient.getFullName() : null)
            .registrationId(registration != null ? registration.getId() : null)
            .hospitalId(hospital != null ? hospital.getId() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .nurseStaffId(entity.getNurse() != null ? entity.getNurse().getId() : null)
            .nurseName(fullName(entity.getNurse()))
            .nurseAssignmentId(entity.getNurseAssignment() != null ? entity.getNurseAssignment().getId() : null)
            .doctorStaffId(entity.getDoctor() != null ? entity.getDoctor().getId() : null)
            .doctorName(fullName(entity.getDoctor()))
            .doctorAssignmentId(entity.getDoctorAssignment() != null ? entity.getDoctorAssignment().getId() : null)
            .nurseSummary(entity.getNurseSummary())
            .doctorNote(entity.getDoctorNote())
            .rejectionReason(entity.getRejectionReason())
            .requestedAt(entity.getRequestedAt())
            .approvedAt(entity.getApprovedAt())
            .resolvedAt(entity.getResolvedAt())
            .currentStayStatus(registration != null ? registration.getStayStatus() : null)
            .stayStatusUpdatedAt(registration != null ? registration.getStayStatusUpdatedAt() : null)
            .build();
    }

    private String fullName(Staff staff) {
        return staff != null ? staff.getFullName() : null;
    }
}
