package com.example.hms.config;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import java.beans.PropertyEditorSupport;
import java.util.Locale;

/**
 * Global binder to robustly parse Accept-Language header values like
 * "en-US,en;q=0.9" into a single Locale (first language tag wins).
 * This avoids MethodArgumentTypeMismatchException when controller
 * methods declare a Locale parameter or @RequestHeader Locale.
 */
@ControllerAdvice
public class LocaleBinderConfig {

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(Locale.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) throws IllegalArgumentException {
                setValue(parseAcceptLanguage(text));
            }
        });
    }

    private Locale parseAcceptLanguage(String header) {
        if (header == null || header.isBlank()) return null; // allow downstream fallback
        // Take first comma-separated value
        String first = header.split(",")[0].trim();
        // Strip any ;q= segment
        int sc = first.indexOf(';');
        if (sc > -1) first = first.substring(0, sc).trim();
        if (first.isEmpty()) return Locale.ENGLISH;
        try {
            Locale locale = Locale.forLanguageTag(first);
            return locale.getLanguage().isEmpty() ? Locale.ENGLISH : locale;
        } catch (Exception e) {
            return Locale.ENGLISH;
        }
    }
}
