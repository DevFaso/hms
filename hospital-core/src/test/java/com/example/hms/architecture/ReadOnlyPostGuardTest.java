package com.example.hms.architecture;

import com.example.hms.security.DowntimeStateService;
import com.example.hms.security.ReadOnlyModeFilter;
import com.example.hms.security.ReadsOnly;
import com.example.hms.testsupport.ControllerMappingRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D1404: POST /billing-invoices/search answered 503 in read-only mode, and the
 * billing page, under a banner promising that viewing works, was empty.
 *
 * <p>Class: a POST that only reads, refused as a write. This walks Spring's own
 * mapping registry over every controller. A POST whose method or route says it
 * reads must declare {@link ReadsOnly}, or be listed in {@link #WRITES_DESPITE_THE_NAME}
 * with what it writes. Then every POST of the application goes through the real
 * filter in read-only mode, in both directions: a declared read passes, and
 * everything else is refused unless it sits under the allowlisted prefixes.
 */
@DisplayName("D1404: read-only mode lets every read through and refuses every write")
class ReadOnlyPostGuardTest {

    private static final Pattern READ_WORD = Pattern.compile(
        "(?i)^(search|filter|find|lookup|query|fetch|list|get|read|view|preview|validate|validation|calculate|compute"
            + "|check|verify|estimate|simulate|simulation|simulations|evaluate|count|candidates|candidate|match"
            + "|suggest|resolve|export|report|generate|summary|invoke)$");

    /** Controller#method of a POST named as a read that changes something, and what it changes. */
    private static final Map<String, String> WRITES_DESPITE_THE_NAME = Map.ofEntries(
        Map.entry("EncounterController#checkOut", "checks the patient out"),
        Map.entry("LabResultController#readBackCriticalValue", "records the read-back"),
        Map.entry("LabTestValidationStudyController#create", "creates a validation study"),
        Map.entry("ReceptionController#checkInPatient", "checks the patient in"),
        Map.entry("SlotInventoryController#generate", "creates slots"),
        Map.entry("UltrasoundController#createOrUpdateReport", "stores the report"),
        Map.entry("EligibilityController#checkCoverage", "persists the eligibility check and answers 201"),
        Map.entry("MaternalHistoryController#calculateRiskScore", "stores the score on the maternal history"),
        Map.entry("NurseTaskController#verifyMedication", "records the pharmacist's verification on the task"),
        Map.entry("PrescriptionController#resolveClarification", "closes the clarification on the order"),
        Map.entry("SuperAdminProviderController#verify", "marks the provider verified"),
        Map.entry("UserRoleHospitalAssignmentController#verifyAssignmentByCode", "confirms the assignment"),
        Map.entry("UserRoleHospitalAssignmentController#verifyAssignmentByPhone", "activates the assignment"),
        Map.entry("Dhis2ExportController#trigger", "starts an export run and stores its result"),
        Map.entry("ReportController#runNow", "stores a report run"),
        Map.entry("InstrumentOutboxController#retryMessage", "re-queues an outbox row"),
        Map.entry("AuthController#verifyEmail", "activates the account (under /auth/, open in downtime)"),
        Map.entry("AuthController#verifyPassword", "records the step-up (under /auth/, open in downtime)"),
        Map.entry("AuthController#verifyRecoveryContact", "marks the contact verified (under /auth/, open in downtime)"),
        Map.entry("MfaController#verifyMfa", "completes the login (under /auth/, open in downtime)"),
        Map.entry("MfaController#verifyEnrollment", "enrols the factor (under /auth/, open in downtime)"));

    /** Every declared read, so that declaring a write as a read needs this list to change too: review sees it. */
    private static final Set<String> DECLARED_READS = Set.of(
        "AppointmentController#searchAppointments",
        "BillingInvoiceController#searchInvoices",
        "CdsHooksController#invoke",
        "DepartmentController#filterDepartments",
        "DigitalSignatureController#verifySignature",
        "EmpiProbabilisticController#candidates",
        "PatientController#getDoctorRecord",
        "PatientController#getDoctorTimeline",
        "PrenatalSchedulingController#generateSchedule",
        "SuperAdminSecurityRuleController#simulatePolicyImpact");

    private record Post(RequestMappingInfo info, HandlerMethod handler) {
        String name() {
            return handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
        }

        boolean declaredRead() {
            return handler.hasMethodAnnotation(ReadsOnly.class);
        }

        String path() {
            return info.getPathPatternsCondition().getPatternValues().iterator().next()
                .replaceAll("\\{[^/}]*}", "00000000-0000-0000-0000-000000000001");
        }

        boolean namedAsRead() {
            String pattern = info.getPathPatternsCondition().getPatternValues().iterator().next();
            String[] segments = pattern.split("/");
            String tail = "";
            for (int i = segments.length - 1; i >= 0 && tail.isEmpty(); i--) {
                tail = segments[i].startsWith("{") ? "" : segments[i];
            }
            String firstOfTail = tail.split("-")[0];
            String firstOfMethod = handler.getMethod().getName().split("(?=[A-Z])")[0];
            return READ_WORD.matcher(firstOfTail).matches() || READ_WORD.matcher(firstOfMethod).matches();
        }
    }

    private static List<Post> posts() {
        List<Post> posts = new ArrayList<>();
        ControllerMappingRegistry.mapping().getHandlerMethods().forEach((info, handler) -> {
            if (info.getMethodsCondition().getMethods().contains(RequestMethod.POST)
                    && info.getPathPatternsCondition() != null) {
                posts.add(new Post(info, handler));
            }
        });
        return posts;
    }

    @Test
    @DisplayName("the registry really holds the application's POSTs")
    void theRegistryIsTheApplications() {
        assertThat(posts()).hasSizeGreaterThan(350);
    }

    @Test
    @DisplayName("a POST named as a read declares @ReadsOnly, or is listed with what it writes")
    void aPostNamedAsAReadSaysWhichItIs() {
        Set<String> undeclared = new TreeSet<>();
        Set<String> stale = new TreeSet<>(WRITES_DESPITE_THE_NAME.keySet());
        for (Post post : posts()) {
            stale.remove(post.name());
            if (post.namedAsRead() && !post.declaredRead() && !WRITES_DESPITE_THE_NAME.containsKey(post.name())) {
                undeclared.add(post.name());
            }
        }
        assertThat(undeclared).as("add @ReadsOnly, or WRITES_DESPITE_THE_NAME with what it writes").isEmpty();
        assertThat(stale).as("listed, and no such POST any more").isEmpty();
    }

    @Test
    @DisplayName("the declared reads are exactly the reviewed list, and a read says why")
    void theDeclaredReadsAreReviewed() {
        Set<String> declared = new TreeSet<>();
        for (Post post : posts()) {
            if (post.declaredRead()) {
                declared.add(post.name());
                assertThat(post.handler().getMethodAnnotation(ReadsOnly.class).value()).as(post.name()).isNotBlank();
            }
        }
        assertThat(declared).isEqualTo(new TreeSet<>(DECLARED_READS));
    }

    @Test
    @DisplayName("through the real filter: every declared read passes, every other POST is refused")
    void everyPostOfTheApplicationInBothDirections() throws Exception {
        ReadOnlyModeFilter filter = readOnlyFilter();
        List<String> wrong = new ArrayList<>();
        for (Post post : posts()) {
            String uri = "/api" + post.path();
            boolean open = uri.startsWith("/api/auth/") || uri.startsWith("/api/frontend-audit");
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            request.setContextPath("/api");
            request.setContentType("application/json");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            boolean refused = response.getStatus() == 503;
            if (refused == (post.declaredRead() || open)) {
                wrong.add(post.name() + " " + uri + " -> " + response.getStatus());
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("the lookup leaves nothing on the request: the dispatcher meets it as if nobody had looked")
    void theLookupLeavesTheRequestUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/billing-invoices/search");
        request.setContextPath("/api");
        request.setContentType("application/json");
        readOnlyFilter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)).isNull();
        assertThat(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE)).isNull();
    }

    @SuppressWarnings("unchecked")
    private static ReadOnlyModeFilter readOnlyFilter() {
        DowntimeStateService downtime = mock(DowntimeStateService.class);
        when(downtime.snapshot()).thenReturn(new DowntimeStateService.DowntimeSnapshot(true, null, null));
        ObjectProvider<HandlerMapping> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ControllerMappingRegistry.mapping());
        return new ReadOnlyModeFilter(downtime, provider, new StaticMessageSource(), new FixedLocaleResolver(Locale.FRENCH));
    }
}
