package com.example.hms.i18n;

import com.example.hms.model.Role;
import com.example.hms.seed.RoleSeeder;
import com.example.hms.service.UserRoleHospitalAssignmentServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * QA D941: the onboarding welcome page showed the seeded English role
 * description ("Registered patient with self-service access…") and the
 * English checklist in a French session.
 *
 * <p>Guard for the class of bug: every role the seeder creates and every
 * onboarding checklist line has its own French and Spanish text (a missing
 * key would fall back to the English base bundle and pass unnoticed), and the
 * public assignment view reads them in the caller's language.
 */
class RoleOnboardingTextLocalizationTest {

    private static final MessageSource BUNDLES = TestMessageSources.bundles();
    private static final Locale FR = Locale.FRENCH;
    private static final Locale ES = Locale.forLanguageTag("es");

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> seededRoles() {
        return (Map<String, String>) ReflectionTestUtils.invokeMethod(RoleSeeder.class, "buildRoleCatalog");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> checklists() {
        return (Map<String, List<String>>) ReflectionTestUtils.getField(
            UserRoleHospitalAssignmentServiceImpl.class, "ROLE_PROFILE_CHECKLISTS");
    }

    @SuppressWarnings("unchecked")
    private static List<String> defaultChecklist() {
        return (List<String>) ReflectionTestUtils.getField(
            UserRoleHospitalAssignmentServiceImpl.class, "DEFAULT_PROFILE_CHECKLIST");
    }

    private static String text(String key, Locale locale) {
        return BUNDLES.getMessage(key, null, null, locale);
    }

    @Test
    void everySeededRoleHasItsOwnFrenchAndSpanishDescription() {
        List<String> missing = new ArrayList<>();
        seededRoles().forEach((code, english) -> {
            String key = "role.description." + code;
            String en = text(key, Locale.ENGLISH);
            String fr = text(key, FR);
            String es = text(key, ES);
            if (en == null || fr == null || es == null || fr.equals(en) || es.equals(en)) {
                missing.add(code);
            }
        });
        assertThat(missing).as("roles without their own FR/ES description").isEmpty();
    }

    @Test
    void everyChecklistLineHasItsOwnFrenchAndSpanishText() {
        Map<String, List<String>> all = new java.util.LinkedHashMap<>(checklists());
        all.put("DEFAULT", defaultChecklist());
        List<String> missing = new ArrayList<>();
        all.forEach((owner, lines) -> {
            for (int i = 1; i <= lines.size(); i++) {
                String key = "onboarding.checklist." + owner + "." + i;
                String en = text(key, Locale.ENGLISH);
                String fr = text(key, FR);
                String es = text(key, ES);
                if (en == null || fr == null || es == null || fr.equals(en) || es.equals(en)) {
                    missing.add(key);
                }
            }
        });
        assertThat(missing).as("checklist lines without their own FR/ES text").isEmpty();
    }

    @Test
    void thePublicViewReadsThePatientRoleInFrench() {
        UserRoleHospitalAssignmentServiceImpl service =
            mock(UserRoleHospitalAssignmentServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "messageSource", BUNDLES);
        Role patient = Role.builder().code("ROLE_PATIENT").name("ROLE_PATIENT")
            .description("Registered patient with self-service access to medical records, appointments, and prescriptions")
            .build();
        LocaleContextHolder.setLocale(FR);

        String description = ReflectionTestUtils.invokeMethod(service, "localizedRoleDescription", patient);
        List<String> checklist = ReflectionTestUtils.invokeMethod(service, "localizedProfileChecklist", patient);

        assertThat(description).startsWith("Patient inscrit");
        assertThat(checklist).containsExactly(
            "Vérifiez et confirmez vos informations personnelles",
            "Ajoutez au moins un contact d’urgence",
            "Vérifiez vos coordonnées de contact préférées");
    }

    @Test
    void aRoleAnAdministratorCreatedKeepsItsOwnDescription() {
        UserRoleHospitalAssignmentServiceImpl service =
            mock(UserRoleHospitalAssignmentServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "messageSource", BUNDLES);
        Role custom = Role.builder().code("ROLE_CHW").name("ROLE_CHW").description("Agent de santé communautaire").build();
        LocaleContextHolder.setLocale(ES);

        String description = ReflectionTestUtils.invokeMethod(service, "localizedRoleDescription", custom);

        assertThat(description).isEqualTo("Agent de santé communautaire");
    }

    @Test
    void anAdministratorsRewriteOfASeededRoleIsKept() {
        UserRoleHospitalAssignmentServiceImpl service =
            mock(UserRoleHospitalAssignmentServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "messageSource", BUNDLES);
        Role nurse = Role.builder().code("ROLE_NURSE").name("ROLE_NURSE")
            .description("Infirmier du service de nuit, CHU QA").build();
        LocaleContextHolder.setLocale(ES);

        String description = ReflectionTestUtils.invokeMethod(service, "localizedRoleDescription", nurse);

        assertThat(description).isEqualTo("Infirmier du service de nuit, CHU QA");
    }
}
