package com.example.hms.service.impl;

import com.example.hms.model.Organization;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl implements OrganizationService {
    private final OrganizationRepository organizationRepository;

    @Override
    public List<Organization> getAllOrganizations() {
        return organizationRepository.findAll();
    }

    @Override
    public Organization getOrganizationById(UUID id) {
        return organizationRepository.findById(id).orElse(null);
    }

    @Override
    public Organization createOrganization(Organization organization) {
        return organizationRepository.save(organization);
    }

    @Override
    public Organization updateOrganization(UUID id, Organization organization) {
        organization.setId(id);
        return organizationRepository.save(organization);
    }

    @Override
    public void deleteOrganization(UUID id) {
        organizationRepository.deleteById(id);
    }
}
