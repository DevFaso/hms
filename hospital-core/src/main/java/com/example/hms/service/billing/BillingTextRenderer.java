package com.example.hms.service.billing;

import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.DisplayDates;
import com.example.hms.utility.MessageUtil;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * QA D1901: the one place billing text is turned into words for a reader.
 *
 * <p>A row written by the server carries a message code and its arguments
 * ({@link BillingText}); it is shown in the reader's language: the staff
 * invoice detail and the patient's {@code /my-billing} through the mappers
 * (the request's {@code Accept-Language}), the invoice PDF and the e-mailed
 * invoice in the {@code lang} they were asked in.
 *
 * <p>A row with no code is what a person typed (a line the cashier wrote, a
 * cancellation reason, invoice notes, a hospital's own service name) and is
 * shown exactly as typed in every language: user-written prose is never
 * translated. A code no bundle knows, or arguments that cannot be read, also
 * fall back to the stored text, never to a raw key.
 */
public final class BillingTextRenderer {

    /** The language of the copy kept in the text column (the product is French-first). */
    public static final Locale STORED_LOCALE = NotificationLocales.STAFF;

    /** Looks a message up; null when no bundle knows the key. */
    @FunctionalInterface
    public interface Messages {
        String find(String key, Object[] args, Locale locale);
    }

    private static final Messages APP_MESSAGES = (key, args, locale) -> MessageUtil.resolveOrNull(locale, key, args);

    private BillingTextRenderer() {
    }

    /** The text in the request's language (staff detail, {@code /my-billing}, refusals). */
    public static String render(String code, String argsJson, String stored) {
        return render(APP_MESSAGES, LocaleContextHolder.getLocale(), code, argsJson, stored);
    }

    /** The text in {@code locale}, through the application's bundles. */
    public static String render(Locale locale, String code, String argsJson, String stored) {
        return render(APP_MESSAGES, locale, code, argsJson, stored);
    }

    /** The text in {@code locale}, through {@code source} (the PDFs carry their own). */
    public static String render(MessageSource source, Locale locale, String code, String argsJson, String stored) {
        if (source == null) {
            return render(locale, code, argsJson, stored);
        }
        return render((key, args, at) -> source.getMessage(key, args, null, at), locale, code, argsJson, stored);
    }

    /**
     * The text in {@code locale}: the code resolved with its arguments, or the
     * stored text as written when there is no code or it cannot be resolved.
     */
    public static String render(Messages messages, Locale locale, String code, String argsJson, String stored) {
        if (code == null || code.isBlank()) {
            return stored;
        }
        Optional<List<BillingText.Arg>> args = BillingText.parseArgs(argsJson);
        if (args.isEmpty()) {
            return stored;
        }
        String rendered = resolve(messages, locale, new BillingText(code, args.get()));
        return rendered != null ? rendered : stored;
    }

    /**
     * True when {@code incoming} is the text the reader was shown (the stored
     * copy, or the code rendered in the request's language): an edit form
     * that echoes it back changed nothing, so the code is kept. Anything else
     * is the person's own text.
     */
    public static boolean isShownText(String incoming, String code, String argsJson, String stored) {
        if (incoming == null) {
            return stored == null;
        }
        String typed = incoming.trim();
        if (stored != null && typed.equals(stored.trim())) {
            return true;
        }
        if (code == null || code.isBlank()) {
            return false;
        }
        String shown = render(code, argsJson, null);
        return shown != null && typed.equals(shown.trim());
    }

    /** The text in {@code locale}, or null when a code or argument is unknown. */
    static String renderOrNull(Locale locale, BillingText text) {
        return resolve(APP_MESSAGES, locale, text);
    }

    private static String resolve(Messages messages, Locale locale, BillingText text) {
        Locale at = locale != null ? locale : STORED_LOCALE;
        Object[] values = new Object[text.args().size()];
        for (int i = 0; i < values.length; i++) {
            String value = argument(messages, at, text.args().get(i));
            if (value == null) {
                return null;
            }
            values[i] = value;
        }
        try {
            return messages.find(text.code(), values, at);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String argument(Messages messages, Locale locale, BillingText.Arg arg) {
        if (arg == null || arg.kind() == null || arg.value() == null) {
            return null;
        }
        return switch (arg.kind()) {
            case VALUE -> arg.value();
            case CODE -> codeArgument(messages, locale, arg.value());
            case DATE -> dateArgument(locale, arg.value());
        };
    }

    private static String codeArgument(Messages messages, Locale locale, String key) {
        try {
            return messages.find(key, new Object[0], locale);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String dateArgument(Locale locale, String isoDay) {
        try {
            return DisplayDates.date(LocalDate.parse(isoDay), locale);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
