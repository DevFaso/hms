package com.example.hms.model;

import com.example.hms.BaseIT;
import com.example.hms.enums.FacilityType;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registration backstop against the real persistence lifecycle (provider
 * plan AC-10): what reaches the database is checked, not only what the entity
 * held when {@code persist} was called. A registration persisted at a
 * hospital and switched to a pharmacy before the flush is refused; so is a
 * loaded one moved to a pharmacy. Nothing is written in either case.
 */
class PatientHospitalRegistrationProviderFlushIT extends BaseIT {

    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void unscoped() {
        tx = new TransactionTemplate(transactionManager);
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    /** The backstop's refusal, thrown as is or wrapped by the transaction. */
    private static void refusedByTheBackstop(Throwable thrown) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(cause).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("registration.hospital.notClinical");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Hospital facility(FacilityType type) {
        String n = suffix();
        Hospital hospital = Hospital.builder()
            .name("Flush " + type + " " + n)
            .code("FL" + n.toUpperCase())
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .email("fl" + n + "@facility.test")
            .active(true)
            .build();
        hospital.setFacilityType(type);
        entityManager.persist(hospital);
        return hospital;
    }

    private Patient patient(Hospital at) {
        String n = suffix();
        User user = User.builder()
            .username("flush" + n)
            .passwordHash("hashed-password")
            .email("flush" + n + "@example.test")
            .firstName("Flush")
            .lastName("Patient")
            .phoneNumber("+2267" + Math.abs(n.hashCode() % 10_000_000))
            .isActive(true)
            .build();
        entityManager.persist(user);
        Patient patient = Patient.builder()
            .firstName("Aminata")
            .lastName("Diallo")
            .dateOfBirth(LocalDate.of(1992, 3, 10))
            .gender("F")
            .address("Patient address")
            .city("Bobo-Dioulasso")
            .country("Burkina Faso")
            .phoneNumberPrimary("+2267" + Math.abs((n + "p").hashCode() % 10_000_000))
            .email("aminata" + n + "@patient.test")
            .emergencyContactName("Issa Diallo")
            .emergencyContactPhone("+2267" + Math.abs((n + "e").hashCode() % 10_000_000))
            .hospitalId(at.getId())
            .user(user)
            .build();
        entityManager.persist(patient);
        return patient;
    }

    private PatientHospitalRegistration registrationAt(Patient patient, Hospital hospital) {
        return PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(hospital)
            .mrn("MRN-" + suffix())
            .registrationDate(LocalDate.now())
            .active(true)
            .build();
    }

    @Test
    @DisplayName("persist at a hospital, switch to a pharmacy, flush: refused, nothing written")
    void switchBetweenPersistAndFlushIsRefused() {
        AtomicReference<UUID> registrationId = new AtomicReference<>();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Hospital hospital = facility(FacilityType.HOSPITAL);
            Hospital pharmacy = facility(FacilityType.PHARMACY);
            PatientHospitalRegistration registration = registrationAt(patient(hospital), hospital);
            entityManager.persist(registration);
            registrationId.set(registration.getId());
            registration.setHospital(pharmacy);
            entityManager.flush();
        })).satisfies(PatientHospitalRegistrationProviderFlushIT::refusedByTheBackstop);

        PatientHospitalRegistration written = tx.execute(status -> registrationId.get() == null ? null
            : entityManager.find(PatientHospitalRegistration.class, registrationId.get()));
        assertThat(written).isNull();
    }

    @Test
    @DisplayName("a loaded registration moved to a laboratory is refused; at a hospital it saves")
    void loadedRowMovedToAProviderIsRefused() {
        UUID registrationId = tx.execute(status -> {
            Hospital hospital = facility(FacilityType.HOSPITAL);
            PatientHospitalRegistration registration = registrationAt(patient(hospital), hospital);
            entityManager.persist(registration);
            return registration.getId();
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Hospital lab = facility(FacilityType.LABORATORY);
            PatientHospitalRegistration loaded = entityManager.find(PatientHospitalRegistration.class, registrationId);
            loaded.setActive(false);
            loaded.setHospital(lab);
            entityManager.flush();
        })).satisfies(PatientHospitalRegistrationProviderFlushIT::refusedByTheBackstop);

        tx.executeWithoutResult(status -> {
            PatientHospitalRegistration loaded = entityManager.find(PatientHospitalRegistration.class, registrationId);
            loaded.setCurrentRoom("12B");
            entityManager.flush();
        });
    }
}
