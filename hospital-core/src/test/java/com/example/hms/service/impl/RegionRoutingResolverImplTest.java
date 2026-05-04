package com.example.hms.service.impl;

import com.example.hms.enums.OrganizationRegion;
import com.example.hms.service.RegionPolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegionRoutingResolverImplTest {

    @Mock
    private RegionPolicyService regionPolicyService;

    @InjectMocks
    private RegionRoutingResolverImpl resolver;

    @Test
    void resolveDeploymentUrl_nullRegion_returnsEmptyAndDoesNotConsultPolicy() {
        Optional<String> result = resolver.resolveDeploymentUrl(null);

        assertThat(result).isEmpty();
        verifyNoInteractions(regionPolicyService);
    }

    @Test
    void resolveDeploymentUrl_policyReturnsNull_returnsEmpty() {
        when(regionPolicyService.resolveTargetDeploymentUrl(OrganizationRegion.BF)).thenReturn(null);

        Optional<String> result = resolver.resolveDeploymentUrl(OrganizationRegion.BF);

        assertThat(result).isEmpty();
    }

    @Test
    void resolveDeploymentUrl_policyReturnsBlank_returnsEmpty() {
        when(regionPolicyService.resolveTargetDeploymentUrl(OrganizationRegion.BF)).thenReturn("   ");

        Optional<String> result = resolver.resolveDeploymentUrl(OrganizationRegion.BF);

        assertThat(result).isEmpty();
    }

    @Test
    void resolveDeploymentUrl_policyReturnsUrl_returnsTrimmed() {
        when(regionPolicyService.resolveTargetDeploymentUrl(OrganizationRegion.EU))
            .thenReturn("  https://eu.hms.example/api  ");

        Optional<String> result = resolver.resolveDeploymentUrl(OrganizationRegion.EU);

        assertThat(result).contains("https://eu.hms.example/api");
    }
}
