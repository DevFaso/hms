package com.example.hms.mapper;

import com.example.hms.model.Department;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffLeaveRequest;
import com.example.hms.model.StaffShift;
import com.example.hms.model.User;
import com.example.hms.payload.dto.StaffLeaveResponseDTO;
import com.example.hms.payload.dto.StaffShiftResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class StaffSchedulingMapper {

    public StaffShiftResponseDTO toShiftDto(StaffShift shift) {
        Staff staff = shift.getStaff();
        Department department = shift.getDepartment();
        User scheduledBy = shift.getScheduledBy();
        User lastModified = shift.getLastModifiedBy();

        return new StaffShiftResponseDTO(
            shift.getId(),
            staff != null ? staff.getId() : null,
            staff != null ? staff.getFullName() : null,
            staff != null && staff.getJobTitle() != null ? staff.getJobTitle().name() : null,
            shift.getHospital() != null ? shift.getHospital().getId() : null,
            shift.getHospital() != null ? shift.getHospital().getName() : null,
            department != null ? department.getId() : null,
            department != null ? department.getName() : null,
            shift.getShiftDate(),
            shift.getStartTime(),
            shift.getEndTime(),
            shift.getShiftType(),
            shift.getStatus(),
            shift.isPublished(),
            shift.getNotes(),
            shift.getCancellationReason(),
            scheduledBy != null ? scheduledBy.getId() : null,
            scheduledBy != null ? resolveDisplayName(scheduledBy) : null,
            lastModified != null ? lastModified.getId() : null,
            lastModified != null ? resolveDisplayName(lastModified) : null,
            shift.getStatusChangedAt(),
            shift.getCreatedAt(),
            shift.getUpdatedAt()
        );
    }

    public StaffLeaveResponseDTO toLeaveDto(StaffLeaveRequest leave) {
        Staff staff = leave.getStaff();
        Department department = leave.getDepartment();
        User requestedBy = leave.getRequestedBy();
        User reviewedBy = leave.getReviewedBy();

        return new StaffLeaveResponseDTO(
            leave.getId(),
            staff != null ? staff.getId() : null,
            staff != null ? staff.getFullName() : null,
            staff != null && staff.getJobTitle() != null ? staff.getJobTitle().name() : null,
            leave.getHospital() != null ? leave.getHospital().getId() : null,
            leave.getHospital() != null ? leave.getHospital().getName() : null,
            department != null ? department.getId() : null,
            department != null ? department.getName() : null,
            leave.getLeaveType(),
            leave.getStatus(),
            leave.getStartDate(),
            leave.getEndDate(),
            leave.getStartTime(),
            leave.getEndTime(),
            leave.isRequiresCoverage(),
            leave.getReason(),
            leave.getManagerNote(),
            requestedBy != null ? requestedBy.getId() : null,
            requestedBy != null ? resolveDisplayName(requestedBy) : null,
            reviewedBy != null ? reviewedBy.getId() : null,
            reviewedBy != null ? resolveDisplayName(reviewedBy) : null,
            leave.getReviewedAt(),
            leave.getCreatedAt(),
            leave.getUpdatedAt()
        );
    }

    private String resolveDisplayName(User user) {
        if (user.getFirstName() == null && user.getLastName() == null) {
            return user.getEmail();
        }
        String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String last = user.getLastName() != null ? user.getLastName().trim() : "";
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getEmail() : full;
    }
}
