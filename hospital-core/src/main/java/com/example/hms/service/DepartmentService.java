package com.example.hms.service;

import com.example.hms.payload.dto.DepartmentFilterDTO;
import com.example.hms.payload.dto.DepartmentMinimalDTO;
import com.example.hms.payload.dto.DepartmentRequestDTO;
import com.example.hms.payload.dto.DepartmentResponseDTO;
import com.example.hms.payload.dto.DepartmentStatsDTO;
import com.example.hms.payload.dto.DepartmentWithStaffDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface DepartmentService {
    List<DepartmentResponseDTO> getAllDepartments(UUID organizationId,
                                                  Boolean unassignedOnly,
                                                  String city,
                                                  String state,
                                                  Locale locale);
    Page<DepartmentResponseDTO> getAllDepartments(Pageable pageable, Locale locale);
    DepartmentResponseDTO getDepartmentById(UUID id, Locale locale);
    DepartmentResponseDTO createDepartment(DepartmentRequestDTO dto, Locale locale);
    Page<DepartmentResponseDTO> getDepartmentsByHospital(UUID hospitalId, Pageable pageable, Locale locale);
    DepartmentWithStaffDTO getDepartmentWithStaff(UUID departmentId, Locale locale);
    DepartmentResponseDTO updateDepartmentHead(UUID departmentId, UUID staffId, Locale locale);
    Page<DepartmentResponseDTO> searchDepartments(String query, Pageable pageable, Locale locale);
    Page<DepartmentResponseDTO> filterDepartments(DepartmentFilterDTO filter, Pageable pageable, Locale locale);
    DepartmentStatsDTO getDepartmentStatistics(UUID departmentId, Locale locale);
    List<DepartmentMinimalDTO> getActiveDepartmentsMinimal(UUID hospitalId, Locale locale);
    boolean isHeadOfDepartment(UUID staffId, Locale locale);
    DepartmentResponseDTO updateDepartment(UUID id, DepartmentRequestDTO dto, Locale locale);
    void deleteDepartment(UUID id, Locale locale);
}
