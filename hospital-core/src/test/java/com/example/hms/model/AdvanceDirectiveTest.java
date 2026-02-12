package com.example.hms.model;

import com.example.hms.enums.AdvanceDirectiveStatus;
import com.example.hms.enums.AdvanceDirectiveType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdvanceDirectiveTest {

    private final Patient patient = new Patient();
    private final Hospital hospital = new Hospital();

    // ───────────────── builder ─────────────────

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("builds with all fields populated")
        void builderAllFields() {
            LocalDate eff = LocalDate.of(2026, 1, 1);
            LocalDate exp = LocalDate.of(2027, 1, 1);
            LocalDateTime reviewed = LocalDateTime.of(2026, 6, 15, 10, 0);

            AdvanceDirective ad = AdvanceDirective.builder()
                .patient(patient)
                .hospital(hospital)
                .directiveType(AdvanceDirectiveType.LIVING_WILL)
                .status(AdvanceDirectiveStatus.ACTIVE)
                .description("Full code except intubation")
                .effectiveDate(eff)
                .expirationDate(exp)
                .witnessName("Alice Witness")
                .physicianName("Dr. Smith")
                .documentLocation("/docs/ad-001.pdf")
                .sourceSystem("EHR-v3")
                .lastReviewedAt(reviewed)
                .build();

            assertAll(
                () -> assertSame(patient, ad.getPatient()),
                () -> assertSame(hospital, ad.getHospital()),
                () -> assertEquals(AdvanceDirectiveType.LIVING_WILL, ad.getDirectiveType()),
                () -> assertEquals(AdvanceDirectiveStatus.ACTIVE, ad.getStatus()),
                () -> assertEquals("Full code except intubation", ad.getDescription()),
                () -> assertEquals(eff, ad.getEffectiveDate()),
                () -> assertEquals(exp, ad.getExpirationDate()),
                () -> assertEquals("Alice Witness", ad.getWitnessName()),
                () -> assertEquals("Dr. Smith", ad.getPhysicianName()),
                () -> assertEquals("/docs/ad-001.pdf", ad.getDocumentLocation()),
                () -> assertEquals("EHR-v3", ad.getSourceSystem()),
                () -> assertEquals(reviewed, ad.getLastReviewedAt())
            );
        }

        @Test
        @DisplayName("defaults status to ACTIVE via @Builder.Default")
        void builderDefaultStatus() {
            AdvanceDirective ad = AdvanceDirective.builder()
                .patient(patient)
                .hospital(hospital)
                .directiveType(AdvanceDirectiveType.DO_NOT_RESUSCITATE)
                .build();

            assertEquals(AdvanceDirectiveStatus.ACTIVE, ad.getStatus());
        }
    }

    // ───────────────── constructors ─────────────────

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("no-args constructor creates empty instance")
        void noArgs() {
            AdvanceDirective ad = new AdvanceDirective();
            assertNull(ad.getPatient());
            assertNull(ad.getDirectiveType());
        }

        @Test
        @DisplayName("all-args constructor sets every field")
        void allArgs() {
            LocalDate eff = LocalDate.of(2026, 3, 1);
            LocalDate exp = LocalDate.of(2027, 3, 1);
            LocalDateTime reviewed = LocalDateTime.now();

            AdvanceDirective ad = new AdvanceDirective(
                patient, hospital,
                AdvanceDirectiveType.DURABLE_POWER_OF_ATTORNEY,
                AdvanceDirectiveStatus.REVOKED,
                "Revoked by patient",
                eff, exp,
                "Bob Witness", "Dr. Jones",
                "/docs/ad-002.pdf", "Legacy-EHR",
                reviewed
            );

            assertAll(
                () -> assertSame(patient, ad.getPatient()),
                () -> assertEquals(AdvanceDirectiveStatus.REVOKED, ad.getStatus()),
                () -> assertEquals(eff, ad.getEffectiveDate()),
                () -> assertEquals("Bob Witness", ad.getWitnessName())
            );
        }
    }

    // ───────────────── getters / setters ─────────────────

    @Nested
    @DisplayName("Getters & Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("all setters mutate corresponding getters")
        void settersAndGetters() {
            AdvanceDirective ad = new AdvanceDirective();
            LocalDate eff = LocalDate.of(2026, 5, 1);
            LocalDate exp = LocalDate.of(2028, 5, 1);
            LocalDateTime reviewed = LocalDateTime.of(2026, 7, 1, 8, 30);

            ad.setPatient(patient);
            ad.setHospital(hospital);
            ad.setDirectiveType(AdvanceDirectiveType.PHYSICIAN_ORDERS_FOR_LIFE_SUSTAINING_TREATMENT);
            ad.setStatus(AdvanceDirectiveStatus.EXPIRED);
            ad.setDescription("POLST form");
            ad.setEffectiveDate(eff);
            ad.setExpirationDate(exp);
            ad.setWitnessName("Carol Witness");
            ad.setPhysicianName("Dr. Lee");
            ad.setDocumentLocation("/docs/polst.pdf");
            ad.setSourceSystem("InteropHub");
            ad.setLastReviewedAt(reviewed);

            assertAll(
                () -> assertSame(patient, ad.getPatient()),
                () -> assertSame(hospital, ad.getHospital()),
                () -> assertEquals(AdvanceDirectiveType.PHYSICIAN_ORDERS_FOR_LIFE_SUSTAINING_TREATMENT, ad.getDirectiveType()),
                () -> assertEquals(AdvanceDirectiveStatus.EXPIRED, ad.getStatus()),
                () -> assertEquals("POLST form", ad.getDescription()),
                () -> assertEquals(eff, ad.getEffectiveDate()),
                () -> assertEquals(exp, ad.getExpirationDate()),
                () -> assertEquals("Carol Witness", ad.getWitnessName()),
                () -> assertEquals("Dr. Lee", ad.getPhysicianName()),
                () -> assertEquals("/docs/polst.pdf", ad.getDocumentLocation()),
                () -> assertEquals("InteropHub", ad.getSourceSystem()),
                () -> assertEquals(reviewed, ad.getLastReviewedAt())
            );
        }
    }

    // ───────────────── ensureDefaults (lifecycle callback) ─────────────────

    @Nested
    @DisplayName("ensureDefaults — @PrePersist / @PreUpdate")
    class EnsureDefaultsTests {

        @Test
        @DisplayName("sets status to ACTIVE when null")
        void ensureDefaults_setsActiveWhenNull() {
            AdvanceDirective ad = new AdvanceDirective();
            ad.setStatus(null);

            ad.ensureDefaults();

            assertEquals(AdvanceDirectiveStatus.ACTIVE, ad.getStatus());
        }

        @Test
        @DisplayName("preserves existing status when non-null")
        void ensureDefaults_preservesExistingStatus() {
            AdvanceDirective ad = new AdvanceDirective();
            ad.setStatus(AdvanceDirectiveStatus.REVOKED);

            ad.ensureDefaults();

            assertEquals(AdvanceDirectiveStatus.REVOKED, ad.getStatus());
        }
    }

    // ───────────────── equals / hashCode ─────────────────

    @Nested
    @DisplayName("equals & hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("same id → equal")
        void sameId_equal() {
            UUID id = UUID.randomUUID();
            AdvanceDirective a = new AdvanceDirective();
            a.setId(id);
            AdvanceDirective b = new AdvanceDirective();
            b.setId(id);

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different id → not equal")
        void differentId_notEqual() {
            AdvanceDirective a = new AdvanceDirective();
            a.setId(UUID.randomUUID());
            AdvanceDirective b = new AdvanceDirective();
            b.setId(UUID.randomUUID());

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("null id fields → equal by convention")
        void nullIds_equal() {
            AdvanceDirective a = new AdvanceDirective();
            AdvanceDirective b = new AdvanceDirective();

            assertEquals(a, b);
        }

        @Test
        @DisplayName("not equal to null")
        void notEqualToNull() {
            AdvanceDirective ad = new AdvanceDirective();
            assertNotEquals(null, ad);
        }

        @Test
        @DisplayName("not equal to different type")
        void notEqualToDifferentType() {
            AdvanceDirective ad = new AdvanceDirective();
            assertNotEquals("a string", ad);
        }
    }
}
