package com.example.hms.exception;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.payload.dto.StaffLeaveRequestDTO;
import com.example.hms.payload.dto.StaffShiftRequestDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.context.request.WebRequest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D754: POST /api/staff/scheduling/leaves with a leaveType the enum does
 * not know answered "La valeur saisie pour « leaveType » n'est pas valide."
 * The sentence must carry a human label in the caller's language, while the
 * {@code field} entry keeps the raw path for clients.
 */
class MalformedRequestFieldLabelTest {

    private GlobalExceptionHandler handler;
    private WebRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        handler.setMessageSource(TestMessageSources.bundles());
        request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/staff/scheduling/leaves");
    }

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static HttpMessageNotReadableException badField(String property) {
        var cause = tools.jackson.databind.exc.InvalidFormatException.from(
            (tools.jackson.core.JsonParser) null, "Cannot deserialize value of type StaffLeaveType", "HOLIDAY", Object.class);
        cause.prependPath(new tools.jackson.core.JacksonException.Reference(null, property));
        return new HttpMessageNotReadableException("x", cause, new MockHttpInputMessage(new byte[0]));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpMessageNotReadableException ex) {
        return (Map<String, Object>) handler.handleHttpMessageNotReadable(ex, request).getBody();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "fr|La valeur saisie pour « type de congé » n'est pas valide.",
        "es|El valor introducido para «tipo de permiso» no es válido.",
        "en|The value entered for \"leave type\" is not valid."
    })
    void leaveTypeIsNamedByItsLabel(String lang, String expected) {
        LocaleContextHolder.setLocale(Locale.forLanguageTag(lang));

        Map<String, Object> body = body(badField("leaveType"));

        assertThat(body).containsEntry("code", "MALFORMED_REQUEST").containsEntry("field", "leaveType");
        assertThat((String) body.get("message")).isEqualTo(expected).doesNotContain("leaveType");
    }

    @Test
    void missingQueryParameterIsNamedByItsLabel() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        var ex = new org.springframework.web.bind.MissingServletRequestParameterException("startDate", "LocalDate");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) handler.handleMissingParameter(ex, request).getBody();

        assertThat((String) body.get("message")).contains("date de début").doesNotContain("startDate");
        assertThat(body).containsEntry("field", "startDate");
    }

    /** QA D1268 (round 26): the fields the round named by their raw property in FR and ES sentences. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "fullName|nom complet|nombre completo", "encounterType|type de consultation|tipo de consulta",
        "dueDate|date d’échéance|fecha de vencimiento", "medicationName|nom du médicament|nombre del medicamento",
        "patientId|patient|paciente", "authorStaffId|auteur|autor", "problemStatement|énoncé du problème|enunciado del problema",
        "priority|priorité|prioridad", "modality|modalité|modalidad", "studyType|type d’examen|tipo de estudio"})
    void theRoundTwentySixFieldsAreNamedByTheirLabel(String property, String french, String spanish) {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat((String) body(badField(property)).get("message")).contains("« " + french + " »")
            .doesNotContain(property);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
        assertThat((String) body(badField(property)).get("message")).contains("«" + spanish + "»");
    }

    /**
     * QA D1312 (round 28; assertion changed): a field with no label used to
     * keep its raw path in the sentence ("« copayMethod »"). It now gets the
     * sentence without a name; the body's {@code field} and {@code fieldErrors}
     * still name it, so the form marks it.
     */
    @Test
    void aFieldWithoutALabelIsNeverNamedByItsRawPath() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        Map<String, Object> body = body(badField("zzUnlabelled"));
        assertThat((String) body.get("message")).isEqualTo("Une valeur saisie n’est pas valide.")
            .doesNotContain("zzUnlabelled");
        assertThat(body).containsEntry("field", "zzUnlabelled");
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldErrors = (Map<String, Object>) body.get("fieldErrors");
        assertThat(fieldErrors).containsOnlyKeys("zzUnlabelled");
    }

    /** QA D1312 (round 28): the check-in's malformed copay method is named by its label in every language. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "fr|La valeur saisie pour « mode de paiement » n'est pas valide.",
        "es|El valor introducido para «forma de pago» no es válido.",
        "en|The value entered for \"payment method\" is not valid."
    })
    void copayMethodIsNamedByItsLabel(String lang, String expected) {
        LocaleContextHolder.setLocale(Locale.forLanguageTag(lang));
        assertThat((String) body(badField("copayMethod")).get("message")).isEqualTo(expected);
    }

    /** Guard: every property of the scheduling request bodies has a label in every bundle. */
    @Test
    void everySchedulingRequestFieldHasALabelInEveryLocale() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String suffix : List.of("", "_en", "_fr", "_es")) {
            Properties props = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/i18n/messages-common" + suffix + ".properties")) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (Class<?> dto : List.of(StaffLeaveRequestDTO.class, StaffShiftRequestDTO.class)) {
                for (var component : dto.getRecordComponents()) {
                    String key = "field.label." + component.getName();
                    String value = props.getProperty(key);
                    if (value == null || value.isBlank()) {
                        missing.add(suffix + ":" + key);
                    }
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    /** QA D1313 (round 29): the three fields the round found unnamed in English. */
    @ParameterizedTest
    @CsvSource({"relationship", "date", "amount"})
    void aLabelThatSpellsItsKeyIsStillUsed(String property) {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat((String) body(badField(property)).get("message"))
            .isEqualTo("The value entered for \"" + property + "\" is not valid.");
    }

    @Test
    void labelsResolveThroughTheRealMessageSource() {
        MessageSource source = TestMessageSources.bundles();
        assertThat(source.getMessage("field.label.endDate", null, Locale.FRENCH)).isEqualTo("date de fin");
    }
}
