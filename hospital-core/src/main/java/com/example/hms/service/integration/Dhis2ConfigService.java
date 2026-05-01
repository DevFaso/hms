package com.example.hms.service.integration;

import com.example.hms.payload.dto.integration.Dhis2DataElementMappingRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2DataElementMappingResponseDTO;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigResponseDTO;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * CRUD for the per-hospital DHIS2 facility config and dataElement
 * mappings. Auth secrets stay server-side; nothing on this surface
 * accepts or returns the secret value.
 */
public interface Dhis2ConfigService {

    Optional<Dhis2FacilityConfigResponseDTO> getFacilityConfig(UUID hospitalId);

    Dhis2FacilityConfigResponseDTO upsertFacilityConfig(UUID hospitalId,
                                                        Dhis2FacilityConfigRequestDTO request);

    Page<Dhis2DataElementMappingResponseDTO> listMappings(UUID hospitalId,
                                                          String datasetUid,
                                                          Pageable pageable);

    Dhis2DataElementMappingResponseDTO createMapping(UUID hospitalId,
                                                     Dhis2DataElementMappingRequestDTO request);

    Dhis2DataElementMappingResponseDTO updateMapping(UUID mappingId,
                                                     UUID hospitalId,
                                                     Dhis2DataElementMappingRequestDTO request);

    void deleteMapping(UUID mappingId, UUID hospitalId);
}
