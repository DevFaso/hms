package com.example.hms.architecture;

import com.example.hms.security.ReadsOnly;
import com.example.hms.testsupport.ControllerMappingRegistry;
import com.example.hms.validation.RejectsUnknownFields;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3753: {@code POST /patients/search} with a misspelt criterion ({@code {"bogus":"x"}}) answered
 * 200 with the hospital's first patients: Jackson skips a property the body does not declare, so the
 * search ran with no criterion at all. The query-string form had refused an unknown parameter.
 *
 * <p>Class: the body of a POST that only reads. Walking Spring's own mapping registry, every
 * {@code @ReadsOnly} POST whose {@code @RequestBody} is a search's criteria declares
 * {@link RejectsUnknownFields} with {@link RejectsUnknownFields.Kind#SEARCH}, so a criterion it does
 * not take is refused (400, naming the field and the criteria it accepts) instead of widening the
 * search. A body that is not a search's criteria is listed in {@link #NOT_SEARCH_CRITERIA} with why.
 */
@DisplayName("D3753: a search body refuses a criterion it does not take")
class SearchBodyStrictnessGuardTest {

    /** {@code Controller#method} of a read-only POST whose body is not a search's criteria, and why. */
    private static final Map<String, String> NOT_SEARCH_CRITERIA = Map.ofEntries(
        Map.entry("CdsHooksController#invoke", "a CDS Hooks request: the specification lets a caller add fields"),
        Map.entry("DigitalSignatureController#verifySignature", "a signature to verify, not a filter"),
        Map.entry("PatientController#getDoctorRecord", "the access reason of a chart read, not a filter"),
        Map.entry("PatientController#getDoctorTimeline", "the access reason of a chart read, not a filter"),
        Map.entry("PrenatalSchedulingController#generateSchedule", "the inputs of a schedule computation"),
        Map.entry("SuperAdminSecurityRuleController#simulatePolicyImpact", "a policy draft to simulate"),
        Map.entry("SuperAdminAuditSearchController#search",
            "refuses an unknown filter itself (@JsonAnySetter, error.request.unknownParameter)"),
        Map.entry("SuperAdminAuditSearchController#exportCsv",
            "refuses an unknown filter itself (@JsonAnySetter, error.request.unknownParameter)"));

    /** The read-only POSTs with a body, by {@code Controller#method}, and the class each body is read as. */
    private static Map<String, Class<?>> readOnlyPostBodies() {
        Map<String, Class<?>> bodies = new java.util.TreeMap<>();
        ControllerMappingRegistry.mapping().getHandlerMethods().forEach((info, handler) -> {
            if (!info.getMethodsCondition().getMethods().contains(RequestMethod.POST)
                    || !handler.hasMethodAnnotation(ReadsOnly.class)) {
                return;
            }
            for (MethodParameter parameter : handler.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(RequestBody.class)) {
                    bodies.put(name(handler), parameter.getParameterType());
                }
            }
        });
        return bodies;
    }

    private static String name(HandlerMethod handler) {
        return handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
    }

    private static boolean strictSearch(Class<?> type) {
        RejectsUnknownFields marker = type.getAnnotation(RejectsUnknownFields.class);
        return marker != null && marker.value() == RejectsUnknownFields.Kind.SEARCH;
    }

    @Test
    @DisplayName("every read-only POST's search body refuses an unknown criterion, or says why it is not one")
    void everySearchBodyRefusesAnUnknownCriterion() {
        Set<String> lenient = new TreeSet<>();
        readOnlyPostBodies().forEach((handler, type) -> {
            if (!strictSearch(type) && !NOT_SEARCH_CRITERIA.containsKey(handler)) {
                lenient.add(handler + " (" + type.getSimpleName() + ")");
            }
        });
        assertThat(lenient)
            .as("mark the body @RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH), or list the handler in"
                + " NOT_SEARCH_CRITERIA with why its body is not a search's criteria (QA D3753)")
            .isEmpty();
    }

    @Test
    @DisplayName("every exception still names a read-only POST with a body, and says why")
    void theExceptionsAreCurrent() {
        assertThat(readOnlyPostBodies().keySet()).containsAll(NOT_SEARCH_CRITERIA.keySet());
        assertThat(NOT_SEARCH_CRITERIA.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    @DisplayName("the walk sees the search bodies it guards")
    void theWalkSeesTheSearchBodies() {
        assertThat(readOnlyPostBodies()).containsKeys(
            "PatientController#searchPatients", "PatientController#termSearchPatients",
            "BillingInvoiceController#searchInvoices", "UserController#searchUsers");
    }
}
