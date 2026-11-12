package com.example.hms.service.integration;

import com.example.hms.model.integration.Dhis2AuthMode;
import com.example.hms.model.integration.Dhis2ExportRun;
import com.example.hms.model.integration.Dhis2FacilityConfig;
import com.example.hms.model.integration.Dhis2PeriodType;
import com.example.hms.repository.integration.Dhis2FacilityConfigRepository;
import com.example.hms.service.integration.impl.Dhis2ExportRunPersistence;
import com.example.hms.service.integration.impl.DhisAdxExportServiceImpl;
import com.example.hms.service.support.OutboundUrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** Real export orchestration, XML writer, destination checks and HTTP adapter; no database or sockets. */
class DhisAdxPushContractIntegrationTest {

    private static final String BASE_URL = "https://cdx-dhis.example.invalid";
    private final UUID hospitalId = UUID.randomUUID();
    private final Dhis2ExportRun run = Dhis2ExportRun.builder().requestId(UUID.randomUUID()).build();
    private Dhis2ExportRunPersistence persistence;
    private Dhis2FacilityConfig config;
    private DhisAdxExportServiceImpl service;
    private MockRestServiceServer receiver;
    private MockedStatic<OutboundUrlGuard> guard;
    private byte[] dnsAnswer = new byte[] {93, (byte) 184, (byte) 216, 34};
    private int lookups;

    @BeforeEach
    void setUp() {
        var repository = mock(Dhis2FacilityConfigRepository.class);
        var aggregator = mock(DhisAdxAggregator.class);
        persistence = mock(Dhis2ExportRunPersistence.class);
        var builder = RestClient.builder();
        receiver = MockRestServiceServer.bindTo(builder).build();
        var client = new DhisHttpClient(builder.build(), variable -> {
            assertThat(variable).isEqualTo("CDX_FAKE_DHIS_TOKEN");
            return "cdx-fake-token";
        });
        service = new DhisAdxExportServiceImpl(repository, persistence, aggregator,
            new DhisAdxXmlWriter(), client);
        config = Dhis2FacilityConfig.builder().baseUrl(BASE_URL).authMode(Dhis2AuthMode.PAT)
            .authSecretEnvVar("CDX_FAKE_DHIS_TOKEN").defaultPeriodType(Dhis2PeriodType.MONTHLY).build();
        when(repository.findByHospital_IdAndActiveTrue(hospitalId)).thenReturn(Optional.of(config));
        when(aggregator.aggregateImmunizations(eq(hospitalId), eq("cdxDataset1"),
            eq(Dhis2PeriodType.MONTHLY), any(), any())).thenReturn(new DhisAdxAggregator.AggregationResult(
                List.of(new AggregatedDataValue("cdxOrgUnit1", "cdxElement1", "cdxCategory", "7"),
                    new AggregatedDataValue("cdxOrgUnit1", "cdxElement2", null, "0")), 0, "cdxOrgUnit1"));
        when(persistence.persistPending(any(), any(), any(), any(), any())).thenReturn(run);
        // Replace only DNS resolution; all URL and address classification code remains real.
        // getByAddress constructs an address from bytes and cannot perform a lookup.
        guard = mockStatic(OutboundUrlGuard.class, invocation -> {
            if (invocation.getMethod().getName().equals("resolvePublic")) {
                lookups++;
                String host = invocation.getArgument(0);
                return OutboundUrlGuard.requireAllPublic(host,
                    new InetAddress[] {InetAddress.getByAddress(host, dnsAnswer)});
            }
            return invocation.callRealMethod();
        });
    }

    @AfterEach
    void close() {
        try {
            receiver.verify();
        } finally {
            guard.close();
        }
    }

    private Dhis2ExportRun trigger() {
        return service.triggerImmunizationsExport(hospitalId, "cdxDataset1",
            Dhis2PeriodType.MONTHLY, "202610", null);
    }

