package com.example.hms.i18n;

import com.example.hms.enums.*;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnumLabelFallbackContractTest {
    private final MessageSource previous = (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
    private final org.springframework.context.i18n.LocaleContext previousLocale = LocaleContextHolder.getLocaleContext();

    @AfterEach void restore() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.setLocaleContext(previousLocale);
    }

    @Test void everyEnumLabelRetainsItsFallbackWhenBundlesAreMissingOrBroken() throws Exception {
        MessageSource broken = mock(MessageSource.class);
        when(broken.getMessage(anyString(), any(), nullable(String.class), any(Locale.class)))
            .thenThrow(new IllegalStateException("cdx unavailable"));
        for (MessageSource source : new MessageSource[] { null, new StaticMessageSource(), broken }) {
            MessageUtil.setMessageSource(source);
            for (Class<? extends Enum<?>> type : List.of(AppointmentStatus.class, EncounterStatus.class,
                    InvoiceStatus.class, PrescriptionStatus.class, RoutingDecisionStatus.class)) {
                for (Enum<?> value : type.getEnumConstants()) {
                    assertThat(type.getMethod("localizedLabel").invoke(value)).as(type.getSimpleName() + ":" + value.name())
                        .isEqualTo(value.name());
                }
            }
        }
    }

    @Test void availableLabelsRemainTranslatedInTheReaderLanguage() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        for (var language : java.util.Map.of("fr", "Brouillon", "en", "Draft", "es", "Borrador").entrySet()) {
            LocaleContextHolder.setLocale(Locale.forLanguageTag(language.getKey()));
            assertThat(InvoiceStatus.DRAFT.localizedLabel()).isEqualTo(language.getValue());
        }
    }
}
