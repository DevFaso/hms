package com.example.hms.mapper;

import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.OrganizationRequestDTO;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D191: the organisation's contact fields are kept on create and update. */
class OrganizationMapperContactFieldsTest {

    private final OrganizationMapper mapper = new OrganizationMapper();

    @Test
    @DisplayName("create keeps the contact fields, under either JSON name")
    void createKeepsContacts() throws Exception {
        OrganizationRequestDTO dto = new ObjectMapper().readValue("""
            {"name":"Org","code":"ORG","type":"%s","primaryContactEmail":"desk@example.org",
             "contactPhone":"+22670000000","timezone":"Africa/Ouagadougou","notes":"n"}
            """.formatted(OrganizationType.values()[0].name()), OrganizationRequestDTO.class);

        Organization org = mapper.toEntity(dto);

        assertThat(org.getPrimaryContactEmail()).isEqualTo("desk@example.org");
        assertThat(org.getPrimaryContactPhone()).isEqualTo("+22670000000");
        assertThat(org.getDefaultTimezone()).isEqualTo("Africa/Ouagadougou");
        assertThat(org.getOnboardingNotes()).isEqualTo("n");
    }

    @Test
    @DisplayName("update changes a sent contact and leaves an absent one alone")
    void updateKeepsAbsentContacts() {
        Organization org = Organization.builder().name("Org").code("ORG")
            .primaryContactEmail("old@example.org").primaryContactPhone("+22670000000").build();
        OrganizationRequestDTO dto = OrganizationRequestDTO.builder().name("Org").code("ORG")
            .type(OrganizationType.values()[0]).contactEmail("new@example.org").build();

        mapper.updateEntity(org, dto);

        assertThat(org.getPrimaryContactEmail()).isEqualTo("new@example.org");
        assertThat(org.getPrimaryContactPhone()).isEqualTo("+22670000000");
    }
}
