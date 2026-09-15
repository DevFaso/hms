package com.example.hms.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Configuration
public class LocaleConfig {

    private static final List<Locale> SUPPORTED_LOCALES = Arrays.asList(
            Locale.ENGLISH,
            Locale.FRENCH,
            Locale.of("es")
    );

    /**
     * French-first product: a request that names no supported language, or
     * none at all (a scheduled job, a partner integration, a curl), is answered
     * in French. Until 2026-09-14 this was ENGLISH, so every API message fell
     * back to English whenever the browser's own Accept-Language did not say
     * fr — which, on a laptop set up in English, was every request.
     */
    private static final Locale DEFAULT_LOCALE = Locale.FRENCH;

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
        localeResolver.setSupportedLocales(SUPPORTED_LOCALES);
        localeResolver.setDefaultLocale(DEFAULT_LOCALE);
        return localeResolver;
    }

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource =
                new ReloadableResourceBundleMessageSource();

        messageSource.setBasenames(
                "classpath:messages",
                "classpath:messages_en",
                "classpath:messages_fr",
                "classpath:messages_es"
        );

        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setCacheSeconds(60);
        messageSource.setUseCodeAsDefaultMessage(false);
        messageSource.setAlwaysUseMessageFormat(true);
        return messageSource;
    }

    /**
     * Helper method that can be @Autowired where needed
     * Provides robust message resolution with fallback logic
     */
    @Bean
    public MessageHelper messageHelper(MessageSource messageSource) {
        return new MessageHelper(messageSource);
    }

    public static class MessageHelper {
        private final MessageSource messageSource;

        public MessageHelper(MessageSource messageSource) {
            this.messageSource = messageSource;
        }

        public String getMessage(String code, Object[] args, Locale locale) {
            try {
                return messageSource.getMessage(code, args, locale);
            } catch (RuntimeException e) {
                if (!locale.equals(Locale.ENGLISH)) {
                    try {
                        return messageSource.getMessage(code, args, Locale.ENGLISH);
                    } catch (RuntimeException ex) {
                        return code + (args != null && args.length > 0 ?
                                " " + Arrays.toString(args) : "");
                    }
                }
                return code + (args != null && args.length > 0 ?
                        " " + Arrays.toString(args) : "");
            }
        }
    }
}

