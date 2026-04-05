package com.example.hms.mapper;

import com.example.hms.enums.InstrumentStatus;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabInstrument;
import com.example.hms.payload.dto.LabInstrumentRequestDTO;
import com.example.hms.payload.dto.LabInstrumentResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class LabInstrumentMapper {

    public LabInstrumentResponseDTO toDto(LabInstrument entity) {
        if (entity == null) return null;

        LocalDate today = LocalDate.now();
        boolean calibrationOverdue = entity.getNextCalibrationDate() != null
            && !entity.getNextCalibrationDate().isAfter(today);
        boolean maintenanceOverdue = entity.getNextMaintenanceDate() != null
            && !entity.getNextMaintenanceDate().isAfter(today);

        return LabInstrumentResponseDTO.builder()
            .id(entity.getId() != null ? entity.getId().toString() : null)
            .name(entity.getName())
            .manufacturer(entity.getManufacturer())
            .modelNumber(entity.getModelNumber())
            .serialNumber(entity.getSerialNumber())
            .hospitalId(entity.getHospital() != null ? entity.getHospital().getId().toString() : null)
            .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : null)
            .departmentId(entity.getDepartment() != null ? entity.getDepartment().getId().toString() : null)
            .departmentName(entity.getDepartment() != null ? entity.getDepartment().getName() : null)
            .status(entity.getStatus() != null ? entity.getStatus().name() : null)
            .installationDate(entity.getInstallationDate())
            .lastCalibrationDate(entity.getLastCalibrationDate())
            .nextCalibrationDate(entity.getNextCalibrationDate())
            .lastMaintenanceDate(entity.getLastMaintenanceDate())
            .nextMaintenanceDate(entity.getNextMaintenanceDate())
            .maintenanceOverdue(maintenanceOverdue)
            .calibrationOverdue(calibrationOverdue)
            .notes(entity.getNotes())
            .active(entity.isActive())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public LabInstrument toEntity(LabInstrumentRequestDTO dto, Hospital hospital, Department department) {
        if (dto == null) return null;

        InstrumentStatus status = InstrumentStatus.ACTIVE;
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            try {
                status = InstrumentStatus.valueOf(dto.getStatus().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("Invalid instrument status: " + dto.getStatus());
            }
        }

        return LabInstrument.builder()
            .name(dto.getName())
            .manufacturer(dto.getManufacturer())
            .modelNumber(dto.getModelNumber())
            .serialNumber(dto.getSerialNumber())
            .hospital(hospital)
            .department(department)
            .status(status)
            .installationDate(dto.getInstallationDate())
            .lastCalibrationDate(dto.getLastCalibrationDate())
            .nextCalibrationDate(dto.getNextCalibrationDate())
            .lastMaintenanceDate(dto.getLastMaintenanceDate())
            .nextMaintenanceDate(dto.getNextMaintenanceDate())
            .notes(dto.getNotes())
            .build();
    }

    public void updateEntity(LabInstrument entity, LabInstrumentRequestDTO dto, Department department) {
        if (dto.getName() != null) entity.setName(dto.getName());
        if (dto.getManufacturer() != null) entity.setManufacturer(dto.getManufacturer());
        if (dto.getModelNumber() != null) entity.setModelNumber(dto.getModelNumber());
        if (dto.getSerialNumber() != null) entity.setSerialNumber(dto.getSerialNumber());
        if (department != null) entity.setDepartment(department);
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            try {
                entity.setStatus(InstrumentStatus.valueOf(dto.getStatus().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("Invalid instrument status: " + dto.getStatus());
            }
        }
        if (dto.getInstallationDate() != null) entity.setInstallationDate(dto.getInstallationDate());
        if (dto.getLastCalibrationDate() != null) entity.setLastCalibrationDate(dto.getLastCalibrationDate());
        if (dto.getNextCalibrationDate() != null) entity.setNextCalibrationDate(dto.getNextCalibrationDate());
        if (dto.getLastMaintenanceDate() != null) entity.setLastMaintenanceDate(dto.getLastMaintenanceDate());
        if (dto.getNextMaintenanceDate() != null) entity.setNextMaintenanceDate(dto.getNextMaintenanceDate());
        if (dto.getNotes() != null) entity.setNotes(dto.getNotes());
    }
}
