package com.example.hms.repository;

import com.example.hms.enums.EducationCategory;
import com.example.hms.enums.EducationResourceType;
import com.example.hms.model.education.EducationResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class EducationResourceRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EducationResourceRepository educationResourceRepository;

    private UUID hospitalId;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
    }

    @Test
    void shouldFindActiveResourcesForHospitalOrderedByCreatedAt() {
        // The two setCreatedAt calls this test used to make were silently
        // discarded: BaseEntity's @PrePersist overwrites createdAt with now()
        // on every insert, so both rows landed microseconds apart instead of
        // two days. Whenever the two timestamps rounded to the same value the
        // ORDER BY had nothing to sort on and returned insertion order, which
        // is why this failed intermittently in a full suite run and never in
        // isolation.
        //
        // createdAt is also @Column(updatable = false), so JPA cannot backdate
        // it after the fact either. A native update is the only way to give
        // this test the data it always meant to have.
        EducationResource olderResource = persistResource(createResource(
            "Older Resource", EducationCategory.PRENATAL_CARE, EducationResourceType.ARTICLE, hospitalId));
        EducationResource newerResource = persistResource(createResource(
            "Newer Resource", EducationCategory.PRENATAL_CARE, EducationResourceType.VIDEO, hospitalId));

        backdateCreatedAt(olderResource.getId(), LocalDateTime.now().minusDays(2));
        backdateCreatedAt(newerResource.getId(), LocalDateTime.now());
        entityManager.clear();

        List<EducationResource> results = educationResourceRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId);

        assertThat(results)
            .extracting(EducationResource::getTitle)
            .containsExactly("Newer Resource", "Older Resource");
    }

    @Test
    void shouldFilterByCategoryForHospital() {
        persistResource(createResource("Prenatal Guide", EducationCategory.PRENATAL_CARE, EducationResourceType.ARTICLE, hospitalId));
        persistResource(createResource("Nutrition Guide", EducationCategory.NUTRITION, EducationResourceType.VIDEO, hospitalId));

        List<EducationResource> results = educationResourceRepository
            .findByCategoryAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(EducationCategory.PRENATAL_CARE, hospitalId);

        assertThat(results).hasSize(1).allMatch(resource -> resource.getCategory() == EducationCategory.PRENATAL_CARE);
    }

    @Test
    void shouldFilterByResourceTypeForHospital() {
        persistResource(createResource("Video Resource", EducationCategory.PRENATAL_CARE, EducationResourceType.VIDEO, hospitalId));
        persistResource(createResource("Article Resource", EducationCategory.NUTRITION, EducationResourceType.ARTICLE, hospitalId));

        List<EducationResource> results = educationResourceRepository
            .findByResourceTypeAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(EducationResourceType.VIDEO, hospitalId);

        assertThat(results).hasSize(1).allMatch(resource -> resource.getResourceType() == EducationResourceType.VIDEO);
    }

    @Test
    void shouldFindByPrimaryLanguageForHospital() {
        persistResource(createResource("English Resource", EducationCategory.NUTRITION, EducationResourceType.ARTICLE, hospitalId));
        EducationResource spanishResource = createResource("Spanish Resource", EducationCategory.NUTRITION, EducationResourceType.ARTICLE, hospitalId);
        spanishResource.setPrimaryLanguage("es");
        persistResource(spanishResource);

        List<EducationResource> results = educationResourceRepository
            .findByPrimaryLanguageAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc("es", hospitalId);

        assertThat(results).hasSize(1).first().extracting(EducationResource::getTitle).isEqualTo("Spanish Resource");
    }

    @Test
    void shouldSearchResourcesByTitleAndDescription() {
        persistResource(createResource("Prenatal Yoga", EducationCategory.EXERCISE, EducationResourceType.VIDEO, hospitalId));
        persistResource(createResource("Nutrition Basics", EducationCategory.NUTRITION, EducationResourceType.ARTICLE, hospitalId));

        List<EducationResource> results = educationResourceRepository.searchResources("yoga", hospitalId);

        assertThat(results).hasSize(1).first().extracting(EducationResource::getTitle).isEqualTo("Prenatal Yoga");
    }

    @Test
    void shouldFindPopularResourcesByCategory() {
        EducationResource highViewResource = createResource("High View", EducationCategory.PRENATAL_CARE, EducationResourceType.ARTICLE, hospitalId);
        highViewResource.setViewCount(150L);
        highViewResource.setAverageRating(4.8);

        EducationResource mediumViewResource = createResource("Medium View", EducationCategory.PRENATAL_CARE, EducationResourceType.VIDEO, hospitalId);
        mediumViewResource.setViewCount(80L);
        mediumViewResource.setAverageRating(4.6);

        persistResource(highViewResource);
        persistResource(mediumViewResource);

        List<EducationResource> results = educationResourceRepository.findPopularResourcesByCategory(EducationCategory.PRENATAL_CARE, hospitalId);

        assertThat(results).extracting(EducationResource::getTitle).containsExactly("High View", "Medium View");
    }

    @Test
    void shouldFindResourcesByTag() {
        EducationResource taggedResource = createResource("Tagged Resource", EducationCategory.NUTRITION, EducationResourceType.ARTICLE, hospitalId);
        taggedResource.getTags().add("prenatal");
        persistResource(taggedResource);

        EducationResource untaggedResource = createResource("Untagged Resource", EducationCategory.NUTRITION, EducationResourceType.ARTICLE, hospitalId);
        persistResource(untaggedResource);

        List<EducationResource> results = educationResourceRepository.findByTag("prenatal");

        assertThat(results).hasSize(1).first().extracting(EducationResource::getTitle).isEqualTo("Tagged Resource");
    }

    private EducationResource createResource(
        String title,
        EducationCategory category,
        EducationResourceType type,
        UUID hospitalId
    ) {
        LocalDateTime now = LocalDateTime.now();

        return EducationResource.builder()
            .title(title)
            .description(title + " description")
            .resourceType(type)
            .category(category)
            .hospitalId(hospitalId)
            .primaryLanguage("en")
            .isActive(true)
            .isEvidenceBased(true)
            .createdBy("tester")
            .lastModifiedBy("tester")
            .createdAt(now)
            .updatedAt(now)
            .build();
    }

    /**
     * Set {@code created_at} past both the @PrePersist callback and the
     * {@code updatable = false} mapping, so a test about ordering by that
     * column can actually control it.
     */
    private void backdateCreatedAt(UUID resourceId, LocalDateTime createdAt) {
        entityManager.getEntityManager()
            .createNativeQuery("UPDATE clinical.education_resources SET created_at = :createdAt WHERE id = :id")
            .setParameter("createdAt", createdAt)
            .setParameter("id", resourceId)
            .executeUpdate();
    }

    private EducationResource persistResource(EducationResource resource) {
        entityManager.persist(resource);
        entityManager.flush();
        return resource;
    }
}
