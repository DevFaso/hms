package com.example.hms.i18n;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D641: the patient portal titled a notification "Break Glass Opt Out
 * Override" in French. Notification.type is a free String column, so the
 * portal's enum-coverage gate cannot read its vocabulary from an enum; the
 * hand-kept list of 13 types in i18n-enum-domains.json never learnt
 * BREAK_GLASS_OPT_OUT_OVERRIDE, and the label fell back to a prettified
 * English token.
 *
 * <p>This reads the vocabulary where it is born — every type argument passed to
 * {@code createNotification(messageKey, args, recipient, type)} in the main sources, as
 * a literal or as a {@code static final String} constant of the calling class
 * — and requires a PORTAL.ENUM.NOTIFICATION_TYPE label for each in EN, FR and
 * ES.
 */
class NotificationTypeLabelCoverageTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path LOCALES = Path.of("..", "hospital-portal", "src", "assets", "i18n");
    private static final Pattern CALL = Pattern.compile("\\bcreateNotification\\(", Pattern.MULTILINE);

    @Test
    void everyEmittedNotificationTypeHasALabelInEveryLanguage() throws Exception {
        TreeMap<String, String> emitted = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                Matcher call = CALL.matcher(src);
                while (call.find()) {
                    List<String> args = arguments(src, call.end());
                    if ((args.size() != 4 && args.size() != 5) || isDeclaration(src, call.start())) continue;
                    String type = resolve(args.get(3), src);
                    if (type != null) emitted.put(type, file.getFileName().toString());
                }
            }
        }
        assertThat(emitted).as("notification types found in %s", MAIN.toAbsolutePath())
            .containsKey("BREAK_GLASS_OPT_OUT_OVERRIDE")
            .containsKey("APPOINTMENT_REMINDER");

        List<String> missing = new ArrayList<>();
        for (String lang : new String[] {"en", "fr", "es"}) {
            Set<String> keyed = notificationTypeKeys(Files.readString(LOCALES.resolve(lang + ".json"), StandardCharsets.UTF_8));
            for (var e : emitted.entrySet()) {
                if (!keyed.contains(e.getKey())) missing.add(lang + ": " + e.getKey() + " (" + e.getValue() + ")");
            }
        }
        assertThat(missing).as("PORTAL.ENUM.NOTIFICATION_TYPE labels").isEmpty();
    }

    private static boolean isDeclaration(String src, int at) {
        int lineStart = src.lastIndexOf('\n', at) + 1;
        String before = src.substring(lineStart, at);
        if (before.endsWith("#")) return true; // a Javadoc {@link #createNotification(...)}
        return before.matches(".*\\b(Notification|public|private|protected)\\s+$") || before.contains("Notification ");
    }

    private static String resolve(String arg, String src) {
        String a = arg.trim();
        if (a.equals("null")) return null;
        if (a.startsWith("\"") && a.endsWith("\"")) return a.substring(1, a.length() - 1);
        String name = a.contains(".") ? a.substring(a.lastIndexOf('.') + 1) : a;
        Matcher constant = Pattern.compile("\\bString\\s+" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]+)\"").matcher(src);
        if (constant.find()) return constant.group(1);
        throw new AssertionError("cannot resolve notification type argument '" + a
            + "': pass a literal or a static final String constant of the calling class");
    }

    /** Top-level comma-separated arguments of the call whose '(' ends at {@code from}. */
    private static List<String> arguments(String src, int from) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder cur = new StringBuilder();
        for (int i = from; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString) {
                cur.append(c);
                if (c == '\\') { cur.append(src.charAt(++i)); continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; cur.append(c); continue; }
            if (c == '(' || c == '{' || c == '[') depth++;
            if (c == '}' || c == ']') depth--;
            if (c == ')') {
                if (depth == 0) { args.add(cur.toString()); return args; }
                depth--;
            }
            if (c == ',' && depth == 0) { args.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        return args;
    }

    private static Set<String> notificationTypeKeys(String json) {
        int at = json.indexOf("\"NOTIFICATION_TYPE\": {");
        assertThat(at).as("PORTAL.ENUM.NOTIFICATION_TYPE block").isPositive();
        String block = json.substring(at, json.indexOf('}', at));
        Set<String> keys = new TreeSet<>();
        Matcher m = Pattern.compile("\"([A-Z0-9_]+)\"\\s*:").matcher(block);
        while (m.find()) keys.add(m.group(1));
        keys.remove("NOTIFICATION_TYPE");
        return keys;
    }
}
