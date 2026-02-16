package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.HospitalRequestDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class HospitalMapperTest {

    private final HospitalMapper mapper = new HospitalMapper();

    @Test
    void toHospitalDTO_nullReturnsNull() {
        assertThat(mapper.toHospitalDTO(null)).isNull();
    }

    @Test
    void toHospitalDTO_mapsAllFields() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("OrgName");
        org.setCode("ORG1");

        Hospital h = Hospital.builder()
                .name("TestHospital").code("TH01")
                .address("123 Main").city("NYC").state("NY")
                .zipCode("10001").country("US").province("P")
                .region("R").sector("S").poBox("PO123")
                .phoneNumber("555-0100").email("h@test.com")
                .website("https://test.com").active(true)
                .build();
        h.setId(UUID.randomUUID());
        h.setOrganization(org);
        h.setCreatedAt(LocalDateTime.now());
        h.setUpdatedAt(LocalDateTime.now());

        HospitalResponseDTO dto = mapper.toHospitalDTO(h);

        assertThat(dto.getId()).isEqualTo(h.getId());
        assertThat(dto.getName()).isEqualTo("TestHospital");
        assertThat(dto.getCode()).isEqualTo("TH01");
        assertThat(dto.getCity()).isEqualTo("NYC");
        assertThat(dto.getOrganizationId()).isEqualTo(org.getId());
        assertThat(dto.getOrganizationName()).isEqualTo("OrgName");
        assertThat(dto.isActive()).isTrue();
    }

    @Test
    void toHospitalDTO_nullOrganization() {
        Hospital h = Hospital.builder().name("H").build();
        HospitalResponseDTO dto = mapper.toHospitalDTO(h);
        assertThat(dto.getOrganizationId()).isNull();
    }

    @Test
    void toHospitalDTO_nullFieldsReturnEmptyStrings() {
        Hospital h = Hospital.builder().build();
        HospitalResponseDTO dto = mapper.toHospitalDTO(h);
        assertThat(dto.getName()).isEmpty();
        assertThat(dto.getCode()).isEmpty();
        assertThat(dto.getAddress()).isEmpty();
    }

    @Test
    void toHospital_fromRequestDTO_nullReturnsNull() {
        assertThat(mapper.toHospital((HospitalRequestDTO) null)).isNull();
    }

    @Test
    void toHospital_fromRequestDTO_mapsFields() {
        HospitalRequestDTO dto = new HospitalRequestDTO();
        dto.setName(" TestHospital ");
        dto.setCity("NYC");
        dto.setEmail("  H@Test.COM  ");
        dto.setWebsite("example.com");
        dto.setActive(true);

        Hospital h = mapper.toHospital(dto);

        assertThat(h.getName()).isEqualTo("TestHospital");
        assertThat(h.getCity()).isEqualTo("NYC");
        assertThat(h.getEmail()).isEqualTo("h@test.com");
        assertThat(h.getWebsite()).isEqualTo("https://example.com");
        assertThat(h.isActive()).isTrue();
    }

    @Test
    void toHospital_fromResponseDTO_nullReturnsNull() {
        assertThat(mapper.toHospital((HospitalResponseDTO) null)).isNull();
    }

    @Test
    void toHospital_fromResponseDTO_mapsIdAndTimestamps() {
        HospitalResponseDTO dto = HospitalResponseDTO.builder()
                .id(UUID.randomUUID())
                .name("H")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Hospital h = mapper.toHospital(dto);

        assertThat(h.getId()).isEqualTo(dto.getId());
        assertThat(h.getCreatedAt()).isEqualTo(dto.getCreatedAt());
    }

    @Test
    void updateHospitalFromDto_nullDtoNoOp() {
        Hospital h = Hospital.builder().name("Original").build();
        mapper.updateHospitalFromDto(null, h);
        assertThat(h.getName()).isEqualTo("Original");
    }

    @Test
    void updateHospitalFromDto_nullHospitalNoOp() {
        HospitalRequestDTO dto = new HospitalRequestDTO();
        dto.setName("New");
        // should not throw — silently returns when hospital is null
        assertThatNoException().isThrownBy(() -> mapper.updateHospitalFromDto(dto, null));
    }

    @Test
    void updateHospitalFromDto_updatesOnlyNonNullFields() {
        Hospital h = Hospital.builder().name("Old").city("OldCity").build();
        HospitalRequestDTO dto = new HospitalRequestDTO();
        dto.setName("New");
        // city is null in dto, should remain "OldCity"

        mapper.updateHospitalFromDto(dto, h);

        assertThat(h.getName()).isEqualTo("New");
        assertThat(h.getCity()).isEqualTo("OldCity");
    }

    @Test
    void toHospital_normalizesWebsiteWithScheme() {
        HospitalRequestDTO dto = new HospitalRequestDTO();
        dto.setWebsite("http://existing.com");
        Hospital h = mapper.toHospital(dto);
        assertThat(h.getWebsite()).isEqualTo("http://existing.com");
    }

    @Test
    void toHospital_blankEmailReturnsNull() {
        HospitalRequestDTO dto = new HospitalRequestDTO();
        dto.setEmail("   ");
        Hospital h = mapper.toHospital(dto);
        assertThat(h.getEmail()).isNull();
    }

    @Test
    void toHospital_blankWebsiteReturnsNull() {
        HospitalRequestDTO dto = new HospitalRequestDTO();
        dto.setWebsite("  ");
        Hospital h = mapper.toHospital(dto);
        assertThat(h.getWebsite()).isNull();
    }
}
