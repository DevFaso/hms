package com.example.hms.service;

import com.example.hms.payload.dto.HospitalRequestDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.HospitalWithDepartmentsDTO;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface HospitalService {
    /**
     * The hospital list. A blank {@code facilityType} or HOSPITAL is the
     * clinical list everyone the endpoint admits reads; PHARMACY or
     * LABORATORY (any case) is the super-admin's explicit filter (provider
     * plan AC-11). Access is decided before the value is parsed.
     *
     * @param facilityType the raw request parameter
     * @throws org.springframework.security.access.AccessDeniedException for
     *         any other value from anyone but a verified super-admin
     * @throws com.example.hms.exception.BusinessException for a super-admin's
     *         unknown type ({@code provider.type.invalid})
     */
    List<HospitalResponseDTO> getAllHospitals(UUID organizationId,
                                              Boolean unassignedOnly,
                                              String city,
                                              String state,
                                              String facilityType,
                                              Locale locale);
    HospitalResponseDTO getHospitalById(UUID id, Locale locale);

    /**
     * Retrieve a hospital by ID without enforcing tenant scope.
     * Used by cross-hospital workflows such as patient consent management.
     */
    HospitalResponseDTO getHospitalByIdUnscoped(UUID id, Locale locale);

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

