package com.example.hms.mapper;

import com.example.hms.model.empi.EmpiIdentityAlias;
import com.example.hms.model.empi.EmpiMasterIdentity;
import com.example.hms.model.empi.EmpiMergeEvent;
import com.example.hms.payload.dto.empi.EmpiAliasRequestDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityAliasDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityLinkRequestDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.payload.dto.empi.EmpiMergeEventResponseDTO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class EmpiMapper {

    public EmpiIdentityResponseDTO toIdentityDto(EmpiMasterIdentity identity) {
        if (identity == null) {
            return null;
        }
        List<EmpiIdentityAliasDTO> aliasDtos = Optional.ofNullable(identity.getAliases())
            .orElseGet(Collections::emptySet)
            .stream()
            .sorted(Comparator.comparing(EmpiIdentityAlias::getCreatedAt))
            .map(this::toAliasDto)
            .toList();

        return EmpiIdentityResponseDTO.builder()
            .id(identity.getId())
            .empiNumber(identity.getEmpiNumber())
            .patientId(identity.getPatientId())
            .organizationId(identity.getOrganizationId())
            .hospitalId(identity.getHospitalId())
            .departmentId(identity.getDepartmentId())
            .status(identity.getStatus())
            .resolutionState(identity.getResolutionState())
            .active(identity.isActive())
            .sourceSystem(identity.getSourceSystem())
            .mrnSnapshot(identity.getMrnSnapshot())
            .metadata(identity.getMetadata())
            .aliases(aliasDtos)
            .createdAt(identity.getCreatedAt())
            .updatedAt(identity.getUpdatedAt())
            .build();
    }

    public EmpiIdentityAliasDTO toAliasDto(EmpiIdentityAlias alias) {
        if (alias == null) {
            return null;
        }
        return EmpiIdentityAliasDTO.builder()
            .id(alias.getId())
            .aliasType(alias.getAliasType())
            .aliasValue(alias.getAliasValue())
            .sourceSystem(alias.getSourceSystem())
            .active(alias.isActive())
            .createdAt(alias.getCreatedAt())
            .updatedAt(alias.getUpdatedAt())
            .build();
    }

    public EmpiMergeEventResponseDTO toMergeEventDto(EmpiMergeEvent event) {
        if (event == null) {
            return null;
        }
        return EmpiMergeEventResponseDTO.builder()
            .id(event.getId())
            .primaryIdentityId(mapIdentityId(event.getPrimaryIdentity()))
            .secondaryIdentityId(mapIdentityId(event.getSecondaryIdentity()))
            .organizationId(event.getOrganizationId())
            .hospitalId(event.getHospitalId())
            .departmentId(event.getDepartmentId())
            .mergeType(event.getMergeType())
            .resolution(event.getResolution())
            .notes(event.getNotes())
            .undoToken(event.getUndoToken())
            .mergedBy(event.getMergedBy())
            .mergedAt(event.getMergedAt())
            .build();
    }

    public EmpiIdentityAlias createAliasFromRequest(EmpiAliasRequestDTO dto) {
        if (dto == null) {
            return null;
        }
        return EmpiIdentityAlias.builder()
            .aliasType(dto.getAliasType())
            .aliasValue(dto.getAliasValue())
            .sourceSystem(dto.getSourceSystem())
            .active(true)
            .build();
    }

    public EmpiMasterIdentity initializeIdentity(EmpiIdentityLinkRequestDTO dto) {
        if (dto == null) {
            return null;
        }
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .patientId(dto.getPatientId())
            .organizationId(dto.getOrganizationId())
            .hospitalId(dto.getHospitalId())
            .departmentId(dto.getDepartmentId())
            .sourceSystem(dto.getSourceSystem())
            .mrnSnapshot(dto.getMrnSnapshot())
            .metadata(dto.getMetadata())
            .build();
        identity.setActive(true);
        return identity;
    }

    public void updateIdentityFromLinkRequest(EmpiIdentityLinkRequestDTO dto, EmpiMasterIdentity identity) {
        if (dto == null || identity == null) {
            return;
        }
        identity.setPatientId(dto.getPatientId());
        identity.setOrganizationId(Optional.ofNullable(dto.getOrganizationId()).orElse(identity.getOrganizationId()));
        identity.setHospitalId(Optional.ofNullable(dto.getHospitalId()).orElse(identity.getHospitalId()));
        identity.setDepartmentId(Optional.ofNullable(dto.getDepartmentId()).orElse(identity.getDepartmentId()));
        identity.setSourceSystem(Optional.ofNullable(dto.getSourceSystem()).orElse(identity.getSourceSystem()));
        identity.setMetadata(Optional.ofNullable(dto.getMetadata()).orElse(identity.getMetadata()));
        identity.setMrnSnapshot(Optional.ofNullable(dto.getMrnSnapshot()).orElse(identity.getMrnSnapshot()));
    }

    public List<EmpiIdentityAliasDTO> toAliasDtoList(List<EmpiIdentityAlias> aliases) {
        return Optional.ofNullable(aliases)
            .orElse(List.of())
            .stream()
            .map(this::toAliasDto)
            .toList();
    }

    private UUID mapIdentityId(EmpiMasterIdentity identity) {
        return Optional.ofNullable(identity)
            .map(EmpiMasterIdentity::getId)
            .orElse(null);
    }
}
