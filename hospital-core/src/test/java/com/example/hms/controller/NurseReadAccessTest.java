package com.example.hms.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads a ward nurse must have, and the one they must not.
 *
 * <p>The portal listed Consultations for {@code ROLE_NURSE} and the route guard
 * admitted them, but three of the read endpoints behind it did not — so a nurse
 * saw the consultation COUNT on {@code /stats} and got 403 on every list behind
 * it. The imaging split was worse: {@code GET /order/{id}} admitted nurses and
 * {@code /order/{id}/all} did not, which withholds the addenda — the version
 * carrying "findings revised" — from the people acting on them.
 *
 * <p>Asserted against the annotation rather than through a {@code @WebMvcTest}
 * slice on purpose: the slices are expensive and fragile here (one new
 * WebMvcConfigurer once broke all 105 of them), and the annotation IS the
 * contract. This fails if someone narrows a guard again.
 */
@DisplayName("Nurse read access")
class NurseReadAccessTest {

    private static String guardFor(Class<?> controller, String path) {
        Optional<Method> method = Arrays.stream(controller.getDeclaredMethods())
            .filter(m -> {
                GetMapping mapping = m.getAnnotation(GetMapping.class);
                return mapping != null && Arrays.asList(mapping.value()).contains(path);
            })
            .findFirst();

        assertThat(method).as("GET %s on %s", path, controller.getSimpleName()).isPresent();
        PreAuthorize pre = method.orElseThrow().getAnnotation(PreAuthorize.class);
        assertThat(pre).as("GET %s must carry @PreAuthorize", path).isNotNull();
        return pre.value();
    }

    @Test
    @DisplayName("a nurse can read the consultation lists whose count they already see")
    void nurseReadsConsultationLists() {
        // /stats has always admitted NURSE. Showing a clinician the number of
        // overdue consults and refusing the list is the drift being closed.
        assertThat(guardFor(ConsultationController.class, "/stats")).contains("NURSE");

        for (String path : new String[] {"/hospital/{hospitalId}", "/hospital/{hospitalId}/pending", "/overdue"}) {
            assertThat(guardFor(ConsultationController.class, path))
                .as("GET %s — ward nurses chase overdue consults and prep the patient", path)
                .contains("NURSE");
        }
    }

    @Test
    @DisplayName("a nurse does NOT get /consultations/mine — they are never the consultant")
    void nurseDoesNotGetMine() {
        // Not an oversight, and not to be "fixed" by the next person widening
        // this controller: "mine" means consultations assigned to the caller AS
        // CONSULTANT, so for a nurse it can only ever be empty. The portal
        // hides the tab rather than rendering one that shows nothing.
        assertThat(guardFor(ConsultationController.class, "/mine"))
            .doesNotContain("NURSE");
    }

    @Test
    @DisplayName("a nurse can read imaging report history, where the addenda are")
    void nurseReadsImagingHistory() {
        String latest = guardFor(ImagingResultController.class, "/order/{orderId}");
        String history = guardFor(ImagingResultController.class, "/order/{orderId}/all");

        // The two must agree about nurses. Admitting them to the latest report
        // — which may itself be PRELIMINARY — while withholding the history
        // never implemented "no unconfirmed reads for non-physicians"; it only
        // hid the corrections.
        assertThat(latest).contains("NURSE");
        assertThat(history)
            .as("addenda live in the version history")
            .contains("NURSE");
    }
}
