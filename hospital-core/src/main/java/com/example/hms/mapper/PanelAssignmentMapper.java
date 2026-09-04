package com.example.hms.mapper;

import com.example.hms.model.PanelAssignment;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.panel.PanelAssignmentResponseDTO;
import org.springframework.stereotype.Component;

/**
 * Empanelment row → DTO (Tier 2 item 37), extracted from the service per
 * the house convention so mapping stays independently testable.
 */
@Component
public class PanelAssignmentMapper {

    public PanelAssignmentResponseDTO toDto(PanelAssignment a) {
        if (a == null) {
            return null;
        }
        return PanelAssignmentResponseDTO.builder()
            .id(a.getId())
            .patientId(a.getPatient() != null ? a.getPatient().getId() : null)
            .patientName(a.getPatient() != null ? a.getPatient().getFullName() : null)
            .providerStaffId(a.getProviderStaff() != null ? a.getProviderStaff().getId() : null)
            .providerName(staffName(a.getProviderStaff()))
            .panelRole(a.getPanelRole())
            .status(a.getStatus())
            .assignedOn(a.getAssignedOn())
            .assignedByName(staffName(a.getAssignedBy()))
            .endedOn(a.getEndedOn())
            .endReason(a.getEndReason())
            .build();
    }

    private static String staffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        if (staff.getName() != null && !staff.getName().isBlank()) {
            return staff.getName();
        }
        if (staff.getUser() != null) {
            String first = staff.getUser().getFirstName() == null ? "" : staff.getUser().getFirstName();
            String last = staff.getUser().getLastName() == null ? "" : staff.getUser().getLastName();
            String full = (first + " " + last).trim();
            return full.isEmpty() ? null : full;
        }
        return null;
    }
}
