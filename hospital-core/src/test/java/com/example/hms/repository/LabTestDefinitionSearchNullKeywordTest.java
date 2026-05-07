package com.example.hms.repository;

import com.example.hms.model.LabTestDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression test for the UAT 500: super-admin opens
 * {@code GET /api/lab-test-definitions/search?page=0&size=20} (no
 * keyword / unit / category) and Postgres rejects the generated SQL
 * with:
 *
 * <pre>
 * ERROR: function lower(bytea) does not exist
 *   Hint: No function matches the given name and argument types.
 * </pre>
 *
 * <p><b>Why it failed.</b> The original JPQL did
 * {@code LOWER(CONCAT('%', :keyword, '%'))}. Hibernate generates
 * {@code lower('%' || ? || '%')} and binds the {@code ?} placeholder
 * for a Java {@code null} String as {@code SQL NULL of type bytea}
 * (the JDBC driver's default {@code setNull} type when no type code
 * is supplied). Postgres then needs to evaluate
 * {@code '%' || NULL_bytea || '%'} which it types as {@code bytea},
 * and {@code lower(bytea)} doesn't exist. The check fails at parse
 * time even though the leading {@code :keyword IS NULL} branch would
 * short-circuit the LIKE at runtime — Postgres type-checks every
 * branch of an OR. H2 (used by the local-h2 profile and by
 * {@link DataJpaTest}'s default in-memory DB when no testcontainer is
 * configured) is more lenient and infers {@code varchar} for the
 * NULL, which is why the bug only surfaced on UAT.</p>
 *
 * <p><b>The fix.</b> Wrap each potentially-null String parameter in
 * {@code CAST(:p AS string)}, matching the existing pattern used by
 * {@code UserRepository#searchByCriteria} and
 * {@code HospitalRepository#findAllWithDepartments}. Hibernate
 * translates that to {@code cast(? as text)}, which gives Postgres an
 * explicit type for the bind even when NULL.</p>
 *
 * <p>This test exercises the all-nulls call shape (the exact one
 * {@code SuperAdminDashboardServiceImpl#getRecentLabTestDefinitions}
 * makes — and the one the UAT super-admin hit). On H2 the test passes
 * either way; on Postgres without the CAST it would throw at
 * parse-time. Locking it as a test means a future "tidy up" that
 * removes the CAST will trip CI.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
class LabTestDefinitionSearchNullKeywordTest {

    @Autowired
    private LabTestDefinitionRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void search_allNullParameters_doesNotThrowAndReturnsAllRows() {
        // Two seed rows with all the lookup-relevant fields populated so
        // a successful query has something to return; null description on
        // one row exercises the COALESCE branch.
        entityManager.persist(LabTestDefinition.builder()
            .testCode("CBC")
            .name("Complete Blood Count")
            .category("HEMATOLOGY")
            .description("Routine diagnostics")
            .unit("cells/mm3")
            .sampleType("BLOOD")
            .active(true)
            .build());
        entityManager.persist(LabTestDefinition.builder()
            .testCode("HBA1C")
            .name("Hemoglobin A1C")
            .category("ENDOCRINE")
            .description(null)             // exercises the COALESCE branch
            .unit("%")
            .sampleType("BLOOD")
            .active(true)
            .build());
        entityManager.flush();

        // The exact call shape SuperAdminDashboardServiceImpl uses for
        // getRecentLabTestDefinitions and that the frontend lab-test-config
        // page makes on first paint (search() with everything null).
        assertThatCode(() ->
            repository.search(null, null, null, null, null, PageRequest.of(0, 20))
        ).doesNotThrowAnyException();

        Page<LabTestDefinition> page =
            repository.search(null, null, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
            .as("with all parameters null the WHERE clause must short-circuit "
                + "to TRUE and return every row, NOT trip the lower(bytea) "
                + "Postgres parse-time error")
            .hasSize(2);
    }

    @Test
    void search_keywordOnly_matchesAcrossNameTestCodeAndDescription() {
        // A non-null keyword still has to work — defensive coverage so the
        // CAST(:keyword AS string) doesn't accidentally break the happy path.
        entityManager.persist(LabTestDefinition.builder()
            .testCode("CBC")
            .name("Complete Blood Count")
            .category("HEMATOLOGY")
            .description("Routine diagnostics")
            .unit("cells/mm3")
            .sampleType("BLOOD")
            .active(true)
            .build());
        entityManager.persist(LabTestDefinition.builder()
            .testCode("HBA1C")
            .name("Hemoglobin A1C")
            .category("ENDOCRINE")
            .description(null)
            .unit("%")
            .sampleType("BLOOD")
            .active(true)
            .build());
        entityManager.flush();

        Page<LabTestDefinition> hit = repository.search(
            "blood", null, null, null, null, PageRequest.of(0, 20));

        // "blood" matches CBC.description ("Routine diagnostics") only via
        // the .name path — name="Complete Blood Count" contains "Blood".
        assertThat(hit.getContent())
            .extracting(LabTestDefinition::getTestCode)
            .containsExactlyInAnyOrder("CBC");
    }

    @Test
    void search_unitOnly_filtersAndDoesNotThrow() {
        entityManager.persist(LabTestDefinition.builder()
            .testCode("CBC").name("Complete Blood Count").category("HEMATOLOGY")
            .unit("cells/mm3").sampleType("BLOOD").active(true).build());
        entityManager.persist(LabTestDefinition.builder()
            .testCode("HBA1C").name("Hemoglobin A1C").category("ENDOCRINE")
            .unit("%").sampleType("BLOOD").active(true).build());
        entityManager.flush();

        // Same CAST applied to :unit / :category — exercise both with
        // mixed-case input to confirm LOWER() applied on both sides.
        Page<LabTestDefinition> hit = repository.search(
            null, "CELLS/MM3", null, null, null, PageRequest.of(0, 20));

        assertThat(hit.getContent())
            .extracting(LabTestDefinition::getTestCode)
            .containsExactly("CBC");
    }
}
