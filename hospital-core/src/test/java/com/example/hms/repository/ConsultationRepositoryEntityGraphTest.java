package com.example.hms.repository;

import com.example.hms.enums.ConsultationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test guarding the {@link ConsultationRepository} {@code @EntityGraph}
 * configuration.
 *
 * <p><b>Background.</b> The dangling-FK fix
 * (commit {@code 0482465b — fix(persistence): dangling-FK resilience for /api/{consultations,prescriptions}})
 * added {@code @EntityGraph} to every list-returning method so the response
 * mapper would not 500 on a hard-deleted parent row. The first iteration
 * included {@code "patient.hospitalRegistrations"} — a nested {@code @OneToMany}
 * collection — in the attribute paths, intending to eagerly load the data
 * needed by {@code Patient.getMrnForHospital()}.
 *
 * <p>That broke {@code GET /api/consultations}: the endpoint started
 * returning {@code []} even when {@code consultationRepository.count()}
 * showed real rows. The combination of nested {@code @OneToMany} fetch +
 * derived-method {@code OrderBy} on a Hibernate 6 query silently filtered
 * every row out of the result set.
 *
 * <p><b>The lock.</b> This test asserts that the EntityGraph attributePaths
 * on every list method declare ONLY {@code @ManyToOne} associations — the
 * five parent references {@code patient}, {@code hospital},
 * {@code requestingProvider}, {@code consultant}, {@code encounter} — and
 * specifically NOT {@code "patient.hospitalRegistrations"} or any other
 * collection path. The mapper falls back to lazy load (wrapped in
 * {@code safeInit} + try/catch) for the MRN computation, which is the
 * acceptable trade-off.
 */
class ConsultationRepositoryEntityGraphTest {

    /** The exact attributePaths the repo must declare on every list method. */
    private static final List<String> EXPECTED_GRAPH = List.of(
        "patient",
        "hospital",
        "requestingProvider",
        "consultant",
        "encounter"
    );

    /** Path that MUST NOT appear — this is the value that broke production. */
    private static final String FORBIDDEN_PATH = "patient.hospitalRegistrations";

    /**
     * Every list-returning method that participates in the cross-tenant /
     * hospital-scoped read path must carry the same EntityGraph so the
     * response mapper has a stable expectation set.
     */
    private static final List<MethodSig> LIST_METHODS = List.of(
        MethodSig.of("findByPatient_IdOrderByRequestedAtDesc", UUID.class),
        MethodSig.of("findByHospital_IdAndStatusOrderByRequestedAtDesc", UUID.class, ConsultationStatus.class),
        MethodSig.of("findByHospital_IdOrderByRequestedAtDesc", UUID.class),
        MethodSig.of("findByRequestingProvider_IdOrderByRequestedAtDesc", UUID.class),
        MethodSig.of("findByConsultant_IdAndStatusOrderByRequestedAtDesc", UUID.class, ConsultationStatus.class),
        MethodSig.of("findByConsultant_IdOrderByRequestedAtDesc", UUID.class),
        MethodSig.of("findByStatusOrderByRequestedAtDesc", ConsultationStatus.class),
        MethodSig.of("findAllByOrderByRequestedAtDesc"),
        MethodSig.of("findByHospitalAndStatuses", UUID.class, List.class)
    );

    @Test
    void everyListMethod_carriesEntityGraph_withTheExpectedManyToOneOnlyPaths() throws NoSuchMethodException {
        for (MethodSig sig : LIST_METHODS) {
            Method method = ConsultationRepository.class.getDeclaredMethod(sig.name, sig.params);

            EntityGraph graph = method.getAnnotation(EntityGraph.class);
            assertThat(graph)
                .as("%s must carry @EntityGraph so the response mapper does not "
                    + "trigger lazy proxy initialisation per row (which 500s on dangling FK)",
                    sig.name)
                .isNotNull();

            List<String> paths = List.of(graph.attributePaths());
            assertThat(paths)
                .as("%s attributePaths must match the canonical @ManyToOne-only set so the "
                    + "mapper has uniform expectations across scoped and unscoped queries",
                    sig.name)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_GRAPH);

            assertThat(paths)
                .as("%s MUST NOT include '%s' — that nested @OneToMany path "
                    + "broke production by silently filtering all rows out of the "
                    + "result set (Hibernate 6 quirk: nested collection fetch + derived "
                    + "OrderBy returns []). The MRN lookup must stay on the lazy path "
                    + "wrapped by ConsultationServiceImpl.toResponseDTO try/catch.",
                    sig.name, FORBIDDEN_PATH)
                .doesNotContain(FORBIDDEN_PATH);
        }
    }

    @Test
    void findOverdueConsultations_isExempt_intentionallyLeftWithoutEntityGraph() throws NoSuchMethodException {
        // Sanity: findOverdueConsultations is NOT a user-facing list endpoint —
        // it is consumed by an internal SLA scheduler that does not call the
        // response mapper. It deliberately has no EntityGraph (avoids loading
        // associations the SLA job never reads). This test makes the omission
        // explicit so a future "tidy up — every method should match" refactor
        // doesn't accidentally fix what isn't broken.
        Method method = ConsultationRepository.class.getDeclaredMethod(
            "findOverdueConsultations", LocalDateTime.class, List.class);
        EntityGraph graph = method.getAnnotation(EntityGraph.class);
        assertThat(graph)
            .as("findOverdueConsultations is internal-only; @EntityGraph is intentionally absent")
            .isNull();
    }

    /* ------------------------------------------------------------------ */

    private record MethodSig(String name, Class<?>[] params) {
        static MethodSig of(String name, Class<?>... params) {
            return new MethodSig(name, params);
        }
    }
}
