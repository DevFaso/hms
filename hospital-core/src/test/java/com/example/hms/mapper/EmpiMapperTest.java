package com.example.hms.mapper;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.model.empi.EmpiIdentityAlias;
import com.example.hms.model.empi.EmpiMasterIdentity;
import com.example.hms.payload.dto.empi.EmpiAliasRequestDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityLinkRequestDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmpiMapperTest {

    private final EmpiMapper mapper = new EmpiMapper();

    @Test
    void toIdentityDtoSortsAliasesByCreatedAt() {
        EmpiIdentityAlias newest = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.MRN)
            .aliasValue("mrn-late")
            .build();
        newest.setCreatedAt(LocalDateTime.now());

        EmpiIdentityAlias oldest = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.PASSPORT)
            .aliasValue("passport-early")
            .build();
        oldest.setCreatedAt(LocalDateTime.now().minusDays(2));

        EmpiIdentityAlias middle = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.NATIONAL_ID)
            .aliasValue("national-mid")
            .build();
        middle.setCreatedAt(LocalDateTime.now().minusDays(1));

        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-123456")
            .build();
        identity.setId(UUID.randomUUID());
        identity.getAliases().addAll(List.of(newest, oldest, middle));

        EmpiIdentityResponseDTO dto = mapper.toIdentityDto(identity);

        assertThat(dto.getAliases())
            .extracting(a -> a.getAliasValue())
            .containsExactly("passport-early", "national-mid", "mrn-late");
    }

    @Test
    void toIdentityDtoReturnsNullWhenIdentityNull() {
        assertThat(mapper.toIdentityDto(null)).isNull();
    }

    @Test
    void updateIdentityFromLinkRequestUsesExistingValuesWhenDtoNull() {
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .organizationId(UUID.randomUUID())
            .hospitalId(UUID.randomUUID())
            .departmentId(UUID.randomUUID())
            .sourceSystem("ehr")
            .metadata("meta")
            .mrnSnapshot("MRN-1")
            .build();

        mapper.updateIdentityFromLinkRequest(null, identity);

        assertThat(identity.getSourceSystem()).isEqualTo("ehr");
    }

    @Test
    void updateIdentityFromLinkRequestMergesNonNullProperties() {
        UUID patientId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        EmpiIdentityLinkRequestDTO dto = new EmpiIdentityLinkRequestDTO();
        dto.setPatientId(patientId);
        dto.setOrganizationId(organizationId);
        dto.setSourceSystem("new-source");

        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .sourceSystem("old")
            .build();

        mapper.updateIdentityFromLinkRequest(dto, identity);

        assertThat(identity.getPatientId()).isEqualTo(patientId);
        assertThat(identity.getOrganizationId()).isEqualTo(organizationId);
        assertThat(identity.getSourceSystem()).isEqualTo("new-source");
    }

    @Test
    void initializeIdentitySetsActiveTrue() {
        EmpiIdentityLinkRequestDTO dto = new EmpiIdentityLinkRequestDTO();
        UUID patientId = UUID.randomUUID();
        dto.setPatientId(patientId);

        EmpiMasterIdentity identity = mapper.initializeIdentity(dto);

        assertThat(identity.isActive()).isTrue();
        assertThat(identity.getPatientId()).isEqualTo(patientId);
    }

    @Test
    void createAliasFromRequestConfiguresDefaults() {
        EmpiAliasRequestDTO request = new EmpiAliasRequestDTO();
        request.setAliasType(EmpiAliasType.NATIONAL_ID);
        request.setAliasValue("VALUE");
        request.setSourceSystem("source");

        EmpiIdentityAlias alias = mapper.createAliasFromRequest(request);

        assertThat(alias.getAliasType()).isEqualTo(EmpiAliasType.NATIONAL_ID);
        assertThat(alias.isActive()).isTrue();
    }

    @Test
    void toAliasDtoListReturnsEmptyForNull() {
        assertThat(mapper.toAliasDtoList(null)).isEmpty();
    }
}
