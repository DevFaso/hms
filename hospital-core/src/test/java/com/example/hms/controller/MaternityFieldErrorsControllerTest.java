package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.service.LaborService;
import com.example.hms.service.MaternalHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The refusal bodies of the maternity calls the round-13 browser walk made
 * (QA D806, D808, D809, D821). Every refusal names its field under
 * {@code fieldErrors}, so the portal can name it to the midwife, and a value
 * outside its bounds never reaches the service.
 */
class MaternityFieldErrorsControllerTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final LaborService laborService = mock(LaborService.class);
    private final MaternalHistoryService historyService = mock(MaternalHistoryService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new LaborController(laborService, mock(ControllerAuthUtils.class)),
            new MaternalHistoryController(historyService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    private final UUID patientId = UUID.randomUUID();
    private final UUID episodeId = UUID.randomUUID();

    @Test
    @DisplayName("D806: a birth before the last partograph entry answers 400 on fieldErrors.birthDateTime")
    void aDeliveryOutOfOrderNamesTheBirthField() throws Exception {
        when(laborService.recordDelivery(eq(patientId), eq(episodeId), any(), any()))
            .thenThrow(new FieldBusinessException("birthDateTime", "labor.delivery.beforeLastPartograph"));
        LocalDateTime birth = LocalDateTime.now().minusHours(1);

        mvc.perform(post("/patients/{p}/labor/episodes/{e}/delivery", patientId, episodeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryMode\":\"SPONTANEOUS_VAGINAL\",\"birthDateTime\":\"" + ISO.format(birth)
                    + "\",\"placentaDeliveredAt\":\"" + ISO.format(birth.minusMinutes(160)) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("birthDateTime"))
            .andExpect(jsonPath("$.fieldErrors.birthDateTime").isNotEmpty())
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("D808: dilation 15 is refused on fieldErrors.cervicalDilationCm before the service")
    void aDilationOfFifteenIsRefused() throws Exception {
        mvc.perform(post("/patients/{p}/labor/episodes/{e}/entries", patientId, episodeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cervicalDilationCm\":15}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Validation Failed"))
            .andExpect(jsonPath("$.fieldErrors.cervicalDilationCm").isNotEmpty());
        verifyNoInteractions(laborService);
    }

    @Test
    @DisplayName("D808: APGAR 1 min = 15 is refused on fieldErrors.apgarOneMinute before the service")
    void anApgarOfFifteenIsRefused() throws Exception {
        mvc.perform(post("/patients/{p}/labor/episodes/{e}/delivery", patientId, episodeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryMode\":\"SPONTANEOUS_VAGINAL\",\"apgarOneMinute\":15}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.apgarOneMinute").isNotEmpty());
        verifyNoInteractions(laborService);
    }

    @Test
    @DisplayName("D809: a negative parity on the labour admission is refused on fieldErrors.para")
    void aNegativeParityIsRefused() throws Exception {
        mvc.perform(post("/patients/{p}/labor/episodes", patientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"laborOnsetAt\":\"" + ISO.format(LocalDateTime.now().minusHours(2))
                    + "\",\"gravida\":1,\"para\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.para").isNotEmpty());
        verifyNoInteractions(laborService);
    }

    @Test
    @DisplayName("D809: G1 P3 on the maternal history answers 400 on fieldErrors['obstetricHistory.para']")
    void aParityAboveGravidityNamesTheParaField() throws Exception {
        when(historyService.createMaternalHistory(any(), any()))
            .thenThrow(new FieldBusinessException("obstetricHistory.para", "maternity.obstetric.paraAboveGravida"));

        mvc.perform(post("/maternal-history").principal(midwife())
                .contentType(MediaType.APPLICATION_JSON)
                .content(history(LocalDateTime.now().minusMinutes(1), "\"obstetricHistory\":{\"gravida\":1,\"para\":3}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors['obstetricHistory.para']").isNotEmpty());
    }

    @Test
    @DisplayName("D821: a maternal history recorded five days ahead is refused before the service")
    void aFutureRecordedDateIsRefused() throws Exception {
        mvc.perform(post("/maternal-history").principal(midwife())
                .contentType(MediaType.APPLICATION_JSON)
                .content(history(LocalDateTime.now().plusDays(5), null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.recordedDate").isNotEmpty());
        verifyNoInteractions(historyService);
    }

    private String history(LocalDateTime recordedDate, String extra) {
        return "{\"patientId\":\"" + patientId + "\",\"hospitalId\":\"" + UUID.randomUUID()
            + "\",\"recordedDate\":\"" + ISO.format(recordedDate) + "\""
            + (extra == null ? "" : "," + extra) + "}";
    }

    private static UsernamePasswordAuthenticationToken midwife() {
        return new UsernamePasswordAuthenticationToken("midwife.a", null,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MIDWIFE")));
    }
}
