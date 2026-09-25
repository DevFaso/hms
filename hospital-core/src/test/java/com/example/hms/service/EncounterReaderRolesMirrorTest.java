package com.example.hms.service;

import com.example.hms.controller.EncounterController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EncounterReaderRoles} states its invariant in a comment: each set is
 * <b>exactly its endpoint's {@code @PreAuthorize} minus {@code ROLE_PATIENT}</b>.
 * The literals are copied by hand from {@link EncounterController}, and a
 * comment does not fail when they drift. Either direction of drift is a
 * defect:
 * <ul>
 *   <li>a role the annotation admits but the set omits is classed as the
 *       subject, so a receptionist or radiologist would be refused every
 *       record that is not their own;</li>
 *   <li>a role the set names but the annotation does not admit reaches the
 *       handler only through {@code ROLE_PATIENT}, and the set would then
 *       reclassify that patient as a clinician and hand them every record at
 *       the hospital. That is not hypothetical: it is the escalation an
 *       earlier draft of this PR shipped by naming {@code ROLE_SURGEON}.</li>
 * </ul>
 *
 * <p>Reflection, not a source scan. {@code ENCOUNTER_DETAIL_ROLES} is built
 * by concatenating {@code CONSULTING_CLINICIANS_AUTHORITIES}, which a source
 * scan would see as an identifier rather than three roles. The compiled
 * annotation carries the constant already inlined — the exact string Spring
 * evaluates — so reading it back asserts against what actually guards the
 * handler.
 */
@DisplayName("Each encounter reader set is exactly its endpoint's annotation minus ROLE_PATIENT")
class EncounterReaderRolesMirrorTest {

    private static final Pattern ROLE = Pattern.compile("'(ROLE_[A-Z_]+)'");

    static Stream<Arguments> readers() {
        return Stream.of(
            Arguments.of("getById", "/{id}", EncounterReaderRoles.DETAIL_NON_SUBJECT_ROLES, true),
            Arguments.of("getAfterVisitSummary", "/{encounterId}/avs",
                EncounterReaderRoles.AVS_NON_SUBJECT_ROLES, true),
            Arguments.of("getEncounterNoteHistory", "/{encounterId}/notes/history",
                EncounterReaderRoles.NOTE_HISTORY_NON_SUBJECT_ROLES, false));
    }

    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("readers")
    void theRoleSetMirrorsTheAnnotation(String handler, String path, Set<String> nonSubjectRoles,
                                        boolean admitsPatient) {
        Method method = handler(handler);

        // Pin the handler to its route as well as its name, so a rename that
        // moved the name onto a different endpoint cannot pass silently.
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping).as("%s must still be a GET handler", handler).isNotNull();
        assertThat(mapping.value()).as("%s must still serve %s", handler, path).containsExactly(path);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).as("%s must still be annotated", handler).isNotNull();

        Set<String> admitted = new TreeSet<>();
        Matcher role = ROLE.matcher(preAuthorize.value());
        while (role.find()) {
            admitted.add(role.group(1));
        }
        assertThat(admitted).as("%s must still admit someone", handler).isNotEmpty();

        // Whether the endpoint admits the patient is pinned too: adding
        // ROLE_PATIENT to the note-history annotation is exactly the change
        // that set exists to make safe, and it should be a deliberate edit to
        // this table, not a silent one.
        assertThat(admitted.contains("ROLE_PATIENT"))
            .as("%s admitting ROLE_PATIENT", handler).isEqualTo(admitsPatient);
        admitted.remove("ROLE_PATIENT");

        assertThat(new TreeSet<>(nonSubjectRoles))
            .as("a role the annotation admits but the set omits is refused its colleagues' "
                + "records; a role the set names but the annotation does not admit enters "
                + "only through ROLE_PATIENT and would be reclassified as a clinician")
            .isEqualTo(admitted);
    }

    private static Method handler(String name) {
        List<Method> matches = Arrays.stream(EncounterController.class.getDeclaredMethods())
            .filter(m -> m.getName().equals(name))
            .toList();
        assertThat(matches).as("exactly one %s on EncounterController", name).hasSize(1);
        return matches.get(0);
    }
}
