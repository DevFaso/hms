package com.example.hms.model;

import com.example.hms.enums.RefillStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the {@link RefillRequest} entity — constructor defaults,
 * builder, and field accessors.
 */
class RefillRequestTest {

    // ═══════════════ NoArgsConstructor ═══════════════

    @Nested
    @DisplayName("NoArgsConstructor")
    class NoArgsCtor {

        @Test
        @DisplayName("creates instance with REQUESTED default status and null optional fields")
        void defaults() {
            RefillRequest r = new RefillRequest();
            assertAll(
                () -> assertNull(r.getId()),
                () -> assertNull(r.getPatient()),
                () -> assertNull(r.getPrescription()),
                () -> assertEquals(RefillStatus.REQUESTED, r.getStatus()),
                () -> assertNull(r.getPreferredPharmacy()),
                () -> assertNull(r.getPatientNotes()),
                () -> assertNull(r.getProviderNotes())
            );
        }
    }

    // ═══════════════ Builder ═══════════════

    @Nested
    @DisplayName("Builder")
    class BuilderTest {

        @Test
        @DisplayName("builds a complete RefillRequest with all fields")
        void fullBuild() {
            Patient patient = new Patient();
            patient.setId(UUID.randomUUID());

            Prescription prescription = new Prescription();
            prescription.setId(UUID.randomUUID());

            RefillRequest r = RefillRequest.builder()
                    .patient(patient)
                    .prescription(prescription)
                    .status(RefillStatus.APPROVED)
                    .preferredPharmacy("CVS Pharmacy")
                    .patientNotes("Running low")
                    .providerNotes("Approved for 90-day supply")
                    .build();

            assertAll(
                () -> assertEquals(patient, r.getPatient()),
                () -> assertEquals(prescription, r.getPrescription()),
                () -> assertEquals(RefillStatus.APPROVED, r.getStatus()),
                () -> assertEquals("CVS Pharmacy", r.getPreferredPharmacy()),
                () -> assertEquals("Running low", r.getPatientNotes()),
                () -> assertEquals("Approved for 90-day supply", r.getProviderNotes())
            );
        }

        @Test
        @DisplayName("builder default produces REQUESTED status")
        void defaultStatus() {
            RefillRequest r = RefillRequest.builder().build();
            assertEquals(RefillStatus.REQUESTED, r.getStatus());
        }

        @Test
        @DisplayName("builder can override default status")
        void overrideStatus() {
            RefillRequest r = RefillRequest.builder()
                    .status(RefillStatus.DENIED)
                    .build();
            assertEquals(RefillStatus.DENIED, r.getStatus());
        }
    }

    // ═══════════════ Setter / Getter ═══════════════

    @Nested
    @DisplayName("Setters and Getters")
    class SettersGetters {

        @Test
        @DisplayName("setter updates are reflected by getters")
        void setAndGet() {
            RefillRequest r = new RefillRequest();
            Patient patient = new Patient();
            patient.setId(UUID.randomUUID());
            Prescription prescription = new Prescription();
            prescription.setId(UUID.randomUUID());

            r.setPatient(patient);
            r.setPrescription(prescription);
            r.setStatus(RefillStatus.DISPENSED);
            r.setPreferredPharmacy("Walgreens");
            r.setPatientNotes("Urgent");
            r.setProviderNotes("Dispensed at pharmacy");

            assertAll(
                () -> assertEquals(patient, r.getPatient()),
                () -> assertEquals(prescription, r.getPrescription()),
                () -> assertEquals(RefillStatus.DISPENSED, r.getStatus()),
                () -> assertEquals("Walgreens", r.getPreferredPharmacy()),
                () -> assertEquals("Urgent", r.getPatientNotes()),
                () -> assertEquals("Dispensed at pharmacy", r.getProviderNotes())
            );
        }
    }

    // ═══════════════ RefillStatus Enum ═══════════════

    @Nested
    @DisplayName("RefillStatus enum")
    class RefillStatusEnum {

        @Test
        @DisplayName("has all expected values")
        void allValues() {
            RefillStatus[] values = RefillStatus.values();
            assertEquals(5, values.length);
            assertAll(
                () -> assertNotNull(RefillStatus.valueOf("REQUESTED")),
                () -> assertNotNull(RefillStatus.valueOf("APPROVED")),
                () -> assertNotNull(RefillStatus.valueOf("DENIED")),
                () -> assertNotNull(RefillStatus.valueOf("DISPENSED")),
                () -> assertNotNull(RefillStatus.valueOf("CANCELLED"))
            );
        }
    }

    // ═══════════════ toString ═══════════════

    @Nested
    @DisplayName("toString")
    class ToStringTest {

        @Test
        @DisplayName("toString excludes lazy associations (patient, prescription)")
        void toStringExcludesLazy() {
            RefillRequest r = RefillRequest.builder()
                    .status(RefillStatus.REQUESTED)
                    .preferredPharmacy("CVS")
                    .build();
            String str = r.toString();
            assertAll(
                () -> assertNotNull(str),
                () -> assertEquals(-1, str.indexOf("patient=")),
                () -> assertEquals(-1, str.indexOf("prescription="))
            );
        }
    }
}
