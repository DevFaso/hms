package com.example.hms.service.escalation;

import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert;
import com.example.hms.utility.MessageUtil;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Scorecard K4: the urgent alerts that are neither obstetric nor an early-warning score, as the escalation path
 * takes them (a severe transfusion reaction, a positive culture, an adult's urgent hypertension, a countersignature
 * past its deadline). One place builds them, so each has a type label, a sentence and a title under the same keys
 * ({@code urgentAlert.type.<TYPE>}, {@code urgentAlert.msg.<TYPE>} or the sentence of the alert's own rule,
 * {@code urgentAlert.notification}).
 *
 * <p>The texts built here are the staff-language copy the row stores: they title the in-basket item and stand in
 * for a reader whose language has no translation. Every reader's own notice and item are rendered from the keys,
 * in her language.
 */
public final class UrgentAlerts {

    public static final String TYPE_SEVERE_TRANSFUSION_REACTION = "SEVERE_TRANSFUSION_REACTION";
    public static final String TYPE_POSITIVE_CULTURE = "POSITIVE_CULTURE";
    public static final String TYPE_URGENT_HYPERTENSION = "URGENT_HYPERTENSION";
    public static final String TYPE_O_POSITIVE_COUNTERSIGN_OVERDUE = "O_POSITIVE_COUNTERSIGN_OVERDUE";
    public static final String TYPE_INCOMPATIBLE_COUNTERSIGN_OVERDUE = "INCOMPATIBLE_COUNTERSIGN_OVERDUE";

    /** Every alert type built here: each has its label in every language ({@code UrgentClinicalMessageGuardTest}). */
    public static final List<String> TYPES = List.of(TYPE_SEVERE_TRANSFUSION_REACTION, TYPE_POSITIVE_CULTURE,
        TYPE_URGENT_HYPERTENSION, TYPE_O_POSITIVE_COUNTERSIGN_OVERDUE, TYPE_INCOMPATIBLE_COUNTERSIGN_OVERDUE);

    /**
     * The types whose sentence is {@code urgentAlert.msg.<TYPE>}; an adult's urgent hypertension keeps the sentence
     * of its own rule.
     */
    public static final List<String> TYPES_WITH_OWN_SENTENCE = List.of(TYPE_SEVERE_TRANSFUSION_REACTION,
        TYPE_POSITIVE_CULTURE, TYPE_O_POSITIVE_COUNTERSIGN_OVERDUE, TYPE_INCOMPATIBLE_COUNTERSIGN_OVERDUE);

    public static final String MESSAGE_PREFIX = "urgentAlert.msg.";
    public static final String TYPE_PREFIX = "urgentAlert.type.";
    private static final String NOTICE_KEY = "urgentAlert.notification";

    private UrgentAlerts() {
    }

    /** Whose alert it is: the hospital it is raised at, the patient, and the name the staff copy carries. */
    public record Subject(UUID hospitalId, UUID patientId, String patientName) {
    }

    /**
     * One alert of {@code sourceType} about the record {@code sourceId}, with the sentence of its type
     * ({@code urgentAlert.msg.<alertType>}).
     *
     * @param messageArgs        the sentence's arguments: numbers and text a row may store, never an entity
     * @param documentedByUserId who recorded what raised the alert: an acknowledgement counts only from somebody else
     */
    public static UrgentAlert of(Subject subject, String sourceType, UUID sourceId, String alertType,
                                 Object[] messageArgs, UUID documentedByUserId) {
        return withSentence(subject, sourceType, sourceId, alertType, MESSAGE_PREFIX + alertType, messageArgs,
            documentedByUserId);
    }

    /** The same, for an alert whose sentence belongs to the rule that raised it ({@code messageKey}). */
    public static UrgentAlert withSentence(Subject subject, String sourceType, UUID sourceId, String alertType,
                                           String messageKey, Object[] messageArgs, UUID documentedByUserId) {
        Locale staffLocale = NotificationLocales.STAFF;
        String patientName = subject.patientName() == null || subject.patientName().isBlank()
            ? MessageUtil.resolve(staffLocale, "patient.fallback.generic") : subject.patientName();
        Object[] args = messageArgs == null ? new Object[0] : messageArgs;
        List<Object> storedArgs = NotificationLocalizer.storable(args);
        String typeLabel = MessageUtil.resolve(staffLocale, TYPE_PREFIX + alertType);
        String message = MessageUtil.resolve(staffLocale, messageKey, args);
        String title = MessageUtil.resolve(staffLocale, NOTICE_KEY, typeLabel, patientName, message);
        return new UrgentAlert(subject.hospitalId(), subject.patientId(), patientName, sourceType, sourceId, alertType,
            typeLabel, message, documentedByUserId, title, messageKey, storedArgs.isEmpty() ? null : storedArgs);
    }
}
