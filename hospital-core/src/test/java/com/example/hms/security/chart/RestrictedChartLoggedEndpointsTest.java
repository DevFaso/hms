package com.example.hms.security.chart;

import com.example.hms.exception.ChartRestrictedException;
import com.example.hms.testsupport.ControllerMappingRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExecutionChain;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D3863: chart reads, matched against the application's REAL routes, go
 * through the guard. Each one is refused before its handler while the caller
 * holds no live break-the-glass session; the patient page header and the
 * break-the-glass flow stay open.
 */
@DisplayName("D3863: chart reads of a restricted chart are refused up front")
class RestrictedChartLoggedEndpointsTest {

    private static final UUID PATIENT = UUID.fromString("0d1e2f30-0000-4000-8000-000000000001");
    private static final UUID ENCOUNTER = UUID.fromString("0d1e2f30-0000-4000-8000-000000000002");

    private RestrictedChartGate gate;
    private RestrictedChartGuardInterceptor interceptor;

    @BeforeEach
    void setUp() {
        gate = mock(RestrictedChartGate.class);
        when(gate.isSubject()).thenReturn(true);
        when(gate.ownerOf(eq(ChartKeys.Owner.PATIENT), any())).thenAnswer(i -> Optional.of(i.getArgument(1)));
        when(gate.ownerOf(ChartKeys.Owner.ENCOUNTER, ENCOUNTER)).thenReturn(Optional.of(PATIENT));
        doThrow(new ChartRestrictedException(PATIENT)).when(gate).requireReadable(PATIENT);
        @SuppressWarnings("unchecked")
        ObjectProvider<RestrictedChartGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gate);
        interceptor = new RestrictedChartGuardInterceptor(provider);
    }

    private boolean preHandle(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri.split("\\?")[0]);
        if (uri.contains("?")) {
            for (String pair : uri.split("\\?")[1].split("&")) {
                String[] kv = pair.split("=");
                request.addParameter(kv[0], kv[1]);
            }
        }
        HandlerExecutionChain chain = ControllerMappingRegistry.mapping().getHandler(request);
        assertThat(chain).as("a real route serves " + uri).isNotNull();
        return interceptor.preHandle(request, new MockHttpServletResponse(), chain.getHandler());
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {
        "/encounters/0d1e2f30-0000-4000-8000-000000000002",
        "/encounters/0d1e2f30-0000-4000-8000-000000000002/notes/history",
        "/encounters/0d1e2f30-0000-4000-8000-000000000002/avs",
        "/encounters?patientId=0d1e2f30-0000-4000-8000-000000000001",
        "/patients/0d1e2f30-0000-4000-8000-000000000001/allergies",
        "/prescriptions?patientId=0d1e2f30-0000-4000-8000-000000000001",
        "/nurse/notes?patientId=0d1e2f30-0000-4000-8000-000000000001&hospitalId=0d1e2f30-0000-4000-8000-000000000003",
        "/patients/0d1e2f30-0000-4000-8000-000000000001/vitals/recent",
        "/patients/0d1e2f30-0000-4000-8000-000000000001/storyboard",
        "/discharge-summaries/patient/0d1e2f30-0000-4000-8000-000000000001",
        "/referrals/patient/0d1e2f30-0000-4000-8000-000000000001",
        "/mortality/patients/0d1e2f30-0000-4000-8000-000000000001/voided-closures",
        "/order-review-holds/patient/0d1e2f30-0000-4000-8000-000000000001",
        "/patients/0d1e2f30-0000-4000-8000-000000000001/record.pdf",
    })
    @DisplayName("refused without a live session")
    void refused(String uri) {
        assertThatThrownBy(() -> preHandle(uri)).isInstanceOf(ChartRestrictedException.class);
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {
        "/patients/0d1e2f30-0000-4000-8000-000000000001",
        "/break-glass/me?patientId=0d1e2f30-0000-4000-8000-000000000001",
        "/break-glass/active?patientId=0d1e2f30-0000-4000-8000-000000000001",
    })
    @DisplayName("the identity header and the break-the-glass flow stay open")
    void open(String uri) throws Exception {
        assertThat(preHandle(uri)).isTrue();
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {
        "/encounters/0d1e2f30-0000-4000-8000-000000000002",
        "/patients/0d1e2f30-0000-4000-8000-000000000001/allergies",
    })
    @DisplayName("open under a live session (the gate lets the chart through)")
    void openUnderBreakGlass(String uri) throws Exception {
        org.mockito.Mockito.reset(gate);
        when(gate.isSubject()).thenReturn(true);
        when(gate.ownerOf(any(), any())).thenReturn(Optional.of(PATIENT));
        assertThat(preHandle(uri)).isTrue();
    }
}
