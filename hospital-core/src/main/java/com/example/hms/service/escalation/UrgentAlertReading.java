package com.example.hms.service.escalation;

import com.example.hms.model.Hospital;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.utility.HospitalCalendar;
import org.springframework.context.MessageSource;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * QA D4105 (r81): what was measured, and when, in the text of an urgent alert raised from a reading. A postpartum
 * or newborn alert gave its rule (« systolique ≥ 160 mmHg ou diastolique ≥ 110 mmHg ») and not the value: three
 * readings of one woman were three identical items, and the reader had to open the chart to know how high.
 *
 * <p>The alert sentence stays the rule's own; this class wraps it: {@code urgentAlert.withReading} is the sentence,
 * the values ({@code urgentAlert.reading.<kind>}) and the time of the reading. It travels as a key with its
 * arguments, like every alert sentence (QA D665), so each reader gets the values written her way (« 35,4 » in
 * French, « 35.4 » in English). A reading with none of the values of its kind keeps the sentence and the time.
 *
 * <p>Every urgent alert filed from a reading goes through here ({@code UrgentAlertReadingGuardTest}).
 */
public final class UrgentAlertReading {

    /** {0} the alert sentence, {1} the measured values, {2} the time of the reading. */
    public static final String WITH_READING_KEY = "urgentAlert.withReading";
    /** {0} the alert sentence, {1} the time of the reading: a finding with no number behind it. */
    public static final String WITH_TIME_KEY = "urgentAlert.withTime";
    public static final String BLOOD_PRESSURE_KEY = "urgentAlert.reading.bloodPressure";
    public static final String HAEMORRHAGE_KEY = "urgentAlert.reading.haemorrhage";
    public static final String TEMPERATURE_KEY = "urgentAlert.reading.temperature";
    public static final String HEART_RATE_KEY = "urgentAlert.reading.heartRate";
    public static final String RESPIRATORY_KEY = "urgentAlert.reading.respiratory";
    public static final String OXYGENATION_KEY = "urgentAlert.reading.oxygenation";
    public static final String GLUCOSE_KEY = "urgentAlert.reading.glucose";
    public static final String APGAR_KEY = "urgentAlert.reading.apgar";

    /** Every key of this class: each has its text in every language ({@code UrgentAlertReadingGuardTest}). */
    public static final List<String> KEYS = List.of(WITH_READING_KEY, WITH_TIME_KEY, BLOOD_PRESSURE_KEY,
        HAEMORRHAGE_KEY, TEMPERATURE_KEY, HEART_RATE_KEY, RESPIRATORY_KEY, OXYGENATION_KEY, GLUCOSE_KEY, APGAR_KEY);

    /** A value of the kind that was not measured on this reading. */
    private static final String NOT_MEASURED = "–";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final String valuesKey;
    private final Object[] values;
    private final String time;

    private UrgentAlertReading(String valuesKey, Object[] values, String time) {
        this.valuesKey = valuesKey;
        this.values = values;
        this.time = time == null ? "" : time;
    }

    /** The alert sentence as it travels: its key and the arguments a row may store. */
    public record Sentence(String key, List<Object> args) {
        public Object[] argsArray() {
            return args == null ? null : args.toArray();
        }
    }

    /** The values of an alert type of {@code source} ({@code NewbornAlertType}, {@code PostpartumAlertType}). */
    public static UrgentAlertReading newborn(String alertType, Double temperature, Integer heartRate,
                                             Integer respirations, Integer spo2, Integer glucoseMgDl,
                                             Integer[] apgar, String time) {
        if (alertType == null) {
            return none(time);
        }
        return switch (alertType) {
            case "THERMOREGULATION" -> of(TEMPERATURE_KEY, time, temperature);
            case "CARDIOVASCULAR" -> of(HEART_RATE_KEY, time, heartRate);
            case "RESPIRATORY" -> of(RESPIRATORY_KEY, time, respirations);
            case "OXYGENATION" -> of(OXYGENATION_KEY, time, spo2);
            case "GLUCOSE" -> of(GLUCOSE_KEY, time, glucoseMgDl);
            case "APGAR" -> apgar == null ? none(time) : of(APGAR_KEY, time, (Object[]) apgar);
            default -> none(time);
        };
    }

