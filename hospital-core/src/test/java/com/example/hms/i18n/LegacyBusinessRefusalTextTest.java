package com.example.hms.i18n;

import com.example.hms.controller.InBasketController;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.service.InBasketService;
import com.example.hms.testsupport.SourceScan;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LegacyBusinessRefusalTextTest {
    private MessageSource previous;
    private LocaleContext previousLocale;

    @BeforeEach void messages() {
        previous = (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
        previousLocale = LocaleContextHolder.getLocaleContext();
        MessageUtil.setMessageSource(TestMessageSources.bundles());
    }

    @AfterEach void restore() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.setLocaleContext(previousLocale);
    }

    @ParameterizedTest
    @CsvSource({
        "fr,Choisissez un hôpital actif pour continuer.",
        "en,Select an active hospital to continue.",
        "es,Seleccione un hospital activo para continuar."
    })
    void actualInboxEndpointKeepsItsRefusalAndRendersTheRequestLanguage(String language, String expected) throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("cdx-clinician", "unused", AuthorityUtils.createAuthorityList("ROLE_DOCTOR"));
        var utils = mock(ControllerAuthUtils.class);
        when(utils.resolveUserId(auth)).thenReturn(Optional.of(UUID.randomUUID()));
        var service = mock(InBasketService.class);
        var advice = new GlobalExceptionHandler();
        advice.setMessageSource(TestMessageSources.bundles());
        var mvc = MockMvcBuilders.standaloneSetup(new InBasketController(service, utils))
            .setControllerAdvice(advice).build();
        mvc.perform(get("/in-basket/summary").principal(auth).locale(Locale.forLanguageTag(language)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(expected));
        verifyNoInteractions(service);
    }

    @Test void everySweptRefusalResolvesInAllThreeLanguagesWithoutAnInternalKey() throws Exception {
        var keys = Files.readAllLines(Path.of("src/test/resources/i18n/legacy-refusal-keys.txt"));
        assertThat(keys).hasSize(45);
        for (String language : new String[]{"fr", "en", "es"}) {
            LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
            for (String key : keys) {
                String text = new BusinessException(key, "cdx", 2, 3, 4).getMessage();
                assertThat(text).as(language + ":" + key).isNotBlank().isNotEqualTo(key)
                    .doesNotContain("[Missing translation]", "Message indisponible.", "{0}");
            }
        }
    }

    @Test void noBusinessRefusalStartsWithUntranslatedLiteralProseAnywhere() throws Exception {
        Pattern prose = Pattern.compile("new\\s+(?:[a-z]\\w*\\.)*BusinessException\\s*\\(\\s*\"[^\"\\r\\n]* [^\"\\r\\n]*\"");
        var offenders = new ArrayList<String>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (prose.matcher(SourceScan.stripComments(Files.readString(file))).find()) offenders.add(file.toString());
            }
        }
        assertThat(offenders).as("Use translated BusinessException keys; never add an English-prose exemption").isEmpty();
        assertThat(prose.matcher("throw new BusinessException(\"Hospital context is required.\");").find()).isTrue();
        assertThat(prose.matcher("throw new BusinessException(\"request.hospitalContext.required\");").find()).isFalse();
    }
}
