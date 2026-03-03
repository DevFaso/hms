package com.example.hms.controller;

import com.example.hms.payload.dto.ServiceTranslationRequestDTO;
import com.example.hms.payload.dto.ServiceTranslationResponseDTO;
import com.example.hms.service.ServiceTranslationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit-level controller tests for {@link ServiceTranslationController}.
 *
 * <p>Uses {@code @AutoConfigureMockMvc(addFilters = false)} so that the Spring Security
 * filter chain (CSRF, JWT) is not applied — this slice focuses purely on the controller
 * layer. Security integration is tested separately in the IT suite.
 *
 * <p>The delete endpoint test also verifies the XSS-fix introduced for CodeQL alert
 * {@code java/xss}: the response Content-Type must be {@code text/plain} and the body
 * must be HTML-escaped (any {@code <} / {@code >} in a message template would be
 * escaped by {@link org.springframework.web.util.HtmlUtils#htmlEscape}).
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = ServiceTranslationController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
class ServiceTranslationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ServiceTranslationService translationService;
    @MockitoBean private MessageSource messageSource;

    private UUID translationId;
    private UUID treatmentId;
    private ServiceTranslationResponseDTO responseDTO;
    private ServiceTranslationRequestDTO requestDTO;

    @BeforeEach
    void setUp() {
        translationId = UUID.randomUUID();
        treatmentId   = UUID.randomUUID();

        responseDTO = ServiceTranslationResponseDTO.builder()
            .id(translationId)
            .treatmentId(treatmentId)
            .treatmentName("Physiotherapy")
            .languageCode("en")
            .name("Physiotherapy")
            .description("Physical treatment")
            .build();

        requestDTO = new ServiceTranslationRequestDTO();
        requestDTO.setTreatmentId(treatmentId);
        requestDTO.setAssignmentId(UUID.randomUUID());
        requestDTO.setLanguageCode("en");
        requestDTO.setName("Physiotherapy");
    }

    // ─── POST /service-translations ──────────────────────────────────────

    @Nested
    class CreateTranslation {

        @Test
        void createTranslation_returns201WithBody() throws Exception {
            when(translationService.createTranslation(any(), any())).thenReturn(responseDTO);

            mockMvc.perform(post("/service-translations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDTO)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(translationId.toString()))
                    .andExpect(jsonPath("$.languageCode").value("en"))
                    .andExpect(jsonPath("$.name").value("Physiotherapy"));
        }

        @Test
        void createTranslation_missingName_returns400() throws Exception {
            requestDTO.setName(null);

            mockMvc.perform(post("/service-translations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDTO)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void createTranslation_blankLanguageCode_returns400() throws Exception {
            requestDTO.setLanguageCode("");

            mockMvc.perform(post("/service-translations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDTO)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─── GET /service-translations/{id} ──────────────────────────────────

    @Nested
    class GetTranslationById {

        @Test
        void getTranslationById_returns200WithBody() throws Exception {
            when(translationService.getTranslationById(eq(translationId), nullable(Locale.class)))
                .thenReturn(responseDTO);

            mockMvc.perform(get("/service-translations/{id}", translationId)
                            .header("Accept-Language", "en"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(translationId.toString()))
                    .andExpect(jsonPath("$.treatmentName").value("Physiotherapy"));
        }
    }

    // ─── GET /service-translations ───────────────────────────────────────

    @Nested
    class GetAllTranslations {

        @Test
        void getAllTranslations_returnsListOf200() throws Exception {
            when(translationService.getAllTranslations(nullable(Locale.class)))
                .thenReturn(List.of(responseDTO, responseDTO));

            mockMvc.perform(get("/service-translations")
                            .header("Accept-Language", "fr"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        void getAllTranslations_emptyList_returns200() throws Exception {
            when(translationService.getAllTranslations(nullable(Locale.class)))
                .thenReturn(List.of());

            mockMvc.perform(get("/service-translations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ─── PUT /service-translations/{id} ──────────────────────────────────

    @Nested
    class UpdateTranslation {

        @Test
        void updateTranslation_returns200WithUpdatedBody() throws Exception {
            ServiceTranslationResponseDTO updated = ServiceTranslationResponseDTO.builder()
                .id(translationId)
                .treatmentId(treatmentId)
                .treatmentName("Physiotherapy")
                .languageCode("en")
                .name("Updated Name")
                .description("Updated description")
                .build();

            when(translationService.updateTranslation(eq(translationId), any(), nullable(Locale.class)))
                .thenReturn(updated);

            requestDTO.setName("Updated Name");

            mockMvc.perform(put("/service-translations/{id}", translationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDTO)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated Name"));
        }
    }

    // ─── DELETE /service-translations/{id} ───────────────────────────────

    @Nested
    class DeleteTranslation {

        /**
         * Core XSS-fix verification (CodeQL java/xss).
         *
         * <p>The endpoint must return:
         * <ul>
         *   <li>HTTP 200 OK</li>
         *   <li>Content-Type: text/plain  (NOT text/html — prevents browser rendering)</li>
         *   <li>Body = HTML-escaped message (e.g. {@code &lt;} instead of {@code <})</li>
         * </ul>
         */
        @Test
        void deleteTranslation_returnsPlainTextBody_xssSafe() throws Exception {
            // The message template contains HTML-special characters to prove escaping works.
            String rawMessage = "Translation <" + translationId + "> deleted.";
            String escapedMessage = "Translation &lt;" + translationId + "&gt; deleted.";

            doNothing().when(translationService).deleteTranslation(eq(translationId), nullable(Locale.class));
            when(messageSource.getMessage(eq("translation.deleted"), any(Object[].class), nullable(Locale.class)))
                .thenReturn(rawMessage);

            mockMvc.perform(delete("/service-translations/{id}", translationId)
                            .header("Accept-Language", "en"))
                    .andExpect(status().isOk())
                    // Content-Type MUST be text/plain — not text/html
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/plain")))
                    // Body must be HTML-escaped  (< → &lt;   > → &gt;)
                    .andExpect(content().string(escapedMessage));

            verify(translationService).deleteTranslation(eq(translationId), nullable(Locale.class));
        }

        @Test
        void deleteTranslation_safeMessageNeededNoHtmlChars_returnsPlain() throws Exception {
            // When the message has no HTML-special characters the body is returned unchanged.
            String safeMessage = "Translation " + translationId + " deleted successfully.";

            doNothing().when(translationService).deleteTranslation(eq(translationId), nullable(Locale.class));
            when(messageSource.getMessage(eq("translation.deleted"), any(Object[].class), nullable(Locale.class)))
                .thenReturn(safeMessage);

            mockMvc.perform(delete("/service-translations/{id}", translationId))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/plain")))
                    .andExpect(content().string(safeMessage));
        }
    }
}
