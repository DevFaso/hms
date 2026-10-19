package com.example.hms.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The backend message bundles, split by feature area under
 * {@code src/main/resources/i18n/} so that two PRs adding keys to different
 * areas no longer append to the same file and conflict.
 *
 * <p>Each area has a default file and {@code _en}, {@code _fr}, {@code _es}
 * variants: {@code i18n/messages-<area>[_<locale>].properties}. A new key goes
 * to the area file its prefix belongs to (all four locales), or to
 * {@code common} when no area fits. A key must live in exactly one area;
 * {@code MessageBundleSplitTest} enforces that.
 */
public final class MessageBundles {

    /** Feature areas, one file set each. Adding an area means adding its four files. */
    public static final List<String> AREAS = List.of(
        "admin", "auth", "billing", "clinical", "common", "lab", "maternity",
        "notification", "patient", "pharmacy", "scheduling");

    /** Locale suffixes; {@code ""} is the default file. */
    public static final List<String> SUFFIXES = List.of("", "_en", "_fr", "_es");

    private MessageBundles() {
    }

    /** Classpath-relative resource path of one area file, e.g. {@code i18n/messages-lab_fr.properties}. */
    public static String resource(String area, String suffix) {
        return "i18n/messages-" + area + suffix + ".properties";
    }

    /**
     * Basenames for a {@code ReloadableResourceBundleMessageSource}, in the order
     * the single bundle used: every area's default basename first, then the
     * {@code _en}, {@code _fr} and {@code _es} files registered as basenames of
     * their own (the historical last-resort fallback chain).
     */
    public static String[] basenames() {
        List<String> out = new ArrayList<>();
        for (String suffix : SUFFIXES) {
            for (String area : AREAS) {
                out.add("classpath:i18n/messages-" + area + suffix);
            }
        }
        return out.toArray(String[]::new);
    }
}
