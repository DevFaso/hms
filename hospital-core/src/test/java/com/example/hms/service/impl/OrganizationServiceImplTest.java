package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.model.Organization;
import com.example.hms.repository.OrganizationRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private OrganizationServiceImpl service;

    private Organization organization;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        organization = Organization.builder()
            .name("Central Hospital")
            .code("central")
            .build();
        organizationId = UUID.randomUUID();
    }

    @Test
    void getAllOrganizationsReturnsRepositoryResult() {
        List<Organization> organizations = List.of(organization);
        when(organizationRepository.findAll()).thenReturn(organizations);

        assertThat(service.getAllOrganizations()).isEqualTo(organizations);
        verify(organizationRepository).findAll();
    }

    @Test
    void getOrganizationByIdReturnsMatchWhenPresent() {
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));

        assertThat(service.getOrganizationById(organizationId)).isSameAs(organization);
        verify(organizationRepository).findById(organizationId);
    }

    @Test
    void getOrganizationByIdReturnsNullWhenMissing() {
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

        assertThat(service.getOrganizationById(organizationId)).isNull();
        verify(organizationRepository).findById(organizationId);
    }

    @Test
    void createOrganizationPersistsAndReturnsEntity() {
        when(organizationRepository.save(organization)).thenReturn(organization);

        assertThat(service.createOrganization(organization)).isSameAs(organization);
        verify(organizationRepository).save(organization);
    }

    @Test
    void updateOrganizationAppliesIdBeforeSaving() {
        when(organizationRepository.save(any(Organization.class)))
            .thenAnswer(invocation -> invocation.getArgument(0, Organization.class));

        Organization updated = service.updateOrganization(organizationId, organization);

        assertThat(updated.getId()).isEqualTo(organizationId);
        assertThat(organization.getId()).isEqualTo(organizationId);
        verify(organizationRepository).save(organization);
    }

    @Test
    void deleteOrganizationDelegatesToRepository() {
        service.deleteOrganization(organizationId);

        verify(organizationRepository).deleteById(organizationId);
    }
}
