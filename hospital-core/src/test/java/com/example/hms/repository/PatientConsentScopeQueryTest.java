package com.example.hms.repository;

import com.example.hms.model.*;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.assertThat;

@TenantScopedDataJpaTest
class PatientConsentScopeQueryTest {
    @Autowired PatientConsentRepository consents;
    @Autowired HospitalRepository hospitals;
    @Autowired PatientRepository patients;
    @Autowired UserRepository users;
    @Autowired PatientHospitalRegistrationRepository registrations;

    @AfterEach void clearScope() { HospitalContextHolder.clear(); }

    @Test
    void hospitalPredicateFiltersContentAndTotalBeforePagination() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        String tag = java.util.UUID.randomUUID().toString().substring(0, 8);
        Hospital a = hospitals.save(Hospital.builder().name("Synthetic A").code("CA-" + tag).build());
        Hospital b = hospitals.save(Hospital.builder().name("Synthetic B").code("CB-" + tag).build());
        Hospital c = hospitals.save(Hospital.builder().name("Synthetic C").code("CC-" + tag).build());
        User user = users.save(User.builder().username("consent-" + tag).email(tag + "@example.invalid")
            .passwordHash("synthetic-no-login").firstName("Synthetic").lastName("Patient").isActive(true).build());
        Patient patient = patients.save(Patient.builder().firstName("Synthetic").lastName("Patient")
            .dateOfBirth(java.time.LocalDate.of(1990, 1, 1)).phoneNumberPrimary("+22670" + String.format("%06d", Math.floorMod(tag.hashCode(), 1_000_000)))
            .user(user).hospitalId(a.getId()).build());
        for (Hospital hospital : java.util.List.of(a, b, c)) {
            PatientHospitalRegistration registration = registrations.save(PatientHospitalRegistration.builder()
                .patient(patient).hospital(hospital).mrn("CONSENT-" + hospital.getCode())
                .registrationDate(java.time.LocalDate.now()).active(true).build());
            patient.getHospitalRegistrations().add(registration);
        }
        PatientConsent outgoing = consents.save(PatientConsent.builder().patient(patient).fromHospital(a).toHospital(b).build());
        PatientConsent incoming = consents.save(PatientConsent.builder().patient(patient).fromHospital(c).toHospital(a).build());
        PatientConsent foreign = consents.save(PatientConsent.builder().patient(patient).fromHospital(b).toHospital(c).build());
        consents.flush();
        var first = consents.findVisibleInHospital(a.getId(), null, PageRequest.of(0, 1));
        var second = consents.findVisibleInHospital(a.getId(), null, PageRequest.of(1, 1));
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(second.getTotalElements()).isEqualTo(2);
        assertThat(java.util.stream.Stream.concat(first.stream(), second.stream()).map(PatientConsent::getId).toList())
            .containsExactlyInAnyOrder(outgoing.getId(), incoming.getId()).doesNotContain(foreign.getId());
        assertThat(consents.findVisibleInHospital(a.getId(), patient.getId(), PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(consents.findVisibleInHospital(a.getId(), java.util.UUID.randomUUID(), PageRequest.of(0, 10))).isEmpty();
    }
}
