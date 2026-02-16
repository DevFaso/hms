package com.example.hms.service.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.payload.dto.empi.EmpiAliasRequestDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityAliasDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityLinkRequestDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.payload.dto.empi.EmpiMergeEventResponseDTO;
import com.example.hms.payload.dto.empi.EmpiMergeRequestDTO;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface EmpiService {

    @Transactional
    EmpiIdentityResponseDTO linkIdentity(EmpiIdentityLinkRequestDTO request);

    @Transactional(readOnly = true)
    EmpiIdentityResponseDTO getIdentity(UUID identityId);

    @Transactional(readOnly = true)
    EmpiIdentityResponseDTO getIdentityByEmpiNumber(String empiNumber);

    @Transactional(readOnly = true)
    Optional<EmpiIdentityResponseDTO> findIdentityByPatientId(UUID patientId);

    @Transactional(readOnly = true)
    Optional<EmpiIdentityResponseDTO> findIdentityByAlias(EmpiAliasType aliasType, String aliasValue);

    @Transactional
    EmpiIdentityAliasDTO addAlias(UUID identityId, EmpiAliasRequestDTO request);

    @Transactional
    void removeAlias(UUID identityId, UUID aliasId);

    @Transactional
    EmpiMergeEventResponseDTO mergeIdentities(UUID primaryIdentityId, EmpiMergeRequestDTO request);
}
