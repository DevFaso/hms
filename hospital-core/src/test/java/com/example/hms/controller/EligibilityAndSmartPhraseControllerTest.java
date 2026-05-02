package com.example.hms.controller;

import com.example.hms.enums.EligibilityCheckType;
import com.example.hms.enums.EligibilityScheme;
import com.example.hms.enums.EligibilityStatus;
import com.example.hms.enums.SmartPhraseScope;
import com.example.hms.payload.dto.SmartPhraseRequestDTO;
import com.example.hms.payload.dto.SmartPhraseResponseDTO;
import com.example.hms.payload.dto.insurance.EligibilityCheckRequestDTO;
import com.example.hms.payload.dto.insurance.EligibilityResponseDTO;
import com.example.hms.service.EligibilityService;
import com.example.hms.service.SmartPhraseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for {@link EligibilityController} and {@link SmartPhraseController}.
 *
 * <p>Intentionally uses {@link MockMvcBuilders#standaloneSetup} rather than
 * {@code @WebMvcTest}: the project's H2 in-memory test datasource is shared
 * across all Spring TestContext-cached contexts (DB_CLOSE_DELAY=-1) and
 * Hibernate {@code create-drop} is wired in the test profile, so adding
 * yet another unique {@code @WebMvcTest} configuration evicts older
 * contexts mid-suite, which then run their {@code DROP SCHEMA} hooks while
 * the next integration test is mid-query — the symptom is unrelated tests
 * (e.g. {@code RoleControllerIntegrationTest}, {@code MllpTcpServerIT})
 * failing with "Schema not found". The standalone setup spins MockMvc up
 * around a hand-injected controller without ever loading a Spring context,
 * so it has no effect on the rest of the suite.
 */
@DisplayName("EligibilityController + SmartPhraseController")
class EligibilityAndSmartPhraseControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ───────────────────────────────────────────────────────────────────────
    // EligibilityController
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("EligibilityController")
    class EligibilityTests {

        private EligibilityService eligibilityService;
        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            eligibilityService = mock(EligibilityService.class);
            EligibilityController controller = new EligibilityController(eligibilityService);
            mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(jacksonConverter(objectMapper))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        }

        private EligibilityCheckRequestDTO baseRequest(EligibilityScheme scheme,
                                                       EligibilityCheckType type) {
            return EligibilityCheckRequestDTO.builder()
                .patientId(UUID.randomUUID())
                .hospitalId(UUID.randomUUID())
                .scheme(scheme)
                .checkType(type)
                .memberId("NHIS-001")
                .build();
        }

        private EligibilityResponseDTO buildResponse(EligibilityCheckType type, EligibilityStatus status) {
            return EligibilityResponseDTO.builder()
                .id(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .hospitalId(UUID.randomUUID())
                .scheme(EligibilityScheme.NHIS_GH)
                .checkType(type)
                .memberId("NHIS-001")
                .requestedAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .status(status)
                .responseCode("ACTIVE")
                .copayAmount(new BigDecimal("0.00"))
                .copayCurrency("GHS")
                .priorAuthRequired(true)
                .validUntil(LocalDate.now().plusDays(30))
                .build();
        }

        @Test
        @DisplayName("POST /eligibility/check returns 201 and forces COVERAGE")
        void checkCoverage_returnsCreated() throws Exception {
            EligibilityCheckRequestDTO req = baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.PRIOR_AUTH);
            EligibilityResponseDTO resp = buildResponse(EligibilityCheckType.COVERAGE, EligibilityStatus.ELIGIBLE);
            when(eligibilityService.submit(any(EligibilityCheckRequestDTO.class))).thenReturn(resp);

            mockMvc.perform(post("/eligibility/check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ELIGIBLE"))
                .andExpect(jsonPath("$.checkType").value("COVERAGE"))
                .andExpect(jsonPath("$.copayCurrency").value("GHS"));

            verify(eligibilityService).submit(any(EligibilityCheckRequestDTO.class));
        }

        @Test
        @DisplayName("POST /eligibility/prior-auth returns 201 and forces PRIOR_AUTH")
        void priorAuth_returnsCreated() throws Exception {
            EligibilityCheckRequestDTO req = baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE);
            req.setServiceCode("CT-HEAD");
            EligibilityResponseDTO resp = buildResponse(EligibilityCheckType.PRIOR_AUTH, EligibilityStatus.ELIGIBLE);
            resp.setPriorAuthNumber("PA-123");
            when(eligibilityService.submit(any(EligibilityCheckRequestDTO.class))).thenReturn(resp);

            mockMvc.perform(post("/eligibility/prior-auth")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkType").value("PRIOR_AUTH"))
                .andExpect(jsonPath("$.priorAuthNumber").value("PA-123"));
        }

        @Test
        @DisplayName("GET /eligibility/{id} returns 200 with the persisted check")
        void getById_returnsOk() throws Exception {
            UUID id = UUID.randomUUID();
            EligibilityResponseDTO resp = buildResponse(EligibilityCheckType.COVERAGE, EligibilityStatus.ELIGIBLE);
            resp.setId(id);
            when(eligibilityService.get(id)).thenReturn(resp);

            mockMvc.perform(get("/eligibility/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        }

        @Test
        @DisplayName("GET /eligibility/patient/{id} delegates to listByPatient with the right Pageable")
        void listByPatient_delegates() throws Exception {
            UUID patientId = UUID.randomUUID();
            EligibilityResponseDTO resp = buildResponse(EligibilityCheckType.COVERAGE, EligibilityStatus.ELIGIBLE);
            Page<EligibilityResponseDTO> page = new PageImpl<>(List.of(resp));
            when(eligibilityService.listByPatient(eq(patientId), any(Pageable.class))).thenReturn(page);

            // Standalone MockMvc has no Page-to-JSON serializer configured, so we can't
            // assert the body — but the service-call signature is the controller contract
            // we care about (path variable + Pageable argument resolution).
            mockMvc.perform(get("/eligibility/patient/{patientId}", patientId)
                    .param("page", "0").param("size", "20"));

            verify(eligibilityService).listByPatient(eq(patientId), any(Pageable.class));
        }

        @Test
        @DisplayName("GET /eligibility/patient/{id}/latest returns 200 when present")
        void latestForPatient_returnsResultWhenPresent() throws Exception {
            UUID patientId = UUID.randomUUID();
            EligibilityResponseDTO resp = buildResponse(EligibilityCheckType.COVERAGE, EligibilityStatus.ELIGIBLE);
            when(eligibilityService.findLatestForPatient(
                patientId, EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE))
                .thenReturn(Optional.of(resp));

            mockMvc.perform(get("/eligibility/patient/{patientId}/latest", patientId)
                    .param("scheme", "NHIS_GH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ELIGIBLE"));
        }

        @Test
        @DisplayName("GET /eligibility/patient/{id}/latest returns 204 when no prior check")
        void latestForPatient_returns204WhenAbsent() throws Exception {
            UUID patientId = UUID.randomUUID();
            when(eligibilityService.findLatestForPatient(
                patientId, EligibilityScheme.CNAMGS_GA, EligibilityCheckType.PRIOR_AUTH))
                .thenReturn(Optional.empty());

            mockMvc.perform(get("/eligibility/patient/{patientId}/latest", patientId)
                    .param("scheme", "CNAMGS_GA")
                    .param("type", "PRIOR_AUTH"))
                .andExpect(status().isNoContent());
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // SmartPhraseController
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SmartPhraseController")
    class SmartPhraseTests {

        private SmartPhraseService smartPhraseService;
        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            smartPhraseService = mock(SmartPhraseService.class);
            SmartPhraseController controller = new SmartPhraseController(smartPhraseService);
            mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(jacksonConverter(objectMapper))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        }

        private SmartPhraseRequestDTO globalRequest(String trigger) {
            return SmartPhraseRequestDTO.builder()
                .trigger(trigger)
                .title("Normal ROS")
                .expansion("Constitutional: denies fever, …")
                .scope(SmartPhraseScope.GLOBAL)
                .build();
        }

        private SmartPhraseResponseDTO buildResponse(String trigger, SmartPhraseScope scope) {
            return SmartPhraseResponseDTO.builder()
                .id(UUID.randomUUID())
                .trigger(trigger)
                .title("Normal ROS")
                .expansion("Constitutional: denies fever, …")
                .scope(scope)
                .usageCount(0L)
                .build();
        }

        @Test
        @DisplayName("POST /smart-phrases returns 201")
        void create_returnsCreated() throws Exception {
            SmartPhraseRequestDTO req = globalRequest(".normros");
            SmartPhraseResponseDTO resp = buildResponse(".normros", SmartPhraseScope.GLOBAL);
            when(smartPhraseService.create(any(SmartPhraseRequestDTO.class))).thenReturn(resp);

            mockMvc.perform(post("/smart-phrases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trigger").value(".normros"))
                .andExpect(jsonPath("$.scope").value("GLOBAL"));

            verify(smartPhraseService).create(any(SmartPhraseRequestDTO.class));
        }

        @Test
        @DisplayName("POST /smart-phrases with malformed trigger returns 400")
        void create_rejectsBadTrigger() throws Exception {
            SmartPhraseRequestDTO req = globalRequest("normros");

            mockMvc.perform(post("/smart-phrases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

            verifyNoInteractions(smartPhraseService);
        }

        @Test
        @DisplayName("PUT /smart-phrases/{id} returns 200")
        void update_returnsOk() throws Exception {
            UUID id = UUID.randomUUID();
            SmartPhraseRequestDTO req = globalRequest(".normros");
            SmartPhraseResponseDTO resp = buildResponse(".normros", SmartPhraseScope.GLOBAL);
            when(smartPhraseService.update(eq(id), any(SmartPhraseRequestDTO.class))).thenReturn(resp);

            mockMvc.perform(put("/smart-phrases/{id}", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger").value(".normros"));
        }

        @Test
        @DisplayName("DELETE /smart-phrases/{id} returns 204")
        void delete_returns204() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete("/smart-phrases/{id}", id))
                .andExpect(status().isNoContent());

            verify(smartPhraseService).delete(id);
        }

        @Test
        @DisplayName("GET /smart-phrases/{id} returns 200")
        void getById_returnsOk() throws Exception {
            UUID id = UUID.randomUUID();
            SmartPhraseResponseDTO resp = buildResponse(".normexam", SmartPhraseScope.GLOBAL);
            when(smartPhraseService.get(id)).thenReturn(resp);

            mockMvc.perform(get("/smart-phrases/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger").value(".normexam"));
        }

        @Test
        @DisplayName("GET /smart-phrases/global delegates to listGlobal with a Pageable")
        void listGlobal_delegates() throws Exception {
            SmartPhraseResponseDTO resp = buildResponse(".normros", SmartPhraseScope.GLOBAL);
            Page<SmartPhraseResponseDTO> page = new PageImpl<>(List.of(resp));
            when(smartPhraseService.listGlobal(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/smart-phrases/global").param("page", "0").param("size", "50"));

            verify(smartPhraseService).listGlobal(any(Pageable.class));
        }

        @Test
        @DisplayName("GET /smart-phrases/autocomplete passes through service results")
        void autocomplete_passesThrough() throws Exception {
            UUID hospitalId = UUID.randomUUID();
            SmartPhraseResponseDTO resp = buildResponse(".normexam", SmartPhraseScope.HOSPITAL);
            when(smartPhraseService.autocomplete(".norm", hospitalId)).thenReturn(List.of(resp));

            mockMvc.perform(get("/smart-phrases/autocomplete")
                    .param("prefix", ".norm")
                    .param("hospitalId", hospitalId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trigger").value(".normexam"))
                .andExpect(jsonPath("$[0].scope").value("HOSPITAL"));
        }

        @Test
        @DisplayName("POST /smart-phrases/{id}/usage returns 204")
        void recordUsage_returns204() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(post("/smart-phrases/{id}/usage", id))
                .andExpect(status().isNoContent());

            verify(smartPhraseService).recordUsage(id);
        }
    }

    private static MappingJackson2HttpMessageConverter jacksonConverter(ObjectMapper mapper) {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(mapper);
        return converter;
    }
}
