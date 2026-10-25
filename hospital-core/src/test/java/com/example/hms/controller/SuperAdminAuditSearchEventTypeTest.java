package com.example.hms.controller;

import com.example.hms.enums.AuditEventType;
import com.example.hms.payload.dto.superadmin.AuditSearchFilter;
import com.example.hms.payload.dto.superadmin.AuditSearchPageDTO;
import com.example.hms.service.SuperAdminAuditAggregationService;
import com.example.hms.service.SuperAdminAuditSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D798: {@code GET /super-admin/audit-search?eventType=USER_DELETE} came
 * back 200 with every event type, because only the plural {@code eventTypes}
 * was bound. The singular form now filters (on the JSON search and on the CSV
 * export), merges with the plural, and an unknown value is a 400.
 */
@ExtendWith(MockitoExtension.class)
class SuperAdminAuditSearchEventTypeTest {

    @Mock
    private SuperAdminAuditSearchService service;
    @Mock
    private SuperAdminAuditAggregationService aggregationService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SuperAdminAuditSearchController(service, aggregationService))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    }

    private AuditSearchFilter searchedWith() {
        ArgumentCaptor<AuditSearchFilter> filter = ArgumentCaptor.forClass(AuditSearchFilter.class);
        verify(service).search(filter.capture(), any(Pageable.class));
        return filter.getValue();
    }

    /** The exact round-12 call. */
    @Test
    void theSingularEventTypeFilters() throws Exception {
        when(service.search(any(), any())).thenReturn(new AuditSearchPageDTO());

        mvc.perform(get("/super-admin/audit-search").param("size", "5").param("eventType", "USER_DELETE"))
            .andExpect(status().isOk());

        assertThat(searchedWith().eventTypes()).containsExactly(AuditEventType.USER_DELETE);
    }

    @Test
    void theSingularAndThePluralAreMerged() throws Exception {
        when(service.search(any(), any())).thenReturn(new AuditSearchPageDTO());

        mvc.perform(get("/super-admin/audit-search")
                .param("eventTypes", "USER_CREATE", "USER_DELETE").param("eventType", "LOGIN"))
            .andExpect(status().isOk());

        assertThat(searchedWith().eventTypes())
            .containsExactlyInAnyOrder(AuditEventType.USER_CREATE, AuditEventType.USER_DELETE, AuditEventType.LOGIN);
    }

    @Test
    void thePluralAloneIsUnchanged() throws Exception {
        when(service.search(any(), any())).thenReturn(new AuditSearchPageDTO());

        mvc.perform(get("/super-admin/audit-search").param("eventTypes", "USER_DELETE"))
            .andExpect(status().isOk());

        assertThat(searchedWith().eventTypes()).containsExactly(AuditEventType.USER_DELETE);
    }

    @Test
    void noEventTypeMeansNoFilter() throws Exception {
        when(service.search(any(), any())).thenReturn(new AuditSearchPageDTO());

        mvc.perform(get("/super-admin/audit-search")).andExpect(status().isOk());

        assertThat(searchedWith().eventTypes()).isNull();
    }

    @Test
    void anUnknownEventTypeIsABadRequestNotEveryRow() throws Exception {
        mvc.perform(get("/super-admin/audit-search").param("eventType", "NOT_A_TYPE"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void theCsvExportHonoursTheSingularEventType() throws Exception {
        when(service.exportCsv(any(), anyInt())).thenReturn(new byte[0]);

        mvc.perform(get("/super-admin/audit-search/csv").param("eventType", "USER_DELETE"))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditSearchFilter> filter = ArgumentCaptor.forClass(AuditSearchFilter.class);
        verify(service).exportCsv(filter.capture(), anyInt());
        assertThat(filter.getValue().eventTypes()).containsExactly(AuditEventType.USER_DELETE);
    }
}
