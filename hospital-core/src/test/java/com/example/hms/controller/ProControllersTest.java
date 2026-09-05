package com.example.hms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.pro.ProInstrument;
import com.example.hms.payload.dto.pro.ProInstrumentDefinitionDTO;
import com.example.hms.payload.dto.pro.ProInstrumentViewDTO;
import com.example.hms.payload.dto.pro.ProResponseCreateDTO;
import com.example.hms.payload.dto.pro.ProResponseDTO;
import com.example.hms.service.pro.ProInstrumentService;
import com.example.hms.service.pro.ProResponseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Wire-level contract of the two PRO controllers (Tier 2 item 47): paths,
 * status codes, validation, and that the hospital scope the controller
 * resolves is what the service receives. Standalone MockMvc, like
 * {@link EligibilityAndSmartPhraseControllerTest}, so nothing here can
 * disturb the other slices' contexts.
 */
@DisplayName("ProInstrumentController + ProResponseController")
class ProControllersTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static MappingJackson2HttpMessageConverter jacksonConverter(ObjectMapper mapper) {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(mapper);
        return converter;
    }

    private static Authentication clinician() {
        return new UsernamePasswordAuthenticationToken("midwife.kone", "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_MIDWIFE")));
    }

    @Nested
    @DisplayName("ProInstrumentController")
    class Instruments {

        private ProInstrumentService instrumentService;
        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            instrumentService = mock(ProInstrumentService.class);
            mockMvc = MockMvcBuilders.standaloneSetup(new ProInstrumentController(instrumentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jacksonConverter(objectMapper))
                .build();
        }

        @Test
        @DisplayName("GET /pro-instruments lists only instruments with text loaded")
        void listHidesInstrumentsWithoutText() throws Exception {
            // Entity equality is by id, so unsaved fixtures need distinct ids for per-object stubs.
            ProInstrument loaded = ProInstrument.builder().code("EPDS").name("Edinburgh").version("1").build();
            loaded.setId(UUID.randomUUID());
            ProInstrument bare = ProInstrument.builder().code("PHQ9").name("Bare").build();
            bare.setId(UUID.randomUUID());
            when(instrumentService.listActive()).thenReturn(List.of(loaded, bare));
            when(instrumentService.languagesOf(loaded)).thenReturn(List.of("en", "fr"));
            when(instrumentService.languagesOf(bare)).thenReturn(List.of());

            mockMvc.perform(get("/pro-instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("EPDS"))
                .andExpect(jsonPath("$[0].languages[1]").value("fr"));
        }

        @Test
        @DisplayName("GET /pro-instruments/{code}?language= renders and passes the language through")
        void getRendersInLanguage() throws Exception {
            when(instrumentService.render("EPDS", "fr"))
                .thenReturn(ProInstrumentViewDTO.builder().code("EPDS").language("fr").maxScore(30).build());

            mockMvc.perform(get("/pro-instruments/EPDS").param("language", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("fr"))
                .andExpect(jsonPath("$.maxScore").value(30));
        }

        @Test
        @DisplayName("GET /pro-instruments/{code} unknown → 404")
        void getUnknownIsNotFound() throws Exception {
            when(instrumentService.render(eq("NOPE"), isNull()))
                .thenThrow(new ResourceNotFoundException("Instrument not found: NOPE"));

            mockMvc.perform(get("/pro-instruments/NOPE"))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("PUT /pro-instruments/{code} takes the code from the path, not the body")
        void importUsesPathCode() throws Exception {
            ProInstrumentDefinitionDTO body = ProInstrumentDefinitionDTO.builder()
                .code("ignored")
                .name("Test")
                .sourceCitation("Fixture")
                .positiveThreshold(1)
                .items(List.of(ProInstrumentDefinitionDTO.Item.builder().itemNo(1)
                    .options(List.of(ProInstrumentDefinitionDTO.Option.builder().optionNo(1).score(0).build()))
                    .build()))
                .texts(List.of(ProInstrumentDefinitionDTO.Translation.builder().language("en")
                    .items(List.of(ProInstrumentDefinitionDTO.ItemText.builder().itemNo(1).prompt("Q")
                        .options(List.of("a")).build()))
                    .build()))
                .build();
            when(instrumentService.importDefinition(any()))
                .thenReturn(ProInstrumentViewDTO.builder().code("TEST").language("en").build());

            mockMvc.perform(put("/pro-instruments/TEST")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TEST"));

            ArgumentCaptor<ProInstrumentDefinitionDTO> captor = ArgumentCaptor.forClass(ProInstrumentDefinitionDTO.class);
            verify(instrumentService).importDefinition(captor.capture());
            assertThat(captor.getValue().getCode()).isEqualTo("TEST");
        }

        @Test
        @DisplayName("PUT /pro-instruments/{code} without a citation → 400")
        void importWithoutCitationIsRejected() throws Exception {
            String body = """
                {"name":"Test","positiveThreshold":1,
                 "items":[{"itemNo":1,"options":[{"optionNo":1,"score":0}]}],
                 "texts":[{"language":"en","items":[{"itemNo":1,"prompt":"Q","options":["a"]}]}]}
                """;

            mockMvc.perform(put("/pro-instruments/TEST")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());

            verify(instrumentService, never()).importDefinition(any());
        }

        @Test
        @DisplayName("PUT /pro-instruments/{code} structural refusal → 400")
        void importStructuralRefusal() throws Exception {
            when(instrumentService.importDefinition(any()))
                .thenThrow(new BusinessException("Critical item 10 is not one of the instrument's items."));
            ProInstrumentDefinitionDTO body = ProInstrumentDefinitionDTO.builder()
                .code("TEST").name("Test").sourceCitation("Fixture").positiveThreshold(1).criticalItemNo(10)
                .items(List.of(ProInstrumentDefinitionDTO.Item.builder().itemNo(1)
                    .options(List.of(ProInstrumentDefinitionDTO.Option.builder().optionNo(1).score(0).build()))
                    .build()))
                .texts(List.of(ProInstrumentDefinitionDTO.Translation.builder().language("en")
                    .items(List.of(ProInstrumentDefinitionDTO.ItemText.builder().itemNo(1).prompt("Q")
                        .options(List.of("a")).build()))
                    .build()))
                .build();

            mockMvc.perform(put("/pro-instruments/TEST")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Critical item 10 is not one of the instrument's items."));
        }
    }

    @Nested
    @DisplayName("ProResponseController")
    class Responses {

        private final UUID patientId = UUID.randomUUID();
        private final UUID hospitalId = UUID.randomUUID();
        private ProResponseService responseService;
        private ControllerAuthUtils authUtils;
        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            responseService = mock(ProResponseService.class);
            authUtils = mock(ControllerAuthUtils.class);
            mockMvc = MockMvcBuilders.standaloneSetup(new ProResponseController(responseService, authUtils))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jacksonConverter(objectMapper))
                .build();
        }

        @Test
        @DisplayName("POST /patients/{id}/pro-responses → 201 with the resolved scope pinned on the request")
        void recordPinsTheResolvedScope() throws Exception {
            UUID requested = UUID.randomUUID();
            when(authUtils.resolveHospitalScope(any(), eq(requested), isNull(), eq(false))).thenReturn(hospitalId);
            when(responseService.record(eq(patientId), any()))
                .thenReturn(ProResponseDTO.builder().id(UUID.randomUUID()).totalScore(4).maxScore(30).build());
            ProResponseCreateDTO body = ProResponseCreateDTO.builder()
                .instrumentCode("EPDS").answers(Map.of(1, 2)).build();

            mockMvc.perform(post("/patients/{patientId}/pro-responses", patientId)
                    .param("hospitalId", requested.toString())
                    .principal(clinician())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalScore").value(4));

            ArgumentCaptor<ProResponseCreateDTO> captor = ArgumentCaptor.forClass(ProResponseCreateDTO.class);
            verify(responseService).record(eq(patientId), captor.capture());
            assertThat(captor.getValue().getHospitalId()).isEqualTo(hospitalId);
            assertThat(captor.getValue().getAnswers()).containsEntry(1, 2);
        }

        @Test
        @DisplayName("POST without answers → 400 before the service is touched")
        void recordWithoutAnswersIsRejected() throws Exception {
            mockMvc.perform(post("/patients/{patientId}/pro-responses", patientId)
                    .principal(clinician())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"instrumentCode\":\"EPDS\"}"))
                .andExpect(status().isBadRequest());

            verify(responseService, never()).record(any(), any());
        }

        @Test
        @DisplayName("POST for a foreign patient → 404")
        void recordForeignPatientIsNotFound() throws Exception {
            when(responseService.record(eq(patientId), any()))
                .thenThrow(new ResourceNotFoundException("patient.notfound"));

            mockMvc.perform(post("/patients/{patientId}/pro-responses", patientId)
                    .principal(clinician())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"instrumentCode\":\"EPDS\",\"answers\":{\"1\":1}}"))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("GET /patients/{id}/pro-responses passes instrument, scope and limit")
        void historyPassesParameters() throws Exception {
            when(authUtils.resolveHospitalScope(any(), isNull(), isNull(), eq(false))).thenReturn(hospitalId);
            when(responseService.history(patientId, hospitalId, "EPDS", 5))
                .thenReturn(List.of(ProResponseDTO.builder().id(UUID.randomUUID()).totalScore(9).build()));

            mockMvc.perform(get("/patients/{patientId}/pro-responses", patientId)
                    .param("instrument", "EPDS")
                    .param("limit", "5")
                    .principal(clinician()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalScore").value(9));
        }

        @Test
        @DisplayName("POST /{responseId}/acknowledge works with and without a body")
        void acknowledgeWithAndWithoutBody() throws Exception {
            UUID responseId = UUID.randomUUID();
            when(authUtils.resolveHospitalScope(any(), isNull(), isNull(), eq(false))).thenReturn(hospitalId);
            when(responseService.acknowledge(eq(patientId), eq(responseId), eq(hospitalId), any()))
                .thenReturn(ProResponseDTO.builder().id(responseId).acknowledgedByDisplay("Mariam Zongo").build());

            mockMvc.perform(post("/patients/{patientId}/pro-responses/{responseId}/acknowledge",
                    patientId, responseId)
                    .principal(clinician())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actionTaken\":\"Referred to psychiatry\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedByDisplay").value("Mariam Zongo"));
            verify(responseService).acknowledge(patientId, responseId, hospitalId, "Referred to psychiatry");

            mockMvc.perform(post("/patients/{patientId}/pro-responses/{responseId}/acknowledge",
                    patientId, responseId)
                    .principal(clinician()))
                .andExpect(status().isOk());
            verify(responseService).acknowledge(patientId, responseId, hospitalId, null);
        }

        @Test
        @DisplayName("POST /{responseId}/acknowledge on a non-critical response → 400")
        void acknowledgeNonCriticalIsRefused() throws Exception {
            UUID responseId = UUID.randomUUID();
            when(responseService.acknowledge(eq(patientId), eq(responseId), any(), any()))
                .thenThrow(new BusinessException("Only a safety-item-positive response needs acknowledging."));

            mockMvc.perform(post("/patients/{patientId}/pro-responses/{responseId}/acknowledge",
                    patientId, responseId)
                    .principal(clinician()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a safety-item-positive response needs acknowledging."));
        }
    }
}
