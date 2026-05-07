package com.example.hms.repository;

import com.example.hms.model.LabResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the cross-tenant Lab Results lazy-loading bug:
 * the super-admin list page rendered HOSPITAL / ORDER CODE / PATIENT NAME /
 * TEST as empty cells because {@code LabResultServiceImpl.getLabResultsPage}
 * with {@code hospitalId == null} fell through to Spring Data's
 * inherited {@code findAll(Pageable)} (no {@code @EntityGraph}), so the
 * mapper saw uninitialised proxies and the
 * {@code Hibernate.isInitialized(...)} defensive checks in
 * {@code LabResultMapper#resolveHospitalName/...} returned null —
 * surfacing as "—" placeholders in the UI.
 *
 * <p>This test locks the {@code @EntityGraph} on the
 * {@code findAll(Pageable)} override so a future refactor (or a Spring
 * Data version that inlines the method) can't silently regress the
 * cross-tenant view. We exercise the metadata via reflection rather
 * than spinning up a {@code @DataJpaTest} container — the bug class
 * is "annotation missing", not "annotation wrong", so reflection is
 * sufficient and avoids the per-test container cost.</p>
 */
class LabResultRepositoryEntityGraphTest {

    /** Attribute paths every {@code @EntityGraph}-decorated finder in this repo declares. */
    private static final List<String> EXPECTED_GRAPH = List.of(
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    );

    @Test
    void findAll_pageable_isOverriddenWithEntityGraph() throws NoSuchMethodException {
        // Method must be DECLARED on this repo (not inherited from JpaRepository).
        // Spring Data only applies @EntityGraph to overrides — an inherited
        // findAll(Pageable) silently bypasses the annotation.
        Method method = LabResultRepository.class.getDeclaredMethod("findAll", Pageable.class);

        assertThat(method.getReturnType()).isEqualTo(Page.class);
        assertThat(method.getDeclaringClass())
            .as("findAll(Pageable) must be DECLARED on LabResultRepository, "
                + "not inherited — Spring Data wires @EntityGraph by override")
            .isEqualTo(LabResultRepository.class);

        EntityGraph graph = method.getAnnotation(EntityGraph.class);
        assertThat(graph)
            .as("findAll(Pageable) must carry @EntityGraph so the cross-tenant "
                + "super-admin view (LabResultServiceImpl.getLabResultsPage with "
                + "hospitalId == null) gets eager-loaded relationships and the "
                + "mapper can populate hospitalName / labOrderCode / patientFullName / "
                + "labTestName instead of rendering empty cells")
            .isNotNull();

        assertThat(Arrays.asList(graph.attributePaths()))
            .as("attribute paths must match the other @EntityGraph-decorated "
                + "finders so the mapper's expectations stay uniform across "
                + "scoped (findByLabOrder_Hospital_IdIn) and unscoped (findAll) queries")
            .containsExactlyInAnyOrderElementsOf(EXPECTED_GRAPH);
    }

    @Test
    void findAll_listOverload_alsoCarriesEntityGraph_companionGuard() throws NoSuchMethodException {
        // Companion guard: the non-paginated findAll() override is the older
        // sibling of findAll(Pageable). If anyone "tidies up" by removing one
        // they should remove both — this test makes the dependency visible.
        Method method = LabResultRepository.class.getDeclaredMethod("findAll");

        assertThat(method.getDeclaringClass()).isEqualTo(LabResultRepository.class);
        assertThat(method.getAnnotation(EntityGraph.class)).isNotNull();
    }

    @Test
    void labResult_class_isWhatItClaimsToBe() {
        // Cheap sanity check that we're inspecting the right repo. If LabResult
        // ever gets renamed/relocated, the reflection-based assertions above
        // will start asserting against a stale type and silently pass.
        assertThat(LabResultRepository.class.getGenericInterfaces())
            .anyMatch(t -> t.getTypeName().contains("LabResult"));
        assertThat(LabResult.class.getSimpleName()).isEqualTo("LabResult");
    }
}
