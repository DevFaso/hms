package com.example.hms.service.emar;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.pharmacy.PrescriptionControlledResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D922 (decision 2026-10-28): the witness of a controlled dose at administration. */
class ControlledAdministrationWitnessTest {

    private final PrescriptionControlledResolver resolver = mock(PrescriptionControlledResolver.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ControlledAdministrationWitness witness =
        new ControlledAdministrationWitness(resolver, assignments, users);

    private final UUID nurse = UUID.randomUUID();
    private final UUID colleague = UUID.randomUUID();
    private final Hospital hospital = new Hospital();
    private final Prescription morphine = new Prescription();

    @BeforeEach
    void setUp() {
        hospital.setId(UUID.randomUUID());
        morphine.setHospital(hospital);
        morphine.setMedicationName("Morphine");
    }

    @Test
    void aControlledDoseNeedsAWitness() {
        when(resolver.isControlled(morphine)).thenReturn(true);

        assertThatThrownBy(() -> witness.resolve(morphine, MedicationAdministrationStatus.GIVEN, null, nurse))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void theNurseGivingItCannotWitnessIt() {
        when(resolver.isControlled(morphine)).thenReturn(true);

        assertThatThrownBy(() -> witness.resolve(morphine, MedicationAdministrationStatus.GIVEN, nurse, nurse))
            .isInstanceOf(BusinessException.class);
        verify(assignments, never()).existsActiveWitness(any(), any(), any());
    }

    @Test
    void theWitnessMustBeAnActiveClinicianOfThePrescriptionsHospital() {
        when(resolver.isControlled(morphine)).thenReturn(true);
        when(assignments.existsActiveWitness(eq(colleague), eq(hospital.getId()), any())).thenReturn(false);

        assertThatThrownBy(() -> witness.resolve(morphine, MedicationAdministrationStatus.GIVEN, colleague, nurse))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void aColleagueOfTheHospitalWitnessesIt() {
        User other = new User();
        other.setId(colleague);
        when(resolver.isControlled(morphine)).thenReturn(true);
        when(assignments.existsActiveWitness(colleague, hospital.getId(),
            ControlledAdministrationWitness.WITNESS_ROLE_CODES)).thenReturn(true);
        when(users.findById(colleague)).thenReturn(Optional.of(other));

        assertThat(witness.resolve(morphine, MedicationAdministrationStatus.GIVEN, colleague, nurse)).isSameAs(other);
    }

    @Test
    void noWitnessForAnOrdinaryDrugOrADoseNotGiven() {
        when(resolver.isControlled(morphine)).thenReturn(false);
        assertThat(witness.resolve(morphine, MedicationAdministrationStatus.GIVEN, colleague, nurse)).isNull();

        when(resolver.isControlled(morphine)).thenReturn(true);
        assertThat(witness.resolve(morphine, MedicationAdministrationStatus.HELD, null, nurse)).isNull();
        assertThat(witness.resolve(morphine, MedicationAdministrationStatus.REFUSED, null, nurse)).isNull();
    }
}
