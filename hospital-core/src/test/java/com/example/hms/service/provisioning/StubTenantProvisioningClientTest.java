package com.example.hms.service.provisioning;

import com.example.hms.exception.RemoteProvisioningNotConfiguredException;
import com.example.hms.payload.dto.superadmin.SuperAdminCreateOrganizationRequestDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubTenantProvisioningClientTest {

    private final StubTenantProvisioningClient client = new StubTenantProvisioningClient();

    @Test
    void provisionRemote_alwaysRejectsWith501ResponseStatusException() {
        SuperAdminCreateOrganizationRequestDTO request = SuperAdminCreateOrganizationRequestDTO.builder()
            .name("EU Tenant")
            .code("EU-TENANT")
            .build();

        assertThatThrownBy(() ->
            client.provisionRemote(request, "https://eu.hms.example/api"))
            .isInstanceOf(RemoteProvisioningNotConfiguredException.class)
            .hasMessageContaining("https://eu.hms.example/api")
            .hasMessageContaining("no remote TenantProvisioningClient implementation is wired");
    }

    @Test
    void provisionRemote_withNullRequest_stillRejects() {
        assertThatThrownBy(() ->
            client.provisionRemote(null, "https://eu.hms.example/api"))
            .isInstanceOf(RemoteProvisioningNotConfiguredException.class);
    }

    @Test
    void isRemoteCapable_returnsFalseSoTheRegionPolicyEditorCanBlockWrites() {
        assertThat(client.isRemoteCapable()).isFalse();
    }
}
