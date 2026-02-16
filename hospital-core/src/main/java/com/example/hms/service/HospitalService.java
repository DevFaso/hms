package com.example.hms.service;

import com.example.hms.payload.dto.HospitalRequestDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.HospitalWithDepartmentsDTO;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface HospitalService {
    List<HospitalResponseDTO> getAllHospitals(UUID organizationId,
                                              Boolean unassignedOnly,
                                              String city,
                                              String state,
                                              Locale locale);
    HospitalResponseDTO getHospitalById(UUID id, Locale locale);
    HospitalResponseDTO createHospital(HospitalRequestDTO hospitalRequestDTO, Locale locale);
    HospitalResponseDTO updateHospital(UUID id, HospitalRequestDTO hospitalRequestDTO, Locale locale);
    void deleteHospital(UUID id, Locale locale);
    List<HospitalResponseDTO> searchHospitals(String name, String city, String state, Boolean active, int page, int size, Locale locale);
    List<HospitalWithDepartmentsDTO> getHospitalsWithDepartments(String hospitalQuery,
                                                                 String departmentQuery,
                                                                 Boolean activeOnly,
                                                                 Locale locale);

    List<HospitalResponseDTO> getHospitalsByOrganization(UUID organizationId, Locale locale);
    HospitalResponseDTO assignHospitalToOrganization(UUID hospitalId, UUID organizationId, Locale locale);
    HospitalResponseDTO unassignHospitalFromOrganization(UUID hospitalId, Locale locale);

    default String generateHospitalCode(String name) {
        String prefix = name.replaceAll("[^A-Z]", "").toUpperCase();
        if (prefix.length() < 3) {
            prefix = String.format("%-3s", prefix).replace(' ', 'X');
        }
        return prefix.substring(0, 3) + "-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    }

}

