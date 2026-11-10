package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class MedicationCatalogAllergyContextTest {

    @Autowired private MedicationCatalogItemRepository repository;
    @Autowired private TestEntityManager entityManager;

    @Test
    void linkedLookupIncludesInactiveItemsButKeepsHospitalAndRequestedIds() {
        Hospital here = hospital();
        Hospital away = hospital();
        MedicationCatalogItem linked = item(here);
        MedicationCatalogItem unrelated = item(here);
        MedicationCatalogItem foreign = item(away);
        MedicationCatalogItem national = item(null);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByHospital_IdAndIdIn(here.getId(),
            List.of(linked.getId(), foreign.getId(), national.getId())))
            .extracting(MedicationCatalogItem::getId).containsExactly(linked.getId());
        assertThat(repository.findByHospital_IdAndIdIn(here.getId(), List.of(unrelated.getId())))
            .extracting(MedicationCatalogItem::isActive).containsExactly(false);
    }

    private Hospital hospital() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return entityManager.persist(Hospital.builder().name("Catalogue " + suffix)
            .code("CAT" + suffix).address("1 Test St").phoneNumber("555" + suffix).build());
    }

    private MedicationCatalogItem item(Hospital hospital) {
        return entityManager.persist(MedicationCatalogItem.builder().hospital(hospital)
            .nameFr("Local product").genericName("Amoxicillin").active(false).build());
    }
}
