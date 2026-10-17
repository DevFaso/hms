package com.example.hms.cdshooks;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.Locale;

/**
 * The words on a CDS card, in the caller's language (D61).
 *
 * <p>Card summaries and details used to be English string concatenation, so
 * a French clinician opening a chart read "1 active allergy on file" in an
 * otherwise French screen. Each sentence now has a {@code cds.*} key in the
 * message bundles; the English text stays in code as the default, so a
 * missing key — or a rule built with {@code new} in a unit test, where no
 * bundle is wired — still produces a readable card.
 *
 * <p>Numbers are passed in already formatted (as strings) so MessageFormat
 * never regroups a dose like 1250.00 into "1 250".
 */
@Component
public class CdsCardText {

    /** English-only instance for rules constructed outside Spring. */
    public static final CdsCardText ENGLISH = new CdsCardText(null);

    @Nullable
    private final MessageSource messageSource;

    public CdsCardText(@Nullable MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** The sentence for {@code key} in the request locale, else {@code english} formatted with {@code args}. */
    public String get(String key, String english, Object... args) {
        String pattern = english.replace("'", "''");
        if (messageSource == null) {
            return new MessageFormat(pattern, Locale.ENGLISH).format(args);
        }
        return messageSource.getMessage(key, args, pattern, LocaleContextHolder.getLocale());
    }

    /** A severity / status enum in words: {@code cds.enum.<NAME>}, else its lower-case name. */
    public String enumWord(@Nullable Enum<?> value) {
        if (value == null) {
            return "";
        }
        String fallback = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return get("cds.enum." + value.name(), fallback);
    }
}
