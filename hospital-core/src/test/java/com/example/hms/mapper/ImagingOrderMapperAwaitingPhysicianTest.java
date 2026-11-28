package com.example.hms.mapper;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D4140 review (r81): the response says a request waits for a physician exactly when the server refuses its
 * exam steps, for an order written before V465 too (no flag: a nurse's or a midwife's unsigned order), so the
 * portal shows the badge and the authorise, decline and withdraw actions.
 */
@DisplayName("QA D4140: the response's « waits for a physician » matches the server's rule")
class ImagingOrderMapperAwaitingPhysicianTest {

    private final UUID nurse = UUID.randomUUID();
    private RoleValidator roles;
    private ImagingOrderMapper mapper;
    private Hospital hospital;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        roles = mock(RoleValidator.class);
        ObjectProvider<RoleValidator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(roles);
        mapper = new ImagingOrderMapper();
        mapper.setRoleValidator(provider);
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        when(roles.draftsForPhysicianSignature(nurse, hospital.getId())).thenReturn(true);
    }

    private ImagingOrder order(ImagingOrderStatus status, Boolean flag, UUID provider) {
        ImagingOrder order = ImagingOrder.builder().hospital(hospital).modality(ImagingModality.XRAY).build();
        order.setId(UUID.randomUUID());
        order.setStatus(status);
        order.setPhysicianAuthorisationRequired(flag);
        order.setOrderingProviderUserId(provider);
        return order;
    }

    @ParameterizedTest
    @EnumSource(value = ImagingOrderStatus.class, names = {"ORDERED", "SCHEDULED", "IN_PROGRESS", "COMPLETED"})
    void anOlderUnsignedNursesOrderWaits(ImagingOrderStatus status) {
        assertThat(mapper.toResponseDTO(order(status, null, nurse)).getAwaitingPhysicianAuthorisation()).isTrue();
    }

    @Test
    void aSignedOrAPhysiciansOrderDoesNotWait() {
        ImagingOrder signed = order(ImagingOrderStatus.ORDERED, null, nurse);
        signed.setProviderSignedAt(LocalDateTime.now());
        assertThat(mapper.toResponseDTO(signed).getAwaitingPhysicianAuthorisation()).isFalse();
        assertThat(mapper.toResponseDTO(order(ImagingOrderStatus.ORDERED, null, UUID.randomUUID()))
            .getAwaitingPhysicianAuthorisation()).isFalse();
        assertThat(mapper.toResponseDTO(order(ImagingOrderStatus.CANCELLED, null, nurse))
            .getAwaitingPhysicianAuthorisation()).isFalse();
    }

    @Test
    void aFlaggedRequestWaitsWhileItIsADraft() {
        assertThat(mapper.toResponseDTO(order(ImagingOrderStatus.DRAFT, true, nurse))
            .getAwaitingPhysicianAuthorisation()).isTrue();
        assertThat(mapper.toResponseDTO(order(ImagingOrderStatus.DRAFT, false, nurse))
            .getAwaitingPhysicianAuthorisation()).isFalse();
    }
}
