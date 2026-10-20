package com.example.hms.security;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallerIdentityTest {

    @AfterEach
    void clear() {
        CallerIdentityTestSupport.clear();
    }

    @Test
    void hospitalIdOfIsNullForNoHospitalAndTheIdOtherwise() {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        assertThat(CallerIdentity.hospitalIdOf(null)).isNull();
        assertThat(CallerIdentity.hospitalIdOf(hospital)).isEqualTo(hospital.getId());
    }

    @Test
    void aClaimedColleagueIsRefusedButTheCallerIsAccepted() {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Staff me = new Staff();
        me.setId(UUID.randomUUID());
        Staff colleague = new Staff();
        colleague.setId(UUID.randomUUID());
        colleague.setHospital(hospital);
        CallerIdentityTestSupport.actAs(me, hospital);
        StaffRepository repo = mock(StaffRepository.class);
        when(repo.findById(me.getId())).thenReturn(Optional.of(me));
        when(repo.findById(colleague.getId())).thenReturn(Optional.of(colleague));

        assertThat(CallerIdentity.resolveActor(repo, me.getId(), hospital.getId())).isSameAs(me);
        UUID colleagueId = colleague.getId();
        UUID hospitalId = hospital.getId();
        assertThatThrownBy(() -> CallerIdentity.resolveActor(repo, colleagueId, hospitalId))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> CallerIdentity.requireAtHospital(me, UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
