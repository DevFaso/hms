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
 * re-activating one there and moving ANY row there, and lets a legacy row be
 * deactivated or discharged where it was loaded. An ordinary update never
 * loads the hospital, and nothing but the entity's own callbacks can set
 * what it was loaded with.
 */
class PatientHospitalRegistrationGuardTest {

    @Test
    @DisplayName("an update that neither moves nor re-activates the row never reads the facility type (no N+1)")
    void ordinaryUpdateDoesNotLoadTheHospital() {
        Hospital hospital = org.mockito.Mockito.spy(facility(FacilityType.HOSPITAL));
        PatientHospitalRegistration registration = new PatientHospitalRegistration(new Patient(), hospital);
        ReflectionTestUtils.invokeMethod(registration, "rememberLoadedHospital");
        org.mockito.Mockito.clearInvocations(hospital);
        registration.setCurrentRoom("12B");

        ReflectionTestUtils.invokeMethod(registration, "normalizeOnUpdate");

        org.mockito.Mockito.verify(hospital, org.mockito.Mockito.never()).isProvider();
        org.mockito.Mockito.verify(hospital, org.mockito.Mockito.never()).getFacilityType();
    }

    @Test
    @DisplayName("what the row was loaded with is not settable: no accessor, no builder property, no constructor argument")
    void loadedStateIsNotSettable() {
        for (java.lang.reflect.Method method : PatientHospitalRegistration.class.getMethods()) {
            org.assertj.core.api.Assertions.assertThat(method.getName().toLowerCase())
                .as("public method %s", method.getName()).doesNotContain("loaded");
        }
        for (java.lang.reflect.Method method : PatientHospitalRegistration.builder().getClass().getMethods()) {
            org.assertj.core.api.Assertions.assertThat(method.getName().toLowerCase())
                .as("builder method %s", method.getName()).doesNotContain("loaded");
        }
        for (java.lang.reflect.Constructor<?> constructor : PatientHospitalRegistration.class.getConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                org.assertj.core.api.Assertions.assertThat(parameter.getSimpleName())
                    .as("constructor parameter").isNotEqualTo("LoadedState");
            }
        }
        org.assertj.core.api.Assertions.assertThat(at(FacilityType.HOSPITAL).toString()).doesNotContain("loaded");
    }

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
    @DisplayName("re-activating a loaded registration at a provider throws; deactivating or discharging it passes")
    void updateMayOnlyDeactivateAtAProvider() {
        PatientHospitalRegistration inactive = at(FacilityType.PHARMACY);
        inactive.setActive(false);
        ReflectionTestUtils.invokeMethod(inactive, "rememberLoadedHospital");
        inactive.setActive(true);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(inactive, "normalizeOnUpdate"))
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
