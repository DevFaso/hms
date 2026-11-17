package com.example.hms.repository;

import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;

/**
 * The organization and hospital a repository slice seeds before anything
 * tenant-scoped can be saved. Unsaved: the caller persists them with whatever
 * it already uses ({@code TestEntityManager} or the repository).
 */
public final class HospitalFixtures {

    private HospitalFixtures() {
    }

    public static Organization organization(String name, String code) {
        return Organization.builder()
            .name(name)
            .code(code)
            .type(OrganizationType.HOSPITAL_CHAIN)
            .build();
    }

    /** A hospital with the NOT NULL address columns filled; active unless the caller says otherwise. */
    public static Hospital hospital(Organization organization, String name, String code) {
        return Hospital.builder()
            .name(name)
            .code(code)
            .address("1 Rue")
            .city("Ouagadougou")
            .country("BF")
            .organization(organization)
            .build();
    }
}
