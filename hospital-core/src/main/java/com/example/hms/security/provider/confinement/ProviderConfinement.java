package com.example.hms.security.provider.confinement;

import com.example.hms.enums.FacilityType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The confinement decision (provider plan §3.3, §6.4), as a pure function of
 * the caller and the handler Spring MVC matched. The filter asks it on every
 * request of a provider user; {@code ProviderConfinementCoverageTest} asks it
 * for every handler of the application, so the frozen snapshots describe
 * exactly what runs.
 */
public final class ProviderConfinement {

    private ProviderConfinement() {
    }

    /**
     * May a provider user reach the handler {@code method handlerPattern}?
     *
     * @param providerTypes  the provider facility types in the caller's
     *                       permitted set (never HOSPITAL); one type in
     *                       practice, since a user holds one kind of facility
     * @param patientHolder  the caller also holds a PATIENT assignment
     * @param method         the request's HTTP method ({@code HEAD} counts as {@code GET})
     * @param handlerPattern the matched handler's mapping pattern, or
     *                       {@code null} when no handler matched
     */
    public static boolean allows(Set<FacilityType> providerTypes, boolean patientHolder,
                                 String method, String handlerPattern) {
        if (handlerPattern == null || method == null) {
            return false;
        }
        String verb = normalise(method);
        if (anyMatches(CommonProviderConfinement.WHOLESALE_EXCLUSIONS, verb, handlerPattern)) {
            return false;
        }
        if (underAny(handlerPattern, CommonProviderConfinement.WHOLESALE_PREFIXES)
            || anyMatches(CommonProviderConfinement.RULES, verb, handlerPattern)) {
            return true;
        }
        if (patientHolder
            && (underAny(handlerPattern, CommonProviderConfinement.PATIENT_SELF_SERVICE_PREFIXES)
                || anyMatches(CommonProviderConfinement.PATIENT_SELF_SERVICE_RULES, verb, handlerPattern))) {
            return true;
        }
        // A facility-specific handler must be allowed for EVERY provider type
        // the caller holds, so a caller who somehow held two kinds gets the
        // narrower set, never the union.
        if (providerTypes == null || providerTypes.isEmpty()) {
            return false;
        }
        for (FacilityType type : providerTypes) {
            if (!anyMatches(rulesFor(type), verb, handlerPattern)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link #allows}, plus the own-id constraint of an entry that carries one
     * ({@code GET /users/{id}}: the {@code id} path variable must be the
     * caller's own user id).
     *
     * @param uriVariables the matched handler's path variables
     * @param callerUserId the caller's local user id; {@code null} never matches an own-id entry
     */
    public static boolean allowsRequest(Set<FacilityType> providerTypes, boolean patientHolder, String method,
                                        String handlerPattern, Map<String, String> uriVariables,
                                        UUID callerUserId) {
        if (!allows(providerTypes, patientHolder, method, handlerPattern)) {
            return false;
        }
        String ownIdVariable = ownIdVariable(normalise(method), handlerPattern);
        if (ownIdVariable == null) {
            return true;
        }
        String named = uriVariables == null ? null : uriVariables.get(ownIdVariable);
        return callerUserId != null && named != null && callerUserId.toString().equalsIgnoreCase(named.trim());
    }

    /** The own-id path variable of the entry naming this handler, or {@code null}. */
    private static String ownIdVariable(String method, String handlerPattern) {
        for (List<ConfinementRule> rules : List.of(CommonProviderConfinement.RULES,
                CommonProviderConfinement.PATIENT_SELF_SERVICE_RULES, PharmacyConfinement.RULES,
                LaboratoryConfinement.RULES)) {
            for (ConfinementRule rule : rules) {
                if (rule.matches(method, handlerPattern) && rule.ownIdVariable() != null) {
                    return rule.ownIdVariable();
                }
            }
        }
        return null;
    }

    /**
     * A path served outside Spring MVC: the health probe (exact path), and the
     * STOMP handshake (prefix, any method; its frames are confined by the
     * STOMP interceptor).
     */
    public static boolean allowsNonMvc(String method, String pathWithinApplication) {
        if (method == null || pathWithinApplication == null) {
            return false;
        }
        return anyMatches(CommonProviderConfinement.NON_MVC_RULES, normalise(method), pathWithinApplication)
            || underAny(pathWithinApplication, CommonProviderConfinement.NON_MVC_PREFIXES);
    }

    /** The facility-specific list of one provider type; a hospital has none. */
    public static List<ConfinementRule> rulesFor(FacilityType type) {
        if (type == FacilityType.PHARMACY) {
            return PharmacyConfinement.RULES;
        }
        if (type == FacilityType.LABORATORY) {
            return LaboratoryConfinement.RULES;
        }
        return List.of();
    }

    /** True when the pattern is a prefix itself or lies under it ({@code /auth} covers {@code /auth/login}, not {@code /authx}). */
    public static boolean underAny(String handlerPattern, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (handlerPattern.equals(prefix) || handlerPattern.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyMatches(List<ConfinementRule> rules, String method, String handlerPattern) {
        for (ConfinementRule rule : rules) {
            if (rule.matches(method, handlerPattern)) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String method) {
        String upper = method.toUpperCase(Locale.ROOT);
        return "HEAD".equals(upper) ? "GET" : upper;
    }
}
