package com.example.hms.architecture;

import com.example.hms.testsupport.ControllerMappingRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Personal identifiers never travel in a URL.
 *
 * <p>A name, a birth date, a phone number, an e-mail address, an MRN, a national id or a login in a
 * query string or a path segment lands in proxy and access logs, in browser history and in the
 * portal's front-end tracing. Searches and lookups keyed by one take it in the body of a POST that
 * only reads ({@code @ReadsOnly}).
 *
 * <p>This walks Spring's own mapping registry over every controller and fails on any handler, of
 * any HTTP method (a write's query string is logged like a read's), whose {@code @RequestParam},
 * {@code @PathVariable} or un-annotated simple parameter (which Spring binds from the query
 * string) carries one of those names, unless it is in {@link #ALLOWED} with its reason.
 *
 * <p>QA D3750: a search box's free text ({@code search}, {@code q}, {@code query}, {@code keyword},
 * …) is named too: what is typed there is very often a name, a phone number or an MRN, so a list's
 * term goes in the body of {@code POST …/term-search}. Only a box that searches a catalogue of
 * things (drugs, tests, departments, facilities), where a person's identifier has nothing to
 * match, is allowlisted.
 */
@DisplayName("No handler takes a personal identifier in its URL")
class PersonalIdentifierUrlGuardTest {

    /** Lower-cased parameter names that carry a personal identifier. */
    private static final Set<String> IDENTIFIER_NAMES = Set.of(
        "name", "firstname", "lastname", "middlename", "fullname", "patientname",
        "dob", "dateofbirth", "birthdate",
        "phone", "phonenumber", "mobile", "msisdn", "telephone",
        "email", "emailaddress",
        "mrn", "mri", "nationalid", "nationaluniqueid", "nui",
        "identifier", "username", "patientusername", "recipientusername",
        // QA D3750: a search box's free text is whatever the user typed, very often one of the above.
        "search", "q", "query", "term", "searchterm", "keyword", "keywords", "filter");

    private static final String RESEND = "the account's contact for a new activation link, before any session; the portal"
        + " posts it in the body, both patient mobile apps still send ?email=, so the query parameter stays until they post"
        + " the body";
    private static final String LEGACY_RESET = "a password-reset request before any session; current clients post"
        + " /auth/password/request with a JSON body, this older route keeps its query parameters for installed app builds"
        + " that predate it (rate-limited, always 204)";

    /** {@code Controller#method parameter} of a handler allowed one anyway, and why. A new entry needs a reason too. */
    private static final Map<String, String> ALLOWED = Map.ofEntries(
        Map.entry("AuthController#verifyEmailViaLink email",
            "deprecated GET twin of POST /auth/verify-email, kept one release for the installed patient mobile apps;"
                + " the portal posts the body"),
        Map.entry("PermissionController#searchPermissions name", "a permission's name, not a person's"),
        Map.entry("AuthController#resendVerificationEmail email", RESEND),
        Map.entry("AuthController#resendVerificationEmail phone", RESEND),
        Map.entry("AuthController#legacyRequestPasswordReset email", LEGACY_RESET),
        Map.entry("AuthController#legacyRequestPasswordReset identifier", LEGACY_RESET),
        Map.entry("AuthController#legacyRequestPasswordReset phone", LEGACY_RESET),
        Map.entry("PasswordResetController#requestResetQuery email", LEGACY_RESET),
        Map.entry("PasswordResetController#requestResetQuery identifier", LEGACY_RESET),
        Map.entry("PasswordResetController#requestResetQuery phone", LEGACY_RESET),
        Map.entry("AdmissionOrderSetController#list search", "searches the names of admission order sets (a catalogue)"),
        Map.entry("DepartmentController#searchDepartments query", "searches department names and codes (a catalogue)"),
        Map.entry("LabTestDefinitionController#search keyword", "searches lab test definitions (a catalogue)"),
        Map.entry("MedicationCatalogController#search q", "searches the drug catalogue"),
        Map.entry("PatientEducationController#searchResources query",
            "searches published patient-education resources (a catalogue), never a patient's record"),
        Map.entry("PharmacyRegistryController#search q", "searches pharmacies by name or city (facilities)"),
        Map.entry("ProviderDirectoryController#search q",
            "searches verified private pharmacies and laboratories by name (facilities)"),
        Map.entry("SuperAdminDashboardController#searchHospitalsForScopeChip q",
            "searches hospital names for the super-admin scope chip (facilities)"));

    private static final ParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

    /**
     * The name a parameter is bound from the URL under: a {@code @RequestParam}, a
     * {@code @PathVariable}, or an un-annotated simple value, which Spring binds as a request
     * parameter. Null for anything read from elsewhere (a body, a header, the principal).
     */
    private static String boundName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        String declared;
        if (requestParam != null) {
            declared = requestParam.name().isEmpty() ? requestParam.value() : requestParam.name();
        } else if (pathVariable != null) {
            declared = pathVariable.name().isEmpty() ? pathVariable.value() : pathVariable.name();
        } else if (BeanUtils.isSimpleProperty(parameter.getParameterType())
            && Arrays.stream(parameter.getParameterAnnotations())
                .noneMatch(annotation -> annotation.annotationType().getName().startsWith("org.springframework."))) {
            // No binding annotation (a validation one such as @NotBlank does not count): Spring
            // binds a simple value from the query string.
            declared = "";
        } else {
            return null;
        }
        if (!declared.isEmpty()) {
            return declared;
        }
        parameter.initParameterNameDiscovery(NAMES);
        return parameter.getParameterName();
    }

    /** Every handler, whatever its HTTP method: a write's query string is logged like a read's. */
    private static Set<String> identifierParametersInUrls() {
        Set<String> found = new TreeSet<>();
        Map<RequestMappingInfo, HandlerMethod> handlers = ControllerMappingRegistry.mapping().getHandlerMethods();
        handlers.forEach((info, handler) -> {
            for (MethodParameter parameter : handler.getMethodParameters()) {
                String name = boundName(parameter);
                if (name != null && IDENTIFIER_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                    found.add(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName() + " " + name);
                }
            }
        });
        return found;
    }

    @Test
    @DisplayName("every handler that names a personal identifier in its URL is allowlisted with a reason")
    void noHandlerTakesAPersonalIdentifierInItsUrl() {
        Set<String> offending = identifierParametersInUrls();
        offending.removeAll(ALLOWED.keySet());
        assertThat(offending)
            .as("move these identifiers into the body of a @ReadsOnly POST (or allowlist one with a reason)")
            .isEmpty();
    }

    @Test
    @DisplayName("every allowlist entry still matches a real handler, so a stale entry cannot hide a new one")
    void theAllowlistIsCurrent() {
        assertThat(identifierParametersInUrls()).containsAll(ALLOWED.keySet());
        assertThat(ALLOWED.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    @DisplayName("the registry really holds the application's GETs")
    void theRegistryIsTheApplications() {
        long gets = ControllerMappingRegistry.mapping().getHandlerMethods().keySet().stream()
            .filter(info -> info.getMethodsCondition().getMethods().contains(RequestMethod.GET))
            .count();
        assertThat(gets).isGreaterThan(500);
    }
}