    public static UrgentAlertReading postpartum(String alertType, Integer systolic, Integer diastolic, Integer pulse,
                                                Integer bloodLossMl, Double temperature, String time) {
        if (alertType == null) {
            return none(time);
        }
        return switch (alertType) {
            case "HYPERTENSION" -> bloodPressure(systolic, diastolic, time);
            case "HEMORRHAGE" -> of(HAEMORRHAGE_KEY, time, systolic, diastolic, pulse, bloodLossMl);
            case "INFECTION" -> of(TEMPERATURE_KEY, time, temperature);
            default -> none(time);
        };
    }

    public static UrgentAlertReading bloodPressure(Integer systolic, Integer diastolic, String time) {
        return of(BLOOD_PRESSURE_KEY, time, systolic, diastolic);
    }

    /** A finding with no number behind it (a danger sign, a checkbox): the sentence and the time. */
    public static UrgentAlertReading none(String time) {
        return new UrgentAlertReading(null, null, time);
    }

    private static UrgentAlertReading of(String valuesKey, String time, Object... values) {
        if (values == null || Arrays.stream(values).allMatch(Objects::isNull)) {
            return none(time);
        }
        Object[] shown = Arrays.stream(values).map(value -> value == null ? NOT_MEASURED : value).toArray();
        return new UrgentAlertReading(valuesKey, shown, time);
    }

    /**
     * The clock time of a reading at its hospital. The record holds the server's wall time; the reader is at the
     * hospital, whose own zone is used ({@link HospitalCalendar}); the server's zone for a reading with no hospital.
     */
    public static String timeOf(LocalDateTime takenAt, Hospital hospital) {
        if (takenAt == null) {
            return "";
        }
        ZoneId server = ZoneId.systemDefault();
        ZoneId zone = hospital == null ? server : HospitalCalendar.zoneOf(hospital, Clock.system(server));
        return takenAt.atZone(server).withZoneSameInstant(zone).format(CLOCK);
    }

    /** The sentence {@code ruleKey} with this reading. An alert with no key has none to wrap: null. */
    public Sentence wrap(String ruleKey, Object[] ruleArgs) {
        if (ruleKey == null || ruleKey.isBlank()) {
            return null;
        }
        Object rule = NotificationLocalizer.key(ruleKey, ruleArgs);
        Object[] args = valuesKey == null ? new Object[]{rule, time}
            : new Object[]{rule, NotificationLocalizer.key(valuesKey, values), time};
        return new Sentence(valuesKey == null ? WITH_TIME_KEY : WITH_READING_KEY, NotificationLocalizer.storable(args));
    }

    /**
     * The key of the rule's own sentence behind an alert's message key: the sentence this class wrapped, or the
     * key itself for an alert that was never wrapped. For a reader that tells alerts apart by their rule.
     */
    public static String ruleKey(String messageKey, List<Object> messageArgs) {
        boolean wrapped = WITH_READING_KEY.equals(messageKey) || WITH_TIME_KEY.equals(messageKey);
        if (wrapped && messageArgs != null && !messageArgs.isEmpty()
                && messageArgs.get(0) instanceof java.util.Map<?, ?> rule && rule.get("key") instanceof String key) {
            return key;
        }
        return messageKey;
    }

    /** The same sentence as text in {@code locale}, around the rule's sentence already rendered in it. */
    public String text(MessageSource messages, Locale locale, String ruleSentence) {
        String sentence = ruleSentence == null ? "" : ruleSentence;
        if (valuesKey == null) {
            return messages.getMessage(WITH_TIME_KEY, new Object[]{sentence, time}, locale);
        }
        return messages.getMessage(WITH_READING_KEY,
            new Object[]{sentence, messages.getMessage(valuesKey, values, locale), time}, locale);
    }
}
