package com.example.hms.service.support;

import com.example.hms.exception.FieldBusinessException;

import java.util.Collection;
import java.util.Map;

/**
 * QA D1148 / D1150 / D1151 / D1158 (round 21): the server accepted clinical
 * writes with nothing in them - a blank ultrasound report shown as "Normal",
 * blank postpartum observations counted as the 15-minute checks of a
 * haemorrhaging woman, a triage with an all-null vitals row, an empty nursing
 * intake, an encounter note with every section empty, a history version with
 * no content. Every clinical write that records findings goes through
 * {@link #requireAny}: at least one finding must be present, or the write is
 * refused with a translated message naming what is missing.
 * {@code EmptyClinicalWriteGuardTest} fails on a clinical write endpoint whose
 * body can be empty and whose service does not call this.
 */
public final class ClinicalContent {

    private ClinicalContent() {
    }

    /**
     * Refuses the write when none of {@code findings} carries a value. A value
     * is present when it is not null, not a blank string, not an empty
     * collection or map, and not {@code Boolean.FALSE} (an unticked box
     * records nothing).
     *
     * @param field      the request property the refusal is reported against
     * @param messageKey the translated refusal, which names what to enter
     */
    public static void requireAny(String field, String messageKey, Object... findings) {
        if (findings != null) {
            for (Object finding : findings) {
                if (isPresent(finding)) {
                    return;
                }
            }
        }
        throw new FieldBusinessException(field, messageKey);
    }

    /** Whether one finding carries a value ({@link #requireAny}). */
    public static boolean isPresent(Object finding) {
        if (finding == null || Boolean.FALSE.equals(finding)) {
            return false;
        }
        if (finding instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        if (finding instanceof Collection<?> collection) {
            return collection.stream().anyMatch(ClinicalContent::isPresent);
        }
        if (finding instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    /**
     * Refuses a request none of whose fields, other than the {@code metadata}
     * ones (ids, dates of record, version flags), carries a value. For a form
     * of many optional answers (a social-history version), where a deliberate
     * "no" ({@code Boolean.FALSE}) is an answer too: here only null and blank
     * count as absent.
     */
    public static void requireAnyAnswer(Object request, java.util.Set<String> metadata, String field,
                                        String messageKey) {
        if (request != null) {
            org.springframework.beans.BeanWrapper bean = new org.springframework.beans.BeanWrapperImpl(request);
            for (java.beans.PropertyDescriptor property : bean.getPropertyDescriptors()) {
                String name = property.getName();
                if (property.getReadMethod() == null || "class".equals(name) || metadata.contains(name)) {
                    continue;
                }
                if (isAnswer(bean.getPropertyValue(name))) {
                    return;
                }
            }
        }
        throw new FieldBusinessException(field, messageKey);
    }

    private static boolean isAnswer(Object value) {
        return value instanceof Boolean || isPresent(value);
    }

    /** Refuses a required single field that is absent or blank (a consent's signer, a directive's type). */
    public static void requirePresent(Object value, String field, String messageKey) {
        if (!isPresent(value)) {
            throw new FieldBusinessException(field, messageKey);
        }
    }
}
