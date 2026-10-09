package com.example.hms.model;

import com.example.hms.enums.FacilityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The entity backstop of provider plan AC-10: a registration is the treatment
 * relationship (E9 #58), so one at a pharmacy or laboratory would open the
 * chart to it. {@code @PrePersist} refuses one; {@code @PreUpdate} refuses
 * keeping or re-activating one there and moving ANY row there, and lets a
 * legacy row be deactivated or discharged where it was loaded.
 */
class PatientHospitalRegistrationGuardTest {

    private static Hospital facility(FacilityType type) {
        Hospital hospital = Hospital.builder().name("Facility").build();
        hospital.setId(UUID.randomUUID());
        hospital.setFacilityType(type);
        return hospital;
    }

    private static PatientHospitalRegistration at(FacilityType type) {
        return new PatientHospitalRegistration(new Patient(), facility(type));
    }

    /** A row as Hibernate hands it back: loaded where it is. */
    private static PatientHospitalRegistration loadedAt(FacilityType type) {
        PatientHospitalRegistration registration = at(type);
        ReflectionTestUtils.invokeMethod(registration, "rememberLoadedHospital");
        return registration;
    }

    @Test
    @DisplayName("saving a registration at a pharmacy or a laboratory throws")
    void providerRegistrationIsRefused() {
        for (FacilityType provider : new FacilityType[] {FacilityType.PHARMACY, FacilityType.LABORATORY}) {
            PatientHospitalRegistration registration = at(provider);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(registration, "normalizeOnPersist"))
                .as(provider.name())
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("an update that keeps a loaded registration active at a provider throws; deactivating or discharging it passes")
    void updateMayOnlyDeactivateAtAProvider() {
        PatientHospitalRegistration stillActive = loadedAt(FacilityType.PHARMACY);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(stillActive, "normalizeOnUpdate"))
            .isInstanceOf(IllegalStateException.class);

        PatientHospitalRegistration deactivated = loadedAt(FacilityType.PHARMACY);
        deactivated.setActive(false);
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(deactivated, "normalizeOnUpdate"))
            .doesNotThrowAnyException();

        PatientHospitalRegistration discharged = loadedAt(FacilityType.LABORATORY);
        discharged.markDischarged();
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(discharged, "normalizeOnUpdate"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an INACTIVE registration loaded at a hospital cannot be moved to a provider")
    void inactiveRowCannotBeMovedToAProvider() {
        PatientHospitalRegistration moved = loadedAt(FacilityType.HOSPITAL);
        moved.setActive(false);
        moved.setHospital(facility(FacilityType.PHARMACY));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(moved, "normalizeOnUpdate"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("moving a row between hospitals, active or not, saves as before")
    void movingBetweenHospitalsIsUnchanged() {
        PatientHospitalRegistration moved = loadedAt(FacilityType.HOSPITAL);
        moved.setActive(false);
        moved.setHospital(facility(FacilityType.HOSPITAL));

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(moved, "normalizeOnUpdate"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an inactive registration is still never created at a provider")
    void inactiveRegistrationIsStillNotCreated() {
        PatientHospitalRegistration inactive = at(FacilityType.PHARMACY);
        inactive.setActive(false);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(inactive, "normalizeOnPersist"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a registration at a hospital, or one built before V180 with no type, saves as before")
    void hospitalRegistrationIsUnchanged() {
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(at(FacilityType.HOSPITAL), "normalizeOnPersist"))
            .doesNotThrowAnyException();
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(at(null), "normalizeOnPersist"))
            .doesNotThrowAnyException();
    }
}
