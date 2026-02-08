package com.example.hms.mapper;

import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffAvailability;
import com.example.hms.payload.dto.StaffAvailabilityRequestDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class StaffAvailabilityMapper {

    public StaffAvailability toEntity(StaffAvailabilityRequestDTO dto,
                                      Staff staff,
                                      Hospital hospital,
                                      Department department) {
        return StaffAvailability.builder()
            .staff(staff)
            .hospital(hospital)
            .date(dto.date())
            .availableFrom(dto.availableFrom())
            .availableTo(dto.availableTo())
            .dayOff(dto.dayOff())
            .note(dto.note())
            .active(true)
            .build();
    }

    public StaffAvailabilityResponseDTO toDto(StaffAvailability entity) {
        var staff = entity.getStaff();
        var hospital = entity.getHospital();
        var dept = (staff != null) ? staff.getDepartment() : null;

        return new StaffAvailabilityResponseDTO(
            entity.getId(),
            (staff != null ? staff.getId() : null),
            (staff != null ? staff.getName() : null),
            (staff != null ? staff.getLicenseNumber() : null),
            (hospital != null ? hospital.getId() : null),
            (hospital != null ? hospital.getName() : null),
            (dept != null ? dept.getId() : null),
            (dept != null ? dept.getName() : null),
            (dept != null && dept.getDepartmentTranslations() != null
                ? dept.getDepartmentTranslations().toString() : null),
            entity.getDate(),
            entity.getAvailableFrom(),
            entity.getAvailableTo(),
            entity.isDayOff(),
            entity.getNote()
        );
    }
}
