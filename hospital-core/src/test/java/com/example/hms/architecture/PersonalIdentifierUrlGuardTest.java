package com.example.hms.architecture;

import com.example.hms.testsupport.ControllerMappingRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

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
 * <p>This walks Spring's own mapping registry over every controller and fails on any GET handler
 * whose {@code @RequestParam} or {@code @PathVariable} carries one of those names, unless it is in
 * {@link #ALLOWED} with its reason. Free-text {@code search}/{@code q} boxes are not named here:
 * they are list filters across many surfaces, not identifier lookups.
 */
@DisplayName("No GET takes a personal identifier in its URL")
class PersonalIdentifierUrlGuardTest {

    /** Lower-cased parameter names that carry a personal identifier. */
    private static final Set<String> IDENTIFIER_NAMES = Set.of(
        "name", "firstname", "lastname", "middlename", "fullname", "patientname",
        "dob", "dateofbirth", "birthdate",
        "phone", "phonenumber", "mobile", "msisdn", "telephone",
        "email", "emailaddress",
        "mrn", "mri", "nationalid", "nationaluniqueid", "nui",
        "identifier", "username", "patientusername", "recipientusername");

    /** {@code Controller#method parameter} of a GET allowed one anyway, and why. A new entry needs a reason too. */
    private static final Map<String, String> ALLOWED = Map.of(
        "AuthController#verifyEmailViaLink email",
        "deprecated GET twin of POST /auth/verify-email, kept one release for the installed patient mobile apps;"
            + " the portal posts the body",
        "PermissionController#searchPermissions name",
        "a permission's name, not a person's");

    private static final ParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

    private static String boundName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        String declared;
        if (requestParam != null) {
            declared = requestParam.name().isEmpty() ? requestParam.value() : requestParam.name();
        } else if (pathVariable != null) {
            declared = pathVariable.name().isEmpty() ? pathVariable.value() : pathVariable.name();
        } else {
            return null;
        }
        if (!declared.isEmpty()) {
            return declared;
        }
        parameter.initParameterNameDiscovery(NAMES);
        return parameter.getParameterName();
    }

    private static Set<String> identifierParametersOfGets() {
        Set<String> found = new TreeSet<>();
        Map<RequestMappingInfo, HandlerMethod> handlers = ControllerMappingRegistry.mapping().getHandlerMethods();
        handlers.forEach((info, handler) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (!methods.isEmpty() && !methods.contains(RequestMethod.GET)) {
                return;
            }
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
    @DisplayName("every GET that names a personal identifier in its URL is allowlisted with a reason")
    void noGetTakesAPersonalIdentifierInItsUrl() {
        Set<String> offending = identifierParametersOfGets();
        offending.removeAll(ALLOWED.keySet());
        assertThat(offending)
            .as("move these identifiers into the body of a @ReadsOnly POST (or allowlist one with a reason)")
            .isEmpty();
    }

    @Test
    @DisplayName("every allowlist entry still matches a real GET, so a stale entry cannot hide a new one")
    void theAllowlistIsCurrent() {
        assertThat(identifierParametersOfGets()).containsAll(ALLOWED.keySet());
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
