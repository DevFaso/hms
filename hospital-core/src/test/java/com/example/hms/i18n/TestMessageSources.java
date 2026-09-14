package com.example.hms.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * The real message bundles, wired exactly as {@code LocaleConfig} wires them,
 * for unit tests of services that render user-facing text.
 *
 * <p>A {@code @Mock MessageSource} answers {@code null}, which Mockito's
 * {@code anyString()} does not match, so every test that verifies a
 * notification body would have to stub the source by hand. Reading the real
 * bundles instead keeps the assertions on the words a clinician actually sees
 * and fails loudly when a key is missing from {@code messages*.properties}.
 */
public final class TestMessageSources {

    private TestMessageSources() {
    }

    public static MessageSource bundles() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames(
            "classpath:messages",
            "classpath:messages_en",
            "classpath:messages_fr",
            "classpath:messages_es");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setUseCodeAsDefaultMessage(false);
        messageSource.setAlwaysUseMessageFormat(true);
        return messageSource;
    }
}
