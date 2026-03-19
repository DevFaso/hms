package com.example.hms.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the {@link PatientDiagnosis} entity — constructor defaults,
 * builder, and field accessors.
 */
class PatientDiagnosisTest {

    // ═══════════════ NoArgsConstructor ═══════════════

    @Nested
    @DisplayName("NoArgsConstructor")
    class NoArgsCtor {

        @Test
        @DisplayName("creates instance with ACTIVE default status and non-null diagnosedAt")
        void defaults() {
            PatientDiagnosis d = new PatientDiagnosis();
            assertAll(
                () -> assertNull(d.getId()),
                () -> assertNull(d.getPatient()),
                () -> assertNull(d.getDiagnosedBy()),
                () -> assertNull(d.getIcdCode()),
                () -> assertEquals("ACTIVE", d.getStatus()),
                () -> assertNotNull(d.getDiagnosedAt())
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
            Patient patient = new Patient();
            Staff diagnosedBy = new Staff();
            OffsetDateTime diagnosedAt = OffsetDateTime.of(2026, 3, 14, 10, 0, 0, 0, ZoneOffset.UTC);

            PatientDiagnosis d = PatientDiagnosis.builder()
                    .patient(patient)
                    .diagnosedBy(diagnosedBy)
                    .icdCode("J18.9")
                    .description("Pneumonia, unspecified")
                    .status("RESOLVED")
                    .diagnosedAt(diagnosedAt)
                    .build();

            assertAll(
                () -> assertEquals(patient, d.getPatient()),
                () -> assertEquals(diagnosedBy, d.getDiagnosedBy()),
                () -> assertEquals("J18.9", d.getIcdCode()),
                () -> assertEquals("Pneumonia, unspecified", d.getDescription()),
                () -> assertEquals("RESOLVED", d.getStatus()),
                () -> assertEquals(diagnosedAt, d.getDiagnosedAt())
            );
        }

        @Test
        @DisplayName("builder defaults: status=ACTIVE, diagnosedAt non-null")
        void builderDefaults() {
            PatientDiagnosis d = PatientDiagnosis.builder()
                    .description("Anemia")
                    .build();

            assertAll(
                () -> assertEquals("ACTIVE", d.getStatus()),
                () -> assertNotNull(d.getDiagnosedAt())
            );
        }

        @Test
        @DisplayName("icdCode is optional — can be null")
        void icdCodeIsOptional() {
            PatientDiagnosis d = PatientDiagnosis.builder()
                    .description("Unspecified condition")
                    .build();

            assertNull(d.getIcdCode());
        }
    }

    // ═══════════════ Setters / Getters ═══════════════

    @Nested
    @DisplayName("Setters and Getters")
    class SettersGetters {

        @Test
        @DisplayName("setter updates are reflected by getters")
        void settersWork() {
            PatientDiagnosis d = new PatientDiagnosis();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            d.setIcdCode("I10");
            d.setDescription("Hypertension");
            d.setStatus("CHRONIC");
            d.setDiagnosedAt(now);

            assertAll(
                () -> assertEquals("I10", d.getIcdCode()),
                () -> assertEquals("Hypertension", d.getDescription()),
                () -> assertEquals("CHRONIC", d.getStatus()),
                () -> assertEquals(now, d.getDiagnosedAt())
            );
        }
    }
}
