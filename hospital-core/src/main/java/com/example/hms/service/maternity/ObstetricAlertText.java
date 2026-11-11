package com.example.hms.service.maternity;

import com.example.hms.service.i18n.NotificationLocalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;

/**
 * QA D1427: an obstetric alert row stores its sentence once, in the staff
 * language ({@code NotificationLocales.STAFF}), and every reader used to get
 * that copy: the toast and the chart tooltip were French in an English or a
 * Spanish session, while the notice about the same alert was right. A row keeps
 * its code, so the mappers render the sentence in the reader's language when the
 * row is read, with the stored copy as the fallback (a code no bundle knows, or
 * arguments that can no longer be derived).
 */
@Slf4j
public final class ObstetricAlertText {

    private ObstetricAlertText() {
    }

    /**
     * @param localizer null keeps the stored text (a mapper built without one)
     * @param args      the sentence's arguments
     */
    public static String render(NotificationLocalizer localizer, String keyPrefix, String code,
                                List<Object> args, String stored) {
        if (localizer == null || code == null) {
            return stored;
        }
        try {
            return localizer.render(keyPrefix + code, args, LocaleContextHolder.getLocale());
        } catch (RuntimeException ex) {
            log.warn("Obstetric alert {} kept its stored text ({})", code, ex.getClass().getSimpleName());
            return stored;
        }
    }
}
