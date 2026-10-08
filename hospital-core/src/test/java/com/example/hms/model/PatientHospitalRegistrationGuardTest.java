package com.example.hms.model;

import com.example.hms.enums.FacilityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The entity backstop of provider plan AC-10: a registration is the treatment
 * relationship (E9 #58), so one at a pharmacy or laboratory would open the
 * chart to it. The {@code @PrePersist/@PreUpdate} hook refuses it.
 */
class PatientHospitalRegistrationGuardTest {

    private static PatientHospitalRegistration at(FacilityType type) {
        Hospital hospital = Hospital.builder().name("Facility").build();
        hospital.setFacilityType(type);
        return new PatientHospitalRegistration(new Patient(), hospital);
    }

    @Test
    @DisplayName("saving a registration at a pharmacy or a laboratory throws")
    void providerRegistrationIsRefused() {
        for (FacilityType provider : new FacilityType[] {FacilityType.PHARMACY, FacilityType.LABORATORY}) {
            PatientHospitalRegistration registration = at(provider);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(registration, "normalize"))
                .as(provider.name())
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("a registration at a hospital, or one built before V180 with no type, saves as before")
    void hospitalRegistrationIsUnchanged() {
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(at(FacilityType.HOSPITAL), "normalize"))
            .doesNotThrowAnyException();
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(at(null), "normalize"))
            .doesNotThrowAnyException();
    }
}
