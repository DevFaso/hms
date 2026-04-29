package com.example.hms.fhir;

import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.model.Patient;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
}
