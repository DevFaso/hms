package com.example.hms.config;

import com.example.hms.controller.EncounterController;
import com.example.hms.security.RoleExpansion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FHIR reader gate is the chart-reader set of the encounter list, by
 * construction — FHIR serves the whole chart. A hand copy drifts silently in
 * both directions: a role added to the chart and not here is refused its
 * FHIR reads; a role removed from the chart and not here keeps reading it
 * over FHIR. This pins the two together, allowing only the documented
 * difference (PHYSICIAN and SURGEON, named because the Keycloak converter
 * does not run RoleExpansion).
 */
class SecurityConfigFhirMatcherTest {

    private static final Set<String> NAMED_FOR_KEYCLOAK = Set.of(RoleExpansion.ROLE_PHYSICIAN, SecurityConstants.ROLE_SURGEON);

    private static Set<String> rolesIn(String expression) {
        Set<String> roles = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'(ROLE_[A-Z_]+)'").matcher(expression);
        while (m.find()) {
            roles.add(m.group(1));
        }
        return roles;
    }

    private static String encounterListRoles() throws ReflectiveOperationException {
        Field field = EncounterController.class.getDeclaredField("ENCOUNTER_LIST_ROLES");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    @DisplayName("the FHIR reader set is the encounter-list set plus the two Keycloak-named doctors")
    void readersMirrorTheEncounterList() throws ReflectiveOperationException {
        Set<String> readers = new LinkedHashSet<>(List.of(SecurityConfig.FHIR_READER_AUTHORITIES));
        assertThat(readers).containsAll(NAMED_FOR_KEYCLOAK);
        readers.removeAll(NAMED_FOR_KEYCLOAK);
        assertThat(readers).containsExactlyInAnyOrderElementsOf(rolesIn(encounterListRoles()));
    }

    @Test
    @DisplayName("writers are readers without the consulting clinicians")
    void writersAreReadersWithoutConsultingClinicians() {
        Set<String> readers = Set.of(SecurityConfig.FHIR_READER_AUTHORITIES);
        Set<String> consulting = rolesIn(SecurityConstants.CONSULTING_CLINICIANS_AUTHORITIES);
        assertThat(consulting).isNotEmpty();
        assertThat(readers).containsAll(List.of(SecurityConfig.FHIR_WRITER_AUTHORITIES));
        assertThat(SecurityConfig.FHIR_WRITER_AUTHORITIES).doesNotContainAnyElementsOf(consulting);
        Set<String> expected = new LinkedHashSet<>(readers);
        expected.removeAll(consulting);
        assertThat(SecurityConfig.FHIR_WRITER_AUTHORITIES).containsExactlyInAnyOrderElementsOf(expected);
    }
}
