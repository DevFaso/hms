package com.example.hms.service;

import com.example.hms.model.Organization;
import java.util.List;
import java.util.UUID;

public interface OrganizationService {
    List<Organization> getAllOrganizations();
    Organization getOrganizationById(UUID id);
    Organization createOrganization(Organization organization);
    Organization updateOrganization(UUID id, Organization organization);
    void deleteOrganization(UUID id);
}
