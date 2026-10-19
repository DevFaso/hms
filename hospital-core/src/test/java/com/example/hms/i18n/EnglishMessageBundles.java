package com.example.hms.i18n;

import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * The services throw message keys (QA D226). This extension renders them
 * through the real bundles in English before each test, so assertions read the
 * words a user sees and never depend on which test last set the static
 * {@link MessageUtil} source; the locale is reset afterwards.
 */
public class EnglishMessageBundles implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        LocaleContextHolder.resetLocaleContext();
    }
}
