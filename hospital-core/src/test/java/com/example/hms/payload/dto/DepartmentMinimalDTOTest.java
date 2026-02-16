package com.example.hms.payload.dto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DepartmentMinimalDTOTest {

    // ─── No-arg constructor ─────────────────────────────────────

    @Test
    void noArgConstructorCreatesEmptyInstance() {
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO();
        assertThat(dto.getId()).isNull();
        assertThat(dto.getName()).isNull();
        assertThat(dto.getEmail()).isNull();
        assertThat(dto.getPhoneNumber()).isNull();
    }

    // ─── All-arg constructor (4 fields) ─────────────────────────

    @Test
    void allArgsConstructor() {
        UUID id = UUID.randomUUID();
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO(id, "Cardiology", "cardio@hospital.com", "555-1234");

        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getName()).isEqualTo("Cardiology");
        assertThat(dto.getEmail()).isEqualTo("cardio@hospital.com");
        assertThat(dto.getPhoneNumber()).isEqualTo("555-1234");
    }

    // ─── Two-arg constructor (id, name) ─────────────────────────

    @Test
    void twoArgConstructor() {
        UUID id = UUID.randomUUID();
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO(id, "Radiology");

        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getName()).isEqualTo("Radiology");
        assertThat(dto.getEmail()).isNull();
        assertThat(dto.getPhoneNumber()).isNull();
    }

    // ─── Getters and Setters (Lombok @Data) ─────────────────────

    @Test
    void setAndGetId() {
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO();
        UUID id = UUID.randomUUID();
        dto.setId(id);
        assertThat(dto.getId()).isEqualTo(id);
    }

    @Test
    void setAndGetName() {
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO();
        dto.setName("Surgery");
        assertThat(dto.getName()).isEqualTo("Surgery");
    }

    @Test
    void setAndGetEmail() {
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO();
        dto.setEmail("surgery@hospital.com");
        assertThat(dto.getEmail()).isEqualTo("surgery@hospital.com");
    }

    @Test
    void setAndGetPhoneNumber() {
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO();
        dto.setPhoneNumber("555-5678");
        assertThat(dto.getPhoneNumber()).isEqualTo("555-5678");
    }

    // ─── equals / hashCode (Lombok @Data) ───────────────────────

    @Test
    void equalsSameValues() {
        UUID id = UUID.randomUUID();
        DepartmentMinimalDTO a = new DepartmentMinimalDTO(id, "Lab", "lab@h.com", "111");
        DepartmentMinimalDTO b = new DepartmentMinimalDTO(id, "Lab", "lab@h.com", "111");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void notEqualDifferentId() {
        DepartmentMinimalDTO a = new DepartmentMinimalDTO(UUID.randomUUID(), "Lab", null, null);
        DepartmentMinimalDTO b = new DepartmentMinimalDTO(UUID.randomUUID(), "Lab", null, null);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void notEqualDifferentName() {
        UUID id = UUID.randomUUID();
        DepartmentMinimalDTO a = new DepartmentMinimalDTO(id, "Lab", null, null);
        DepartmentMinimalDTO b = new DepartmentMinimalDTO(id, "Radiology", null, null);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void notEqualDifferentEmail() {
        UUID id = UUID.randomUUID();
        DepartmentMinimalDTO a = new DepartmentMinimalDTO(id, "Lab", "a@a.com", null);
        DepartmentMinimalDTO b = new DepartmentMinimalDTO(id, "Lab", "b@b.com", null);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void notEqualDifferentPhone() {
        UUID id = UUID.randomUUID();
        DepartmentMinimalDTO a = new DepartmentMinimalDTO(id, "Lab", null, "111");
        DepartmentMinimalDTO b = new DepartmentMinimalDTO(id, "Lab", null, "222");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void notEqualToNull() {
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO();
        assertThat(dto).isNotEqualTo(null);
    }

    @Test
    void notEqualToDifferentType() {
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO();
        assertThat(dto).isNotEqualTo("some string");
    }

    @Test
    void equalsReflexive() {
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO(UUID.randomUUID(), "A", "a@a.com", "1");
        assertThat(dto).isEqualTo(dto);
    }

    // ─── toString (Lombok @Data) ────────────────────────────────

    @Test
    void toStringContainsAllFields() {
        UUID id = UUID.randomUUID();
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO(id, "Lab", "lab@h.com", "111");
        String s = dto.toString();
        assertThat(s).contains("id=" + id)
            .contains("name=Lab")
            .contains("email=lab@h.com")
            .contains("phoneNumber=111");
    }

    // ─── canEqual (Lombok @Data generates this) ─────────────────

    @Test
    void canEqualSameType() {
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO();
        assertThat(dto.canEqual(new DepartmentMinimalDTO())).isTrue();
    }

    @Test
    void canEqualDifferentType() {
        DepartmentMinimalDTO dto = new DepartmentMinimalDTO();
        assertThat(dto.canEqual("string")).isFalse();
    }
}
