package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.controller.support.StrictQueryParamsInterceptor;
import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.superadmin.AuditSearchFilter;
import com.example.hms.payload.dto.superadmin.AuditSearchPageDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.AuditEventLogSpecifications;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.SuperAdminAuditAggregationService;
import com.example.hms.service.SuperAdminAuditSearchService;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D798 / D801, the round-12 re-test ({@code a2.js}): the singular
 * {@code eventType} was fixed on {@code /super-admin/audit-search}, but
 * {@code GET /audit-logs?size=200&eventTypes=INVOICE_UPDATED} still answered
 * 200 with every row (hadmA 1671, hadmB 278, super 3148) — the controller
 * bound only the singular, and Spring drops a parameter nothing binds.
 *
 * <p>Both endpoints now read the filter through one parser (either name,
 * repeated or comma-separated, any match), refuse an unknown value with a
 * translated 400 naming it, and refuse a parameter they do not declare
 * instead of ignoring it. Each call below is one {@code a2.js} made, through
 * the real MVC stack: the interceptor, the binder and the exception handler.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Audit event-type filter, the round-12 re-test calls")
class AuditEventTypeParamsReplayTest {

    @BeforeEach
    void realBundles() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        LocaleContextHolder.resetLocaleContext();
    }

    @Nested
    @DisplayName("GET /audit-logs")
    class AuditLogs {

        @Mock private AuditEventLogService auditService;
        @Mock private AuditEventLogRepository auditRepository;
        @Mock private AuditEventLogMapper auditMapper;
        @Mock private ControllerAuthUtils authUtils;
        @Mock private ActingScopeResolver scopeResolver;

        private MockMvc mvc;
        private final UUID hospitalA = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            mvc = MockMvcBuilders.standaloneSetup(
                    new AuditEventLogController(auditService, auditRepository, auditMapper, authUtils, scopeResolver))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .addInterceptors(new StrictQueryParamsInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
            lenient().when(auditRepository.findAll(anySpec(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.<AuditEventLog>of(), PageRequest.of(0, 20), 0));
        }

        @SuppressWarnings("unchecked")
        private Specification<AuditEventLog> anySpec() {
            return any(Specification.class);
        }

        private void asHospitalAdminA() {
            HospitalContextHolder.setContext(HospitalContext.builder()
                .hospitalAdmin(true).activeHospitalId(hospitalA)
                .permittedHospitalIds(Set.of(hospitalA)).staffHospitalIds(Set.of(hospitalA)).build());
            when(scopeResolver.current()).thenReturn(new ActingScope.Pinned(hospitalA, ActingScope.Source.SOLE_ASSIGNMENT));
        }

        private void asSuperAdminInGlobalView() {
            HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
            when(scopeResolver.current()).thenReturn(new ActingScope.Global(UUID.randomUUID()));
        }

        /** The exact re-test call, as hadmA: the plural reaches the query. */
        @Test
        void thePluralFiltersForAHospitalAdmin() throws Exception {
            asHospitalAdminA();
            try (MockedStatic<AuditEventLogSpecifications> specs =
                     Mockito.mockStatic(AuditEventLogSpecifications.class, Mockito.CALLS_REAL_METHODS)) {
                mvc.perform(get("/audit-logs").param("size", "200").param("eventTypes", "INVOICE_UPDATED"))
                    .andExpect(status().isOk());

                specs.verify(() -> AuditEventLogSpecifications.ofTypes(EnumSet.of(AuditEventType.INVOICE_UPDATED)));
                specs.verify(() -> AuditEventLogSpecifications.anchoredAt(Set.of(hospitalA)));
            }
        }

        /**
         * The exact re-test call, as super: before the fix this fell through
         * to the unfiltered {@code findAll(pageable)} — every row of the platform.
         */
        @Test
        void thePluralFiltersTheWholePlatformLogForASuperAdmin() throws Exception {
            asSuperAdminInGlobalView();
            try (MockedStatic<AuditEventLogSpecifications> specs =
                     Mockito.mockStatic(AuditEventLogSpecifications.class, Mockito.CALLS_REAL_METHODS)) {
                mvc.perform(get("/audit-logs").param("size", "200").param("eventTypes", "INVOICE_UPDATED"))
                    .andExpect(status().isOk());

                specs.verify(() -> AuditEventLogSpecifications.ofTypes(EnumSet.of(AuditEventType.INVOICE_UPDATED)));
            }
            verify(auditRepository).findAll(anySpec(), any(Pageable.class));
            verify(auditRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        void theSingularStillFiltersAndBothNamesMerge() throws Exception {
            asHospitalAdminA();
            try (MockedStatic<AuditEventLogSpecifications> specs =
                     Mockito.mockStatic(AuditEventLogSpecifications.class, Mockito.CALLS_REAL_METHODS)) {
                mvc.perform(get("/audit-logs").param("eventType", "USER_DELETE")
                        .param("eventTypes", "USER_CREATE,login").param("eventTypes", "INVOICE_UPDATED"))
                    .andExpect(status().isOk());

                specs.verify(() -> AuditEventLogSpecifications.ofTypes(EnumSet.of(
                    AuditEventType.USER_DELETE, AuditEventType.USER_CREATE,
                    AuditEventType.LOGIN, AuditEventType.INVOICE_UPDATED)));
            }
        }

        @Test
        void anUnknownPluralValueIsATranslatedBadRequest() throws Exception {
            mvc.perform(get("/audit-logs").param("eventTypes", "BOGUS").header("Accept-Language", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Type d'événement d'audit inconnu : BOGUS")));
            verifyNoInteractions(auditRepository, scopeResolver);
        }

        /** A parameter nothing binds used to vanish; now it is named back to the caller. */
        @Test
        void anUnknownParameterIsATranslatedBadRequestNotEveryRow() throws Exception {
            mvc.perform(get("/audit-logs").param("size", "200").param("eventTypez", "INVOICE_UPDATED")
                    .header("Accept-Language", "es"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("El parámetro «eventTypez» no se acepta aquí")))
                .andExpect(jsonPath("$.message", containsString("eventTypes")));
            verifyNoInteractions(auditRepository, scopeResolver);
        }

        /** Sibling: a filter the user-activity read does not have is refused, not silently dropped. */
        @Test
        void aFilterTheUserActivityReadDoesNotOfferIsRefused() throws Exception {
            mvc.perform(get("/audit-logs/user/{id}", UUID.randomUUID()).param("eventTypes", "LOGIN")
                    .header("Accept-Language", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("The parameter \"eventTypes\" is not accepted here")));
            verifyNoInteractions(auditService, auditRepository);
        }

        /** Sibling: the event-type endpoint takes either name, and none at all is not "every row". */
        @Test
        void theEventTypeStatusEndpointTakesThePlural() throws Exception {
            asHospitalAdminA();
            try (MockedStatic<AuditEventLogSpecifications> specs =
                     Mockito.mockStatic(AuditEventLogSpecifications.class, Mockito.CALLS_REAL_METHODS)) {
                mvc.perform(get("/audit-logs/event-type-status").param("eventTypes", "INVOICE_UPDATED"))
                    .andExpect(status().isOk());
                specs.verify(() -> AuditEventLogSpecifications.ofTypes(
                    EnumSet.of(AuditEventType.INVOICE_UPDATED), null));
            }

            mvc.perform(get("/audit-logs/event-type-status").header("Accept-Language", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Indiquez au moins un type")));
        }

        @Test
        void thePagingParametersTheUiSendsAreAccepted() throws Exception {
            asHospitalAdminA();
            mvc.perform(get("/audit-logs").param("page", "0").param("size", "20").param("sort", "eventTimestamp,desc")
                    .param("fromDate", "2026-10-01T00:00:00").param("toDate", "2026-10-25T23:59:59")
                    .param("eventType", "USER_CREATE").param("search", "Multi91064"))
                .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /super-admin/audit-search")
    class SuperAdminSearch {

        @Mock private SuperAdminAuditSearchService service;
        @Mock private SuperAdminAuditAggregationService aggregationService;

        private MockMvc mvc;

        @BeforeEach
        void setUp() {
            mvc = MockMvcBuilders.standaloneSetup(new SuperAdminAuditSearchController(service, aggregationService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .addInterceptors(new StrictQueryParamsInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        }

        private AuditSearchFilter searchedWith() {
            ArgumentCaptor<AuditSearchFilter> filter = ArgumentCaptor.forClass(AuditSearchFilter.class);
            verify(service).search(filter.capture(), any(Pageable.class));
            return filter.getValue();
        }

        /** a2.js: {@code eventTypes=USER_DELETE,USER_CREATE} — a comma list is two types. */
        @Test
        void aCommaSeparatedPluralIsEveryTypeNamed() throws Exception {
            when(service.search(any(), any())).thenReturn(new AuditSearchPageDTO());

            mvc.perform(get("/super-admin/audit-search").param("size", "200").param("eventTypes", "USER_DELETE,USER_CREATE"))
                .andExpect(status().isOk());

            assertThat(searchedWith().eventTypes())
                .containsExactlyInAnyOrder(AuditEventType.USER_DELETE, AuditEventType.USER_CREATE);
        }

        /** a2.js: a comma list in the singular is read the same way. */
        @Test
        void aCommaSeparatedSingularIsEveryTypeNamed() throws Exception {
            when(service.search(any(), any())).thenReturn(new AuditSearchPageDTO());

            mvc.perform(get("/super-admin/audit-search").param("eventType", "USER_DELETE, USER_CREATE"))
                .andExpect(status().isOk());

            assertThat(searchedWith().eventTypes())
                .containsExactlyInAnyOrder(AuditEventType.USER_DELETE, AuditEventType.USER_CREATE);
        }

        /**
         * a2.js: {@code eventType=USER_DELETE&eventTypes=LOGIN_SUCCESS}. The two
         * names are not in conflict (they merge); LOGIN_SUCCESS is simply not
         * an event type, and the 400 now says so instead of "eventTypes is not valid".
         */
        @Test
        void bothNamesWithOneUnknownValueNamesTheValue() throws Exception {
            mvc.perform(get("/super-admin/audit-search").param("size", "200")
                    .param("eventType", "USER_DELETE").param("eventTypes", "LOGIN_SUCCESS")
                    .header("Accept-Language", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Unknown audit event type: LOGIN_SUCCESS")));
            verifyNoInteractions(service);
        }

        @Test
        void anUnknownParameterIsRefusedOnTheSearchAndTheExport() throws Exception {
            mvc.perform(get("/super-admin/audit-search").param("eventtype", "USER_DELETE"))
                .andExpect(status().isBadRequest());
            mvc.perform(get("/super-admin/audit-search/csv").param("type", "USER_DELETE"))
                .andExpect(status().isBadRequest());
            mvc.perform(get("/super-admin/audit-search/aggregated").param("eventTypes", "USER_DELETE"))
                .andExpect(status().isBadRequest());
            verifyNoInteractions(service, aggregationService);
        }

        @Test
        void theExportTakesTheSameFilter() throws Exception {
            when(service.exportCsv(any(), anyInt())).thenReturn(new byte[0]);

            mvc.perform(get("/super-admin/audit-search/csv").param("eventTypes", "USER_DELETE,INVOICE_UPDATED")
                    .param("maxRows", "100"))
                .andExpect(status().isOk());

            ArgumentCaptor<AuditSearchFilter> filter = ArgumentCaptor.forClass(AuditSearchFilter.class);
            verify(service).exportCsv(filter.capture(), anyInt());
            assertThat(filter.getValue().eventTypes())
                .containsExactlyInAnyOrder(AuditEventType.USER_DELETE, AuditEventType.INVOICE_UPDATED);
        }
    }
}
