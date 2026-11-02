package com.example.hms.service.impl;

import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.beans.PropertyDescriptor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1151 / D1181 (rounds 21 and 22): a postpartum observation with no vital
 * sign (a mood, a note) reset the 15-minute check clock of a haemorrhaging
 * mother and cleared "En retard". The clock moves only for a monitoring check
 * ({@code PostpartumCareServiceImpl.isMonitoringCheck}). This classifies EVERY
 * property of the observation request: each one is a check, not a check, or
 * metadata, and each is proven against the real rule - a property set alone
 * (to a value, or a ticked box) is or is not a check exactly as listed. A new
 * request property fails here until someone decides whether it moves the clock.
 */
class PostpartumMonitoringCheckGuardTest {

    /** A vital sign or an assessment of the uterus or the bleeding: moves the clock. */
    private static final Set<String> CHECKS = Set.of(
        "temperatureCelsius", "systolicBpMmHg", "diastolicBpMmHg", "pulseBpm", "respirationsPerMin",
        "fundusHeightCm", "fundusTone", "lochiaAmount", "lochiaCharacter", "estimatedBloodLossMl",
        "excessiveBleeding", "uterineAtonySuspected", "uterineTenderness", "foulLochiaOdor");

    /** Recorded, but checks nothing: never moves the clock alone. */
    private static final Set<String> NOT_CHECKS = Set.of(
        "painScore", "bladderStatus", "lochiaNotes", "perineumFindings", "uterotonicGiven",
        "hemorrhageProtocolActivated", "chillsOrRigors", "moodStatus", "supportStatus", "sleepStatus",
        "psychosocialNotes", "mentalHealthReferralSuggested", "socialSupportReferralSuggested",
        "painManagementReferralSuggested", "educationTopics", "educationNotes", "educationCompleted",
        "dischargeChecklistComplete", "postpartumVisitDate", "hemorrhageProtocolConfirmed",
        "uterotonicAvailabilityConfirmed", "rhImmunoglobulinCompleted", "immunizationsUpdated",
        "contactInfoVerified", "followUpContactMethod", "dischargeSafetyNotes", "stabilizationConfirmed",
        "shiftFrequencyMinutes", "enhancedMonitoring", "enhancedMonitoringFrequencyMinutes",
        "enhancedMonitoringResolved");

    /** References, times of record and sign-off: not findings at all. */
    private static final Set<String> METADATA = Set.of(
        "class", "carePlanId", "hospitalId", "registrationId", "recordedByStaffId", "supersedesObservationId",
        "observationTime", "deliveryOccurredAt", "lateEntry", "originalEntryTime", "signoffName",
        "signoffCredentials", "signedAt");

    @Test
    void everyRequestPropertyIsClassifiedForTheClock() {
        Set<String> unclassified = new TreeSet<>();
        for (PropertyDescriptor p : new BeanWrapperImpl(new PostpartumObservationRequestDTO()).getPropertyDescriptors()) {
            String name = p.getName();
            if (!CHECKS.contains(name) && !NOT_CHECKS.contains(name) && !METADATA.contains(name)) {
                unclassified.add(name);
            }
        }
        assertThat(unclassified)
            .as("postpartum request properties nobody decided about: does each alone move the 15-minute clock"
                + " (QA D1181)? Add it to CHECKS (and isMonitoringCheck) or NOT_CHECKS")
            .isEmpty();
    }

    @Test
    void eachPropertyAloneIsACheckExactlyAsListed() {
        List<String> wrong = new ArrayList<>();
        BeanWrapper template = new BeanWrapperImpl(new PostpartumObservationRequestDTO());
        for (PropertyDescriptor p : template.getPropertyDescriptors()) {
            String name = p.getName();
            if (METADATA.contains(name) || p.getWriteMethod() == null) {
                continue;
            }
            PostpartumObservationRequestDTO request = new PostpartumObservationRequestDTO();
            request.setEducationTopics(new java.util.HashSet<>());
            new BeanWrapperImpl(request).setPropertyValue(name, sample(p.getPropertyType(), p));
            boolean check = PostpartumCareServiceImpl.isMonitoringCheck(request);
            if (check != CHECKS.contains(name)) {
                wrong.add(name + (check ? " moves the clock but is listed as not a check"
                    : " is listed as a check but does not move the clock"));
            }
        }
        assertThat(wrong).as("QA D1181: the clock rule and this list disagree").isEmpty();
    }

    @Test
    void anUntickedBoxIsNeverACheck() {
        PostpartumObservationRequestDTO request = new PostpartumObservationRequestDTO();
        request.setExcessiveBleeding(Boolean.FALSE);
        request.setUterineAtonySuspected(Boolean.FALSE);
        request.setUterineTenderness(Boolean.FALSE);
        request.setFoulLochiaOdor(Boolean.FALSE);
        assertThat(PostpartumCareServiceImpl.isMonitoringCheck(request)).isFalse();
    }

    private static Object sample(Class<?> type, PropertyDescriptor p) {
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.TRUE;
        }
        if (type == Integer.class || type == int.class) {
            return 1;
        }
        if (type == Double.class || type == double.class) {
            return 37.0;
        }
        if (type == String.class) {
            return "x";
        }
        if (type == UUID.class) {
            return UUID.randomUUID();
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.of(2026, 11, 1, 10, 0);
        }
        if (type == LocalDate.class) {
            return LocalDate.of(2026, 11, 8);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (Collection.class.isAssignableFrom(type)) {
            java.lang.reflect.Type generic = p.getReadMethod().getGenericReturnType();
            Class<?> element = (Class<?>) ((java.lang.reflect.ParameterizedType) generic).getActualTypeArguments()[0];
            return new java.util.HashSet<>(Set.of(element.getEnumConstants()[0]));
        }
        throw new IllegalStateException("no sample for " + p.getName() + " of " + type);
    }
}
