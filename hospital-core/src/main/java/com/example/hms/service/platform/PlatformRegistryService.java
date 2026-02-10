package com.example.hms.service.platform;

import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.payload.dto.DepartmentPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.HospitalPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.PlatformServiceLinkRequestDTO;
import com.example.hms.payload.dto.PlatformServiceRegistrationRequestDTO;
import com.example.hms.payload.dto.PlatformServiceResponseDTO;
import com.example.hms.payload.dto.PlatformServiceUpdateRequestDTO;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface PlatformRegistryService {

    PlatformServiceResponseDTO registerOrganizationService(UUID organizationId,
                                                           PlatformServiceRegistrationRequestDTO request,
                                                           Locale locale);

    PlatformServiceResponseDTO updateOrganizationService(UUID organizationId,
                                                         UUID serviceId,
                                                         PlatformServiceUpdateRequestDTO request,
                                                         Locale locale);

    PlatformServiceResponseDTO getOrganizationService(UUID organizationId,
                                                      UUID serviceId,
                                                      Locale locale);

    List<PlatformServiceResponseDTO> listOrganizationServices(UUID organizationId,
                                                              PlatformServiceStatus status,
                                                              Locale locale);

    HospitalPlatformServiceLinkResponseDTO linkHospitalToService(UUID hospitalId,
                                                                 UUID organizationServiceId,
                                                                 PlatformServiceLinkRequestDTO request,
                                                                 Locale locale);

    void unlinkHospitalFromService(UUID hospitalId,
                                   UUID organizationServiceId,
                                   Locale locale);

    List<HospitalPlatformServiceLinkResponseDTO> listHospitalServiceLinks(UUID hospitalId,
                                                                          Locale locale);

    DepartmentPlatformServiceLinkResponseDTO linkDepartmentToService(UUID departmentId,
                                                                     UUID organizationServiceId,
                                                                     PlatformServiceLinkRequestDTO request,
                                                                     Locale locale);

    void unlinkDepartmentFromService(UUID departmentId,
                                     UUID organizationServiceId,
                                     Locale locale);

    List<DepartmentPlatformServiceLinkResponseDTO> listDepartmentServiceLinks(UUID departmentId,
                                                                              Locale locale);
}
