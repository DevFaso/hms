package com.example.hms.cdshooks.rules;

import com.example.hms.service.emar.MedicationRoutes;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CR-01: the route and formulation of the order being judged, as the order states them. A catalogue
 * item's route says what that product is for and nothing about what was ordered, so it never stands
 * in here. Route words go through the one reading ({@link MedicationRoutes}); a route that reading
 * does not know stays unknown.
 *
 * @param written     the route as written; {@code null} when the order states none
 * @param code        the route's code (PO, IV, PR, ...); {@code null} when none is stated or it is unknown
 * @param formulation the ordered product's form, lower-cased without accents; {@code null} when unknown
 */
public record OrderedRoute(String written, String code, String formulation) {

    /** The route the numeric paracetamol limits in this codebase are written for. */
    public static final String ORAL = "PO";

    public static final OrderedRoute NOT_STATED = new OrderedRoute(null, null, null);

    private static final Set<String> SWALLOWED = Set.of(ORAL, "SL");
    private static final Set<String> INJECTED = Set.of("IV", "IM", "SC");
    private static final Set<String> RECTAL = Set.of("PR");
    private static final List<String> SWALLOWED_FORMS =
        List.of("tablet", "comprim", "capsule", "gelule", "syrup", "sirop", "sachet", "buvable");
    private static final List<String> INJECTED_FORMS = List.of("inject", "perfus", "infus", "ampoule", "ampule");
    private static final List<String> RECTAL_FORMS = List.of("suppos");

    public static OrderedRoute of(String route, String formulation) {
        String written = route == null || route.isBlank() ? null : route.trim();
        return new OrderedRoute(written, MedicationRoutes.canonical(written), normalized(formulation));
    }

    public boolean stated() {
        return written != null;
    }

    /**
     * Whether the product's form cannot be given by the ordered route: a suppository ordered by mouth,
     * a tablet ordered IV. Only names a form plainly fits; any other form is not judged.
     */
    public boolean contradictsFormulation() {
        if (code == null || formulation == null) {
            return false;
        }
        return mismatch(SWALLOWED_FORMS, SWALLOWED) || mismatch(INJECTED_FORMS, INJECTED) || mismatch(RECTAL_FORMS, RECTAL);
    }

    private boolean mismatch(List<String> forms, Set<String> routes) {
        return forms.stream().anyMatch(formulation::contains) && !routes.contains(code);
    }

    private static String normalized(String formulation) {
        if (formulation == null || formulation.isBlank()) {
            return null;
        }
        return Normalizer.normalize(formulation.trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
    }
}
