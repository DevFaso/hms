package com.example.hms.security.chart;

import com.example.hms.exception.ChartRestrictedException;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.EncounterResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("D3863: every JSON answer is refused or masked for a restricted chart")
class RestrictedChartResponseMaskingTest {

    private static final UUID RESTRICTED = UUID.randomUUID();
    private static final UUID OPEN = UUID.randomUUID();

    private RestrictedChartGate gate;
    private RestrictedChartResponseMasking advice;

    /** Stand-ins for controller handlers. */
    static class Handlers {
        public EncounterResponseDTO single() {
            return null;
        }

        public List<EncounterResponseDTO> list() {
            return null;
        }

        @RestrictedChartPolicy(value = RestrictedChartPolicy.Mode.MASK, reason = "test")
        public EncounterResponseDTO masked() {
            return null;
        }

        @RestrictedChartPolicy(value = RestrictedChartPolicy.Mode.NONE, reason = "test")
        public EncounterResponseDTO none() {
            return null;
        }

        @ExceptionHandler(RuntimeException.class)
        public Map<String, Object> error() {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        gate = mock(RestrictedChartGate.class);
        when(gate.isSubject()).thenReturn(true);
        when(gate.anyRestricted()).thenReturn(true);
        when(gate.blockedAmong(anyCollection())).thenAnswer(i -> {
            java.util.Collection<?> ids = i.getArgument(0);
            return ids.contains(RESTRICTED) ? Set.of(RESTRICTED) : Set.of();
        });
        when(gate.refusal(RESTRICTED)).thenReturn(new ChartRestrictedException(RESTRICTED));
        @SuppressWarnings("unchecked")
        ObjectProvider<RestrictedChartGate> gates = mock(ObjectProvider.class);
        when(gates.getIfAvailable()).thenReturn(gate);
        @SuppressWarnings("unchecked")
        ObjectProvider<ObjectMapper> mappers = mock(ObjectProvider.class);
        when(mappers.getIfAvailable()).thenReturn(JsonMapper.builder().findAndAddModules().build());
        advice = new RestrictedChartResponseMasking(gates, mappers);
    }

    private static MethodParameter returnOf(String method) throws NoSuchMethodException {
        return new MethodParameter(Handlers.class.getMethod(method), -1);
    }

    private Object write(Object body, String method, String verb) throws NoSuchMethodException {
        MethodParameter returnType = returnOf(method);
        if (!advice.supports(returnType, JacksonJsonHttpMessageConverter.class)) {
            return body;
        }
        return advice.beforeBodyWrite(body, returnType, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            new ServletServerHttpRequest(new MockHttpServletRequest(verb, "/encounters/x")),
            new ServletServerHttpResponse(new MockHttpServletResponse()));
    }

    private static EncounterResponseDTO visit(UUID patient, String diagnosis) {
        EncounterResponseDTO dto = new EncounterResponseDTO();
        dto.setId(UUID.randomUUID());
        dto.setPatientId(patient);
        dto.setPatientName("Test Patient");
        dto.setDischargeDiagnoses(diagnosis);
        dto.setNotes(diagnosis);
        return dto;
    }

    @Test
    @DisplayName("a GET whose answer is the restricted chart's record is refused")
    void singleRecordIsRefused() {
        assertThatThrownBy(() -> write(visit(RESTRICTED, "B99"), "single", "GET"))
            .isInstanceOf(ChartRestrictedException.class);
    }

    @Test
    @DisplayName("through the ApiResponseWrapper too")
    void wrappedRecordIsRefused() {
        ApiResponseWrapper<EncounterResponseDTO> wrapped = new ApiResponseWrapper<>();
        wrapped.setData(visit(RESTRICTED, "B99"));
        assertThatThrownBy(() -> write(wrapped, "single", "GET")).isInstanceOf(ChartRestrictedException.class);
    }

    @Test
    @DisplayName("a list keeps the restricted row, masked, and the other rows whole")
    void listIsMasked() throws Exception {
        Object out = write(List.of(visit(RESTRICTED, "B99 DX-SENSITIVE"), visit(OPEN, "J45 asthma")), "list", "GET");
        assertThat(out).isInstanceOf(JsonNode.class);
        JsonNode rows = (JsonNode) out;
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("chartRestricted").asBoolean()).isTrue();
        assertThat(rows.get(0).get("patientName").asString()).isEqualTo("Test Patient");
        assertThat(rows.get(0).toString()).doesNotContain("DX-SENSITIVE").doesNotContain("B99");
        assertThat(rows.get(1).get("dischargeDiagnoses").asString()).isEqualTo("J45 asthma");
    }

    @Test
    @DisplayName("a MASK endpoint is masked but never refused; a write answer is masked")
    void maskModeAndWrites() throws Exception {
        JsonNode masked = (JsonNode) write(visit(RESTRICTED, "B99"), "masked", "GET");
        assertThat(masked.get("chartRestricted").asBoolean()).isTrue();
        assertThat(masked.has("dischargeDiagnoses")).isFalse();

        JsonNode written = (JsonNode) write(visit(RESTRICTED, "B99"), "single", "POST");
        assertThat(written.has("notes")).isFalse();
    }

    @Test
    @DisplayName("NONE, an error answer, an open chart and a caller outside the restriction are untouched")
    void untouched() throws Exception {
        EncounterResponseDTO restricted = visit(RESTRICTED, "B99");
        assertThat(write(restricted, "none", "GET")).isSameAs(restricted);
        Map<String, Object> error = Map.of("patientId", RESTRICTED.toString(), "code", "CHART_RESTRICTED");
        assertThat(write(error, "error", "GET")).isSameAs(error);
        EncounterResponseDTO open = visit(OPEN, "J45");
        assertThat(write(open, "single", "GET")).isSameAs(open);

        when(gate.isSubject()).thenReturn(false);
        assertThat(write(restricted, "single", "GET")).isSameAs(restricted);
        when(gate.isSubject()).thenReturn(true);
        when(gate.anyRestricted()).thenReturn(false);
        assertThat(write(restricted, "single", "GET")).isSameAs(restricted);
    }
}
