package com.example.hms.service.lab;

import java.util.Set;

/**
 * Who may do what to a lab result.
 *
 * <p>One place, because the two lab features had drifted apart. Microbiology
 * already separated the two authorities properly — broad entry, narrow
 * finalisation by the laboratory — while lab results let a nurse create, edit
 * <b>and release</b>. Release is the act that makes a result authoritative on
 * the chart, so a nurse's entry sat on exactly the same footing as the
 * laboratory's, for a panel the nurse never ran. Meanwhile {@code sign}, the
 * lesser act, was already narrow. The gates disagreed with each other and with
 * microbiology.
 *
 * <p>The rule now is the one microbiology settled on: <b>entry is broad,
 * release belongs to the laboratory.</b> With one addition that microbiology
 * does not need — entry by a nurse is scoped to the tests a nurse actually
 * performs, which is what {@code LabTestDefinition.pointOfCare} records.
 */
public final class LabResultAuthority {

    private LabResultAuthority() {
    }

    /**
     * May enter and amend results without restriction — the laboratory, plus
     * the roles that own the catalogue and its quality.
     */
    public static final Set<String> LABORATORY_ROLES = Set.of(
        "ROLE_LAB_SCIENTIST",
        "ROLE_LAB_TECHNICIAN",
        "ROLE_LAB_MANAGER",
        "ROLE_LAB_DIRECTOR",
        "ROLE_QUALITY_MANAGER"
    );

    /**
     * May enter a result only for a test flagged point-of-care.
     *
     * <p>DOCTOR is here rather than in {@link #LABORATORY_ROLES} on purpose: a
     * doctor running a bedside glucose reading is doing point-of-care work,
     * and a doctor typing in a chemistry panel they did not run is the same
     * problem as a nurse doing it.
     */
    public static final Set<String> POINT_OF_CARE_ROLES = Set.of(
        "ROLE_DOCTOR",
        "ROLE_NURSE",
        "ROLE_MIDWIFE"
    );

    /**
     * May release a result to the chart.
     *
     * <p>Mirrors {@code MicrobiologyComponent.canFinalize} / the microbiology
     * FINALIZE set exactly. Note who is <b>absent</b>: nurses, midwives and
     * doctors. Releasing is the laboratory saying "this number is correct and
     * you may act on it", and the person who ordered the test is not the
     * person who should attest to it.
     */
    public static final Set<String> RELEASE_ROLES = Set.of(
        "ROLE_LAB_SCIENTIST",
        "ROLE_LAB_MANAGER",
        "ROLE_LAB_DIRECTOR",
        "ROLE_SUPER_ADMIN"
    );

    /** The Spring expression for {@link #RELEASE_ROLES}, for {@code @PreAuthorize}. */
    public static final String RELEASE_EXPRESSION =
        "hasAnyRole('LAB_SCIENTIST', 'LAB_MANAGER', 'LAB_DIRECTOR', 'SUPER_ADMIN')";

    /**
     * The Spring expression for who may reach the entry endpoints at all.
     *
     * <p>Deliberately still broad: whether a given caller may enter a result
     * for a given TEST depends on that test's point-of-care flag, which the
     * annotation cannot see. {@code LabResultEntryGuard} makes that call in
     * the service, where the test is loaded.
     */
    public static final String ENTRY_EXPRESSION =
        "hasAnyRole('DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_SCIENTIST', 'LAB_TECHNICIAN', "
        + "'LAB_MANAGER', 'LAB_DIRECTOR', 'QUALITY_MANAGER', 'SUPER_ADMIN')";
}
