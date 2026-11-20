package com.example.hms.service;

import com.example.hms.mapper.LabResultMapper;
import com.example.hms.payload.dto.LabResultComponentResponseDTO;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * r65 D2623: the read-back of a critical PANEL result (an NFS).
 *
 * <p>A panel's stored value is a summary of every component
 * ("HB 5.5 g/dL; HCT 42 %; GR 4.8 10^12/L; ..."), and the single-value
 * comparison held the clinician to that whole line: "HB 5,5 g/dL" was refused
 * and the critical panel could never be acknowledged, so it paged for six
 * hours. What is phoned and repeated for a panel is its critical
 * component(s), so that is what is checked:
 * <ul>
 *   <li>every component graded CRITICAL must be named (code or name, case and
 *       accents aside) with its value, unit optional, decimal comma or point
 *       ({@link CriticalValueNotificationService#readBackMatches});</li>
 *   <li>with exactly one critical component, its bare value alone ("5,5" or
 *       "5,5 g/dL") is accepted too, since it cannot be confused;</li>
 *   <li>several components are separated by ";", a new line, " et " / " and ",
 *       or a comma followed by a name ("HB 5,5, PLT 15");</li>
 *   <li>any component named must carry its own value, critical or not: a
 *       wrong value, an unknown name or an unparseable part is a mismatch;</li>
 *   <li>a panel paged as critical with no component graded critical now (an
 *       analyser's flag, a definition since changed) needs at least one
 *       component named, every one of them right.</li>
 * </ul>
 * The full stored summary still matches, as it always did.
 */
final class PanelReadBack {

    private static final Pattern SEGMENT_SPLIT =
        Pattern.compile("\\s*(?:[;\\n\\r|]+|\\s+(?:et|and|y)\\s+|,(?=\\s*\\p{L}))\\s*");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern LABEL_SEPARATOR = Pattern.compile("^[\\s:=]+");

    private PanelReadBack() {
    }

    /** Whether {@code repeated} reads back the critical component(s) of these graded components. */
    static boolean matches(List<LabResultComponentResponseDTO> components, String repeated) {
        if (components == null || components.isEmpty() || repeated == null || repeated.isBlank()) {
            return false;
        }
        List<LabResultComponentResponseDTO> critical = new ArrayList<>();
        for (LabResultComponentResponseDTO component : components) {
            if (component != null && LabResultMapper.FLAG_CRITICAL.equalsIgnoreCase(component.getSeverityFlag())) {
                critical.add(component);
            }
        }
        List<String> segments = segmentsOf(repeated);
        if (segments.isEmpty()) {
            return false;
        }
        if (critical.size() == 1 && segments.size() == 1 && namedIn(components, segments.get(0)) == null) {
            LabResultComponentResponseDTO only = critical.get(0);
            return CriticalValueNotificationService.readBackMatches(only.getValue(), only.getUnit(), segments.get(0));
        }
        Set<String> confirmed = new HashSet<>();
        for (String segment : segments) {
            LabResultComponentResponseDTO named = namedIn(components, segment);
            if (named == null) {
                return false;
            }
            String value = LABEL_SEPARATOR.matcher(segment.substring(labelLength(named, segment))).replaceFirst("");
            if (value.isBlank()
                || !CriticalValueNotificationService.readBackMatches(named.getValue(), named.getUnit(), value)) {
                return false;
            }
            confirmed.add(keyOf(named));
        }
        for (LabResultComponentResponseDTO component : critical) {
            if (!confirmed.contains(keyOf(component))) {
                return false;
            }
        }
        return !confirmed.isEmpty();
    }

    private static List<String> segmentsOf(String repeated) {
        List<String> segments = new ArrayList<>();
        for (String part : SEGMENT_SPLIT.split(repeated.trim())) {
            if (!part.isBlank()) {
                segments.add(part.trim());
            }
        }
        return segments;
    }

    /** The component whose code or name (the longest that fits) starts this segment, or null. */
    private static LabResultComponentResponseDTO namedIn(List<LabResultComponentResponseDTO> components,
                                                         String segment) {
        LabResultComponentResponseDTO best = null;
        int bestLength = 0;
        for (LabResultComponentResponseDTO component : components) {
            int length = component == null ? 0 : labelLength(component, segment);
            if (length > bestLength) {
                best = component;
                bestLength = length;
            }
        }
        return best;
    }

    /** Length of this component's code or name at the start of the segment (0 when neither). */
    private static int labelLength(LabResultComponentResponseDTO component, String segment) {
        return Math.max(prefixLength(component.getCode(), segment), prefixLength(component.getName(), segment));
    }

    /**
     * The length of {@code label} at the start of {@code segment}, accents and
     * case aside, when it ends there as a word (not "HB" in "HBA1c"); else 0.
     */
    private static int prefixLength(String label, String segment) {
        if (label == null || label.isBlank()) {
            return 0;
        }
        String wanted = fold(label.trim());
        for (int end = 1; end <= segment.length(); end++) {
            String head = fold(segment.substring(0, end));
            if (head.length() > wanted.length()) {
                return 0;
            }
            if (head.equals(wanted)) {
                boolean wordEnds = end == segment.length() || !Character.isLetter(segment.charAt(end));
                return wordEnds ? end : 0;
            }
        }
        return 0;
    }

    private static String fold(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
            .toLowerCase(Locale.ROOT);
    }

    private static String keyOf(LabResultComponentResponseDTO component) {
        return component.getCode() != null ? component.getCode().toUpperCase(Locale.ROOT)
            : String.valueOf(component.getName());
    }
}
