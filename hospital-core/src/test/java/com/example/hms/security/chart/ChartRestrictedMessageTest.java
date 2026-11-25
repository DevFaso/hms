package com.example.hms.security.chart;

import com.example.hms.config.MessageBundles;
import com.example.hms.exception.ChartRestrictedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D3864: the refusal reads in the caller's language and points to break-the-glass, never to reloading. */
@DisplayName("D3864: the restricted-chart refusal is localised")
class ChartRestrictedMessageTest {

    private static String resolve(Locale locale) {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames(MessageBundles.basenames());
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
        return source.getMessage(new ChartRestrictedException(UUID.randomUUID()).getMessage(), null, locale);
    }

    @Test
    void frenchEnglishSpanish() {
        assertThat(resolve(Locale.FRENCH))
            .startsWith("Ce dossier est restreint.")
            .contains("« Déclencher l'accès d'urgence »");
        assertThat(resolve(Locale.ENGLISH)).startsWith("This chart is restricted.").contains("break-the-glass");
        assertThat(resolve(Locale.forLanguageTag("es"))).startsWith("Esta historia clínica está restringida.")
            .contains("acceso de emergencia");
        for (Locale locale : new Locale[] {Locale.FRENCH, Locale.ENGLISH, Locale.forLanguageTag("es")}) {
            assertThat(resolve(locale).toLowerCase(Locale.ROOT))
                .doesNotContain("actualis").doesNotContain("reload").doesNotContain("refresh").doesNotContain("recarg");
        }
    }
}
