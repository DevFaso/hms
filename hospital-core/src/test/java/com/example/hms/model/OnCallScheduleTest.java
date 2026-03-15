package com.example.hms.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the {@link OnCallSchedule} entity — constructor defaults,
 * builder, and field accessors.
 */
class OnCallScheduleTest {

    // ═══════════════ NoArgsConstructor ═══════════════

    @Nested
    @DisplayName("NoArgsConstructor")
    class NoArgsCtor {

        @Test
        @DisplayName("creates instance with all fields null")
        void defaults() {
            OnCallSchedule s = new OnCallSchedule();
            assertAll(
                () -> assertNull(s.getId()),
                () -> assertNull(s.getStaff()),
                () -> assertNull(s.getDepartment()),
                () -> assertNull(s.getStartTime()),
                () -> assertNull(s.getEndTime()),
                () -> assertNull(s.getNotes())
            );
        }
    }

    // ═══════════════ Builder ═══════════════

    @Nested
    @DisplayName("Builder")
    class BuilderTest {

        @Test
        @DisplayName("builds with all fields set correctly")
        void fullBuild() {
            Staff staff = new Staff();
            Department department = new Department();
            LocalDateTime start = LocalDateTime.of(2026, 3, 14, 20, 0);
            LocalDateTime end   = LocalDateTime.of(2026, 3, 15, 8, 0);

            OnCallSchedule s = OnCallSchedule.builder()
                    .staff(staff)
                    .department(department)
                    .startTime(start)
                    .endTime(end)
                    .notes("Night shift on-call")
                    .build();

            assertAll(
                () -> assertEquals(staff, s.getStaff()),
                () -> assertEquals(department, s.getDepartment()),
                () -> assertEquals(start, s.getStartTime()),
                () -> assertEquals(end, s.getEndTime()),
                () -> assertEquals("Night shift on-call", s.getNotes())
            );
        }

        @Test
        @DisplayName("department and notes are optional — can be null")
        void optionalFields() {
            Staff staff = new Staff();
            LocalDateTime start = LocalDateTime.of(2026, 3, 14, 8, 0);
            LocalDateTime end   = LocalDateTime.of(2026, 3, 14, 16, 0);

            OnCallSchedule s = OnCallSchedule.builder()
                    .staff(staff)
                    .startTime(start)
                    .endTime(end)
                    .build();

            assertAll(
                () -> assertEquals(staff, s.getStaff()),
                () -> assertNull(s.getDepartment()),
                () -> assertNull(s.getNotes())
            );
        }
    }

    // ═══════════════ Setters / Getters ═══════════════

    @Nested
    @DisplayName("Setters and Getters")
    class SettersGetters {

        @Test
        @DisplayName("setter updates are reflected by getters")
        void settersWork() {
            OnCallSchedule s = new OnCallSchedule();
            LocalDateTime start = LocalDateTime.of(2026, 3, 14, 20, 0);
            LocalDateTime end   = LocalDateTime.of(2026, 3, 15, 8, 0);

            s.setStartTime(start);
            s.setEndTime(end);
            s.setNotes("Emergency on-call");

            assertAll(
                () -> assertEquals(start, s.getStartTime()),
                () -> assertEquals(end, s.getEndTime()),
                () -> assertEquals("Emergency on-call", s.getNotes())
            );
        }
    }
}