    @ParameterizedTest
    @CsvSource({"200,0", "200,1", "401,0"})
    void receiverGetsAdxAggregateAndResponseReachesReconciliation(int status, int ignored) {
        receiver.expect(requestTo(BASE_URL + "/api/dataValueSets"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Content-Type", "application/adx+xml"))
            .andExpect(header("Accept", "application/json"))
            .andExpect(header("Authorization", "ApiToken cdx-fake-token"))
            .andExpect(header("X-Request-Id", run.getRequestId().toString()))
            .andExpect(request -> assertAdx(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8)))
            .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                .body("{\"response\":{\"importCount\":{\"imported\":2,\"ignored\":" + ignored + "}}}"));
        when(persistence.finalizeReconciled(eq(run), eq(config), any())).thenAnswer(invocation -> {
            DhisHttpResponse response = invocation.getArgument(2);
            assertThat(response.httpStatus()).isEqualTo(status);
            assertThat(response.importedCount()).isEqualTo(2);
            assertThat(response.ignoredCount()).isEqualTo(ignored);
            return run;
        });
        assertThat(trigger()).isSameAs(run);
        assertThat(lookups).isEqualTo(1);
        verify(persistence).finalizeReconciled(eq(run), eq(config), any());
        verify(persistence, never()).finalizeFailed(any(), eq(0), anyString());
    }

    private void assertAdx(String xml) {
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
                .getDocumentElement();
            assertThat(root.getLocalName()).isEqualTo("adx");
            assertThat(root.getNamespaceURI()).isEqualTo("urn:ihe:qrph:adx:2015");
            assertThat(root.getAttribute("exported")).isNotBlank();
            Element group = (Element) root.getFirstChild();
            assertThat(group.getAttribute("orgUnit")).isEqualTo("cdxOrgUnit1");
            assertThat(group.getAttribute("period")).isEqualTo("202610");
            assertThat(group.getAttribute("dataSet")).isEqualTo("cdxDataset1");
            var values = group.getElementsByTagNameNS("urn:ihe:qrph:adx:2015", "dataValue");
            assertThat(values.getLength()).isEqualTo(2);
            Element first = (Element) values.item(0);
            assertThat(first.getAttribute("dataElement")).isEqualTo("cdxElement1");
            assertThat(first.getAttribute("value")).isEqualTo("7");
            assertThat(first.getAttribute("categoryOptionCombo")).isEqualTo("cdxCategory");
            Element second = (Element) values.item(1);
            assertThat(second.getAttribute("dataElement")).isEqualTo("cdxElement2");
            assertThat(second.getAttribute("value")).isEqualTo("0");
            assertThat(second.hasAttribute("categoryOptionCombo")).isFalse();
            assertThat(xml).doesNotContain(hospitalId.toString(), "patient", "name=", "birthDate");
        } catch (Exception ex) {
            throw new AssertionError("Receiver could not parse ADX payload", ex);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://cdx-dhis.example.invalid", "https://localhost/dhis",
        "https://cdx.internal/dhis", "https://user:pass@cdx-dhis.example.invalid"})
    void invalidDestinationNeverReachesReceiver(String unsafe) {
        config.setBaseUrl(unsafe);
        when(persistence.finalizeFailed(eq(run), eq(0), anyString())).thenReturn(run);
        assertThat(trigger()).isSameAs(run);
        assertThat(lookups).isZero();
        verify(persistence).finalizeFailed(eq(run), eq(0), anyString());
        verify(persistence, never()).finalizeReconciled(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.5", "169.254.169.254", "192.168.1.4"})
    void publicLookingNameResolvingInsideNeverReachesReceiver(String answer) throws Exception {
        dnsAnswer = InetAddress.getByName(answer).getAddress(); // numeric literals only; no DNS
        when(persistence.finalizeFailed(eq(run), eq(0), anyString())).thenReturn(run);
        assertThat(trigger()).isSameAs(run);
        assertThat(lookups).isEqualTo(1);
        verify(persistence).finalizeFailed(eq(run), eq(0), anyString());
        verify(persistence, never()).finalizeReconciled(any(), any(), any());
    }
}
