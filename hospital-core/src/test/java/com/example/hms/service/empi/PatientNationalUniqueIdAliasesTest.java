package com.example.hms.service.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.empi.EmpiIdentityStatus;
import com.example.hms.mapper.EmpiMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.empi.EmpiIdentityAlias;
import com.example.hms.model.empi.EmpiMasterIdentity;
import com.example.hms.model.empi.EmpiMergeEvent;
import com.example.hms.repository.empi.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PatientNationalUniqueIdAliasesTest {
    private final EmpiIdentityAliasRepository aliases = mock(EmpiIdentityAliasRepository.class);
    private final EmpiMasterIdentityRepository identities = mock(EmpiMasterIdentityRepository.class);
    private final EmpiMergeEventRepository merges = mock(EmpiMergeEventRepository.class);
    private final PatientNationalUniqueIdAliases service = new PatientNationalUniqueIdAliases(aliases, identities, merges);

    @Test
    void optionalAbsentValueDoesNotProvisionAnIdentity() {
        Patient patient = patient();
        service.synchronize(patient);
        verifyNoInteractions(identities, merges);
        verify(aliases, never()).save(any());
    }

    @Test
    void aPrivateAliasCarriesOnlyTheFingerprintAndCannotAppearInPublicDtos() {
        Patient patient = patient(); patient.setNationalUniqueIdHash("a".repeat(64));
        EmpiMasterIdentity identity = identity();
        when(identities.findByPatientId(patient.getId())).thenReturn(Optional.of(identity));
        when(identities.findWithLockById(identity.getId())).thenReturn(Optional.of(identity));
        when(identities.findStatusById(identity.getId())).thenReturn(EmpiIdentityStatus.ACTIVE);
        service.synchronize(patient);
        var saved = org.mockito.ArgumentCaptor.forClass(EmpiIdentityAlias.class);
        verify(aliases).save(saved.capture());
        var alias = saved.getValue();
        assertThat(alias.getAliasType()).isEqualTo(EmpiAliasType.NATIONAL_ID);
        assertThat(alias.getAliasValue()).isEqualTo("a".repeat(64));
        assertThat(alias.getNationalUniqueIdPatientId()).isEqualTo(patient.getId());
        assertThat(new EmpiMapper().toAliasDto(alias)).isNull();
        assertThat(new EmpiMapper().toIdentityDto(identity).getAliases()).isEmpty();
        assertThat(alias.toString()).doesNotContain("a".repeat(64));
    }

    @Test
    void updateAndClearTouchOnlyThatPatientsAliasAfterAMerge() {
        Patient patient = patient(); patient.setNationalUniqueIdHash("b".repeat(64));
        EmpiMasterIdentity surviving = identity();
        EmpiIdentityAlias own = EmpiIdentityAlias.builder().aliasValue("a".repeat(64)).aliasType(EmpiAliasType.NATIONAL_ID)
            .nationalUniqueIdPatientId(patient.getId()).build(); own.setId(UUID.randomUUID()); surviving.addAlias(own);
        EmpiIdentityAlias other = EmpiIdentityAlias.builder().aliasValue("b".repeat(64)).aliasType(EmpiAliasType.NATIONAL_ID)
            .nationalUniqueIdPatientId(UUID.randomUUID()).build(); other.setId(UUID.randomUUID()); surviving.addAlias(other);
        when(aliases.findByNationalUniqueIdPatientId(patient.getId())).thenReturn(Optional.of(own));
        service.synchronize(patient);
        assertThat(own.getAliasValue()).isEqualTo("b".repeat(64));
        assertThat(own.getMasterIdentity()).isSameAs(surviving);
        patient.setNationalUniqueIdHash(null); service.synchronize(patient);
        verify(aliases).delete(own); verify(aliases, never()).delete(other);
        assertThat(surviving.getAliases()).containsExactly(other);
    }

    @Test
    void aFirstAliasOnAMergedPatientAttachesToTheSurvivingIdentity() {
        Patient patient = patient(); patient.setNationalUniqueIdHash("a".repeat(64));
        EmpiMasterIdentity retired = identity(), surviving = identity();
        when(identities.findByPatientId(patient.getId())).thenReturn(Optional.of(retired));
        when(identities.findWithLockById(retired.getId())).thenReturn(Optional.of(retired));
        when(identities.findWithLockById(surviving.getId())).thenReturn(Optional.of(surviving));
        when(identities.findStatusById(retired.getId())).thenReturn(EmpiIdentityStatus.MERGED);
        when(identities.findStatusById(surviving.getId())).thenReturn(EmpiIdentityStatus.ACTIVE);
        when(merges.findTopBySecondaryIdentity_IdOrderByMergedAtDesc(retired.getId()))
            .thenReturn(Optional.of(EmpiMergeEvent.builder().primaryIdentity(surviving).secondaryIdentity(retired).build()));
        service.synchronize(patient);
        var saved = org.mockito.ArgumentCaptor.forClass(EmpiIdentityAlias.class); verify(aliases).save(saved.capture());
        assertThat(saved.getValue().getMasterIdentity()).isSameAs(surviving);
    }

    private static Patient patient() { Patient patient = new Patient(); patient.setId(UUID.randomUUID()); return patient; }
    private static EmpiMasterIdentity identity() {
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder().empiNumber("EMP-123456").build();
        identity.setId(UUID.randomUUID()); return identity;
    }
}
