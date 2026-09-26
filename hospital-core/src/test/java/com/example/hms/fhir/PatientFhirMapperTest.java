package com.example.hms.fhir;

import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PatientFhirMapperTest {

    private final PatientFhirMapper mapper = new PatientFhirMapper();

    @Test
    void mapsCoreDemographicsAndContacts() {
        UUID id = UUID.randomUUID();
        Patient src = Patient.builder()
            .firstName("Aïssa")
            .middleName("Marie")
            .lastName("Diallo")
            .dateOfBirth(LocalDate.of(1990, 5, 12))
            .gender("female")
            .phoneNumberPrimary("+221 77 123 45 67")
            .email("aissa.diallo@example.sn")
            .city("Dakar")
            .country("SN")
            .active(true)
            .build();
        src.setId(id);

        org.hl7.fhir.r4.model.Patient out = mapper.toFhir(src);

        assertThat(out.getId()).isEqualTo(id.toString());
        assertThat(out.getActive()).isTrue();
        assertThat(out.getNameFirstRep().getFamily()).isEqualTo("Diallo");
        assertThat(out.getNameFirstRep().getGiven()).extracting(Object::toString)
            .containsExactly("Aïssa", "Marie");
        assertThat(out.getGender()).isEqualTo(Enumerations.AdministrativeGender.FEMALE);
        assertThat(out.getBirthDate()).isNotNull();
        assertThat(out.getTelecom()).extracting(ContactPoint::getValue)
            .contains("+221 77 123 45 67", "aissa.diallo@example.sn");
        assertThat(out.getAddressFirstRep().getCity()).isEqualTo("Dakar");
        assertThat(out.getIdentifier()).anyMatch(i -> "urn:hms:patient:id".equals(i.getSystem()));
    }

    @Test
    void unknownGenderFallsBackToFhirUnknown() {
        Patient src = Patient.builder()
            .firstName("X")
            .lastName("Y")
            .dateOfBirth(LocalDate.of(2000, 1, 1))
            .gender("nonbinary")
            .phoneNumberPrimary("0")
            .email("x@y.com")
            .build();
        src.setId(UUID.randomUUID());

        assertThat(mapper.toFhir(src).getGender())
            .isEqualTo(Enumerations.AdministrativeGender.UNKNOWN);
    }

    @Test
    void aBoundHospitalSeesItsOwnMrnAndNoOtherHospitals() {
        UUID atA = UUID.randomUUID();
        UUID atB = UUID.randomUUID();
        Patient src = registeredAt(atA, "MRN-A", atB, "MRN-B");

        assertThat(mrnSystems(mapper.toFhir(src, atA)))
            .containsExactly("urn:hms:hospital:" + atA + ":mrn=MRN-A");
        // No hospital bound: no MRN at all, never every hospital's.
        assertThat(mrnSystems(mapper.toFhir(src, null))).isEmpty();
        // The unscoped form (global-view super-admin) keeps them all.
        assertThat(mrnSystems(mapper.toFhir(src))).containsExactlyInAnyOrder(
            "urn:hms:hospital:" + atA + ":mrn=MRN-A", "urn:hms:hospital:" + atB + ":mrn=MRN-B");
    }

    private static Patient registeredAt(UUID hospitalA, String mrnA, UUID hospitalB, String mrnB) {
        Patient src = Patient.builder().firstName("X").lastName("Y").build();
        src.setId(UUID.randomUUID());
        register(src, hospitalA, mrnA);
        register(src, hospitalB, mrnB);
        return src;
    }

    private static void register(Patient patient, UUID hospitalId, String mrn) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        // Entities compare by id: without one the set would keep a single registration.
        registration.setId(UUID.randomUUID());
        registration.setHospital(hospital);
        registration.setMrn(mrn);
        registration.setPatient(patient);
        patient.getHospitalRegistrations().add(registration);
    }

    private static List<String> mrnSystems(org.hl7.fhir.r4.model.Patient out) {
        return out.getIdentifier().stream()
            .filter(i -> i.getSystem() != null && i.getSystem().endsWith(":mrn"))
            .map(i -> i.getSystem() + "=" + i.getValue())
            .toList();
    }
}
