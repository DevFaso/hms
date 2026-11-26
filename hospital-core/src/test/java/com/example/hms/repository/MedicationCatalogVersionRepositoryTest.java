package com.example.hms.repository;

import com.example.hms.enums.MedicationCatalogChangeType;
import com.example.hms.enums.MedicationCatalogVersionKind;
import com.example.hms.enums.MedicationCatalogVersionStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Go-live D2 (V385): the queue, the history and the decision lookup of the medication catalogue's versions are
 * per hospital in the QUERY, through Hibernate: hospital A never reads hospital B's, a hospital never reads the
 * platform's queue, and global view (no hospital) reads the platform's own entries only.
 */
@DataJpaTest
@ActiveProfiles("test")
class MedicationCatalogVersionRepositoryTest {

    @Autowired
    private MedicationCatalogVersionRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private UUID hospitalA;
    private UUID hospitalB;
    private final UUID entry = UUID.randomUUID();
    private MedicationCatalogVersion ofA;
    private MedicationCatalogVersion ofB;
    private MedicationCatalogVersion ofThePlatform;

    @BeforeEach
    void setUp() {
        hospitalA = hospital("MCV-A").getId();
        hospitalB = hospital("MCV-B").getId();
        ofA = version(hospitalA, entry, MedicationCatalogVersionKind.FORMULARY, 1,
            MedicationCatalogVersionStatus.PENDING_APPROVAL, 3);
        version(hospitalA, entry, MedicationCatalogVersionKind.DOSE_LIMIT, 1,
            MedicationCatalogVersionStatus.APPROVED, 2);
        ofB = version(hospitalB, UUID.randomUUID(), MedicationCatalogVersionKind.FORMULARY, 4,
            MedicationCatalogVersionStatus.PENDING_APPROVAL, 1);
        ofThePlatform = version(null, UUID.randomUUID(), MedicationCatalogVersionKind.FORMULARY, 1,
            MedicationCatalogVersionStatus.PENDING_APPROVAL, 0);
        entityManager.flush();
        entityManager.clear();
    }

    private Hospital hospital(String code) {
        return entityManager.persist(Hospital.builder()
            .name("Hospital " + code).code(code).city("Ouagadougou").state("Kadiogo").country("Burkina Faso")
            .address("1 Main St").email(code.toLowerCase() + "@facility.test").active(true).build());
    }

    private MedicationCatalogVersion version(UUID hospitalId, UUID catalogItemId, MedicationCatalogVersionKind kind,
                                             int number, MedicationCatalogVersionStatus status, int hoursAgo) {
        boolean decided = status != MedicationCatalogVersionStatus.PENDING_APPROVAL;
        return entityManager.persist(MedicationCatalogVersion.builder()
            .hospitalId(hospitalId).catalogItemId(catalogItemId).kind(kind)
            .changeType(MedicationCatalogChangeType.UPDATE).versionNumber(number).status(status)
            .itemLabel("Paracétamol 500 mg").payload("{}").changeReason("Revue")
            .proposedBy(UUID.randomUUID()).proposedAt(LocalDateTime.now().minusHours(hoursAgo))
            .decidedBy(decided ? UUID.randomUUID() : null).decidedAt(decided ? LocalDateTime.now() : null)
            .build());
    }

    @Test
    void aHospitalsQueueHoldsItsOwnVersionsOnly_theNewestProposalFirst() {
        List<MedicationCatalogVersion> all = repository.findInScope(hospitalA, null, null, PageRequest.of(0, 20))
            .getContent();
        assertThat(all).extracting(MedicationCatalogVersion::getHospitalId).containsOnly(hospitalA);
        assertThat(all).extracting(MedicationCatalogVersion::getKind)
            .containsExactly(MedicationCatalogVersionKind.DOSE_LIMIT, MedicationCatalogVersionKind.FORMULARY);

        assertThat(repository.findInScope(hospitalA, MedicationCatalogVersionStatus.PENDING_APPROVAL, null,
            PageRequest.of(0, 20)).getContent())
            .extracting(MedicationCatalogVersion::getId).containsExactly(ofA.getId());
        assertThat(repository.findInScope(hospitalA, null, MedicationCatalogVersionKind.DOSE_LIMIT,
            PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        assertThat(repository.findInScope(hospitalB, null, null, PageRequest.of(0, 20)).getContent())
            .extracting(MedicationCatalogVersion::getId).containsExactly(ofB.getId());
    }

    @Test
    void globalViewReadsThePlatformsOwnVersions_neverAHospitals() {
        assertThat(repository.findInScope(null, null, null, PageRequest.of(0, 20)).getContent())
            .extracting(MedicationCatalogVersion::getId).containsExactly(ofThePlatform.getId());
    }

    @Test
    void aVersionIsFoundForADecisionOnlyAtItsOwnHospital() {
        assertThat(repository.findForDecision(ofA.getId(), hospitalA)).isPresent();
        assertThat(repository.findForDecision(ofA.getId(), hospitalB)).isEmpty();
        assertThat(repository.findForDecision(ofA.getId(), null)).isEmpty();
        assertThat(repository.findForDecision(ofThePlatform.getId(), hospitalA)).isEmpty();
        assertThat(repository.findForDecision(ofThePlatform.getId(), null)).isPresent();
    }

    @Test
    void theHistoryOfAnEntryIsReadAtItsHospital_andTheNumberingIsPerKind() {
        assertThat(repository.findHistoryInScope(entry, hospitalA)).hasSize(2);
        assertThat(repository.findHistoryInScope(entry, hospitalB)).isEmpty();
        assertThat(repository.highestVersionNumber(entry, MedicationCatalogVersionKind.FORMULARY)).isEqualTo(1);
        assertThat(repository.highestVersionNumber(UUID.randomUUID(), MedicationCatalogVersionKind.FORMULARY)).isZero();
        assertThat(repository.findByCatalogItemIdInAndStatus(List.of(entry),
            MedicationCatalogVersionStatus.PENDING_APPROVAL))
            .extracting(MedicationCatalogVersion::getKind).containsExactly(MedicationCatalogVersionKind.FORMULARY);
    }
}
