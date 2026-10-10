package com.example.hms.mapper;

import com.example.hms.enums.FacilityType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.OrganizationResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider plan AC-11: an organisation lists (and so counts) its clinical hospitals only. */
class OrganizationMapperClinicalHospitalsTest {

    private static Hospital facility(String name, FacilityType type) {
        Hospital hospital = Hospital.builder().name(name).code(name).active(true).build();
        hospital.setId(UUID.randomUUID());
        hospital.setFacilityType(type);
        return hospital;
    }

    @Test
    @DisplayName("a pharmacy or laboratory row in the organisation is not one of its hospitals")
    void providersAreNotListed() {
        Organization organization = Organization.builder().name("Org").code("ORG").active(true).build();
        organization.setId(UUID.randomUUID());
        organization.addHospital(facility("CHU", FacilityType.HOSPITAL));
        organization.addHospital(facility("PHARMA", FacilityType.PHARMACY));
        organization.addHospital(facility("LABO", FacilityType.LABORATORY));

        OrganizationResponseDTO dto = new OrganizationMapper().toResponseDTO(organization);

        assertThat(dto.getHospitals()).extracting(OrganizationResponseDTO.HospitalMinimalDTO::getCode)
            .containsExactly("CHU");
    }
}
