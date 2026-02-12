package com.example.hms.payload.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class BaseUserDTOTest {

    /**
     * Concrete subclass so we can instantiate the abstract BaseUserDTO.
     */
    private static class TestUserDTO extends BaseUserDTO { }

    private TestUserDTO createDto() {
        return new TestUserDTO();
    }

    // ═══════════════ Default state ═══════════════

    @Nested
    @DisplayName("Default state")
    class DefaultState {

        @Test
        @DisplayName("all fields default to null")
        void allFieldsNull() {
            TestUserDTO dto = createDto();
            assertAll(
                () -> assertNull(dto.getUsername()),
                () -> assertNull(dto.getEmail()),
                () -> assertNull(dto.getFirstName()),
                () -> assertNull(dto.getLastName()),
                () -> assertNull(dto.getPhoneNumber()),
                () -> assertNull(dto.getDateOfBirth())
            );
        }
    }

    // ═══════════════ Getters / Setters ═══════════════

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSetters {

        @Test
        @DisplayName("username getter/setter")
        void username() {
            TestUserDTO dto = createDto();
            dto.setUsername("jdoe");
            assertEquals("jdoe", dto.getUsername());
        }

        @Test
        @DisplayName("email getter/setter")
        void email() {
            TestUserDTO dto = createDto();
            dto.setEmail("jdoe@example.com");
            assertEquals("jdoe@example.com", dto.getEmail());
        }

        @Test
        @DisplayName("firstName getter/setter")
        void firstName() {
            TestUserDTO dto = createDto();
            dto.setFirstName("John");
            assertEquals("John", dto.getFirstName());
        }

        @Test
        @DisplayName("lastName getter/setter")
        void lastName() {
            TestUserDTO dto = createDto();
            dto.setLastName("Doe");
            assertEquals("Doe", dto.getLastName());
        }

        @Test
        @DisplayName("phoneNumber getter/setter")
        void phoneNumber() {
            TestUserDTO dto = createDto();
            dto.setPhoneNumber("+15551234567");
            assertEquals("+15551234567", dto.getPhoneNumber());
        }

        @Test
        @DisplayName("dateOfBirth getter/setter")
        void dateOfBirth() {
            TestUserDTO dto = createDto();
            LocalDate dob = LocalDate.of(2000, 6, 15);
            dto.setDateOfBirth(dob);
            assertEquals(dob, dto.getDateOfBirth());
        }
    }

    // ═══════════════ isMinor() ═══════════════

    @Nested
    @DisplayName("isMinor()")
    class IsMinor {

        @Test
        @DisplayName("returns false when dateOfBirth is null")
        void nullDateOfBirth() {
            TestUserDTO dto = createDto();
            dto.setDateOfBirth(null);
            assertFalse(dto.isMinor());
        }

        @Test
        @DisplayName("returns true for a person under 18")
        void underEighteen() {
            TestUserDTO dto = createDto();
            // 10 years old relative to today
            dto.setDateOfBirth(LocalDate.now().minusYears(10));
            assertTrue(dto.isMinor());
        }

        @Test
        @DisplayName("returns false for exactly 18 years old today")
        void exactlyEighteen() {
            TestUserDTO dto = createDto();
            dto.setDateOfBirth(LocalDate.now().minusYears(18));
            assertFalse(dto.isMinor());
        }

        @Test
        @DisplayName("returns false for a person over 18")
        void overEighteen() {
            TestUserDTO dto = createDto();
            dto.setDateOfBirth(LocalDate.now().minusYears(30));
            assertFalse(dto.isMinor());
        }

        @Test
        @DisplayName("returns true for 17 years and 364 days")
        void almostEighteen() {
            TestUserDTO dto = createDto();
            dto.setDateOfBirth(LocalDate.now().minusYears(18).plusDays(1));
            assertTrue(dto.isMinor());
        }

        @Test
        @DisplayName("returns true for a newborn (today)")
        void newborn() {
            TestUserDTO dto = createDto();
            dto.setDateOfBirth(LocalDate.now());
            assertTrue(dto.isMinor());
        }

        @Test
        @DisplayName("returns true for one-day-old")
        void oneDayOld() {
            TestUserDTO dto = createDto();
            dto.setDateOfBirth(LocalDate.now().minusDays(1));
            assertTrue(dto.isMinor());
        }
    }
}
