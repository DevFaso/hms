package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D73 (regression of D30): the service filed a definition under its hospital,
 * but the entity's lifecycle hook nulled hospital_id on every insert and
 * update, so every definition reached the database as a global row. This goes
 * through Hibernate, where the hook runs, which the mocked service tests do not.
 */
@DataJpaTest
@ActiveProfiles("test")
class LabTestDefinitionHospitalPersistenceTest {

    @Autowired
    private LabTestDefinitionRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void aHospitalsDefinitionKeepsItsHospitalOnInsertAndUpdate() {
        Hospital hospital = entityManager.persist(Hospital.builder()
            .name("Lab Scope Hospital")
            .code("LSH1")
            .city("Ouagadougou")
            .state("Kadiogo")
            .country("Burkina Faso")
            .address("1 Main St")
            .email("lsh1@facility.test")
            .active(true)
            .build());

        LabTestDefinition definition = LabTestDefinition.builder()
            .testCode("glua")
            .name("Glucose A")
            .active(true)
            .build();
        definition.setHospital(hospital);
        LabTestDefinition saved = entityManager.persistFlushFind(definition);

        assertThat(saved.getHospital()).isNotNull();
        assertThat(saved.getHospital().getId()).isEqualTo(hospital.getId());

        saved.setName("Glucose A (fasting)");
        entityManager.flush();
        entityManager.clear();

        LabTestDefinition reread = repository.findById(saved.getId()).orElseThrow();
        assertThat(reread.getHospital()).isNotNull();
        assertThat(reread.getHospital().getId()).isEqualTo(hospital.getId());
        assertThat(repository.findByHospital_IdAndActiveTrue(hospital.getId()))
            .extracting(LabTestDefinition::getTestCode).containsExactly("GLUA");
    }
}
