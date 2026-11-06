package com.example.hms.architecture;

import com.example.hms.controller.support.ReceptionistResponseShaping;
import com.example.hms.testsupport.ControllerMappingRegistry;
import com.example.hms.testsupport.RoleExpression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCPT-CLIN (user decision 2026-11-06): a receptionist does not read clinical
 * content. Three rules over Spring's mapping registry and every handler's real
 * {@code @PreAuthorize}, evaluated for the role:
 * <ol>
 *   <li>the clinical surfaces refuse the role, and admit a doctor;</li>
 *   <li>a response the role can receive (read or write, walked through its
 *       generics and nested types) has no field whose name reads as clinical,
 *       unless {@link ReceptionistResponseShaping#REMOVED} takes it out or
 *       {@link #THE_DESK_NEEDS_IT} says why the desk keeps it;</li>
 *   <li>a handler that declares no {@code @PreAuthorize} (the URL rules decide)
 *       answers nothing clinical either, by rule 2 run on it too.</li>
 * </ol>
 */
@DisplayName("RCPT-CLIN: no clinical content reaches a receptionist")
class ReceptionistClinicalReadGuardTest {

    private static final String RECEPTIONIST = "ROLE_RECEPTIONIST";

    private static final Pattern CLINICAL_FIELD = Pattern.compile(
        "(?i).*(allerg|diagnos|vital|medication|prescri|labresult|labresults|imaging|narrative|organism|acuity|esiscore"
            + "|soap|assessment|chiefcomplaint|problem|complaint|clinical|historysummary|careteamnotes|reason|notes?"
            + "|followupinstruction|treatment|symptom|immuniz|procedure|bloodtype|gestation).*");

    /** The surfaces refused outright. */
    private static final List<String> REFUSED = List.of(
        "ChartReviewController#getChartReview",
        "PatientStoryboardController#getStoryboard",
        "DigitalSignatureController#getSignaturesByReport",
        "DigitalSignatureController#getSignatureById",
        "DigitalSignatureController#isReportSigned",
        "DigitalSignatureController#verifySignature",
        "RecordAccessController#decide");

    /**
     * Type#field the role still receives, and why: what the desk's workflow reads
     * today. Each is reported to the user as a judgment call. Own-record types
     * ({@code /me/**}) are not the desk's reach and are skipped by name below.
     */
    private static final Map<String, String> THE_DESK_NEEDS_IT = Map.ofEntries(
        Map.entry("ReceptionQueueItemDTO#appointmentReason", "the visit reason on the queue, walk-in complaint or triage's when none was booked"),
        Map.entry("AppointmentResponseDTO#reason", "the visit reason the desk books and reschedules"),
        Map.entry("AppointmentResponseDTO#notes", "booking notes the desk writes"),
        Map.entry("AppointmentResponseDTO#statusReason", "why the appointment was cancelled or moved"),
        Map.entry("AppointmentResponseDTO#treatmentId", "the booked service"),
        Map.entry("AppointmentResponseDTO#treatmentName", "the booked service"),
        Map.entry("AppointmentResponseDTO#treatmentDescription", "the booked service"),
        Map.entry("AppointmentSummaryDTO#notes", "booking notes the desk writes"),
        Map.entry("PatientResponseDTO#bloodType", "the desk records and corrects it on the demographics dialog"),
        Map.entry("EncounterResponseDTO#chiefComplaint", "the desk types it at walk-in and check-in"),
        Map.entry("EncounterResponseDTO#appointmentReason", "the booked visit reason"),
        Map.entry("EncounterResponseDTO#appointmentNotes", "booking notes the desk writes"),
        Map.entry("RecallResponseDTO#reason", "the desk phones the patient about the recall"),
        Map.entry("RecallResponseDTO#notes", "the desk's own recall notes"),
        Map.entry("RecallResponseDTO#closeReason", "the desk closes recalls"),
        Map.entry("WaitlistEntryResponseDTO#reason", "the visit reason of the waitlist entry"),
        Map.entry("WaitlistEntryResponseDTO#closeReason", "the desk closes entries"),
        Map.entry("RoiRequestResponseDTO#decisionNote", "the outcome of a release request the desk took"),
        Map.entry("TreatmentConsentResponseDTO#revocationReason", "the desk records consents and revocations"),
        Map.entry("BedBoardDTO.BedBoardEntryDTO#notes", "a note about the bed, not the patient"),
        Map.entry("BillingInvoiceResponseDTO#notes", "billing notes"),
        Map.entry("PaymentTransactionResponseDTO#notes", "cashier notes"),
        Map.entry("InvoiceAdjustmentResponseDTO#reason", "why a discount was given"),
        Map.entry("InvoiceAdjustmentResponseDTO#reversalReason", "why a discount was reversed"),
        Map.entry("AppointmentCalendarEventDTO#reason", "the visit reason on the desk's calendar"),
        Map.entry("BedResponseDTO#notes", "a note about the bed, not the patient"),
        Map.entry("BillingInvoiceResponseDTO#cancelReason", "why the invoice was cancelled"),
        Map.entry("BreakGlassSessionResponseDTO#reason", "the caller's own session (/break-glass/me)"),
        Map.entry("BreakGlassSessionResponseDTO#reviewNote", "the caller's own session (/break-glass/me)"),
        Map.entry("BreakGlassSessionResponseDTO#revokeReason", "the caller's own session (/break-glass/me)"),
        Map.entry("CheckInResponseDTO#chiefComplaint", "what the desk itself just typed at check-in"),
        Map.entry("EducationResourceResponseDTO#culturalNotes", "a library resource, nobody's record"),
        Map.entry("GuarantorResponseDTO#deactivationReason", "administrative: who pays"),
        Map.entry("GuarantorResponseDTO#notes", "administrative: who pays"),
        Map.entry("OnCallScheduleResponseDTO#notes", "a note on the rota"),
        Map.entry("PatientEducationProgressResponseDTO#providerNotes", "the answer to a progress the desk itself just posted"),
        Map.entry("PatientEducationQuestionResponseDTO#providerNotes", "the answer to a question the desk itself just posted"),
        Map.entry("PatientResponseDTO#allergiesWithheld", "a flag, no allergy in it"),
        Map.entry("PatientResponseDTO.VitalSnapshot#clinicallySignificant", "inside lastVitals, which is removed whole"),
        Map.entry("PatientResponseDTO.VitalSnapshot#notes", "inside lastVitals, which is removed whole"),
        Map.entry("PaymentTransactionResponseDTO#reversalReason", "why a payment was reversed"),
        Map.entry("PrenatalAppointmentSummaryDTO#gestationalWeek", "the desk books prenatal visits by the week; reported as a judgment call"),
        Map.entry("PrenatalAppointmentSummaryDTO#reason", "the visit reason of a booked prenatal appointment"),
        Map.entry("PrenatalScheduleResponseDTO#currentGestationalWeek", "the desk books prenatal visits by the week; reported as a judgment call"),
        Map.entry("PrenatalVisitRecommendationDTO#gestationalWeek", "the desk books prenatal visits by the week; reported as a judgment call"),
        Map.entry("PrenatalVisitRecommendationDTO#notes", "the fixed text of the recommended cadence, nobody's record"),
        Map.entry("RecordSharingOptOutDTO#reason", "the patient's stated reason, recorded at the desk"),
        Map.entry("ServiceTranslationResponseDTO#treatmentId", "the public service catalogue"),
        Map.entry("ServiceTranslationResponseDTO#treatmentName", "the public service catalogue"),
        Map.entry("SessionTemplateResponseDTO#notes", "a note on the schedule template"),
        Map.entry("StaffLeaveResponseDTO#managerNote", "staff rota"),
        Map.entry("StaffLeaveResponseDTO#reason", "staff rota"),
        Map.entry("StaffShiftResponseDTO#cancellationReason", "staff rota"),
        Map.entry("StaffShiftResponseDTO#notes", "staff rota"),
        Map.entry("TreatmentConsentResponseDTO#notes", "the desk records consents"),
        Map.entry("UserRecoveryContactDTO#notes", "the caller's own account"));

    private static Map<String, HandlerMethod> handlers() {
        Map<String, HandlerMethod> out = new TreeMap<>();
        ControllerMappingRegistry.mapping().getHandlerMethods().forEach((info, handler) ->
            out.put(info.getMethodsCondition().getMethods() + " " + info.getPathPatternsCondition()
                + " " + handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName(), handler));
        return out;
    }

    private static String name(HandlerMethod handler) {
        return handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
    }

    @Test
    @DisplayName("chart review, the storyboard, signatures and the record-access decision refuse the role and admit a doctor")
    void theClinicalSurfacesRefuseTheRole() {
        Set<String> seen = new TreeSet<>();
        for (HandlerMethod handler : handlers().values()) {
            if (REFUSED.contains(name(handler))) {
                seen.add(name(handler));
                assertThat(RoleExpression.admits(handler, RECEPTIONIST)).as(name(handler)).isEqualTo(RoleExpression.NO);
                assertThat(RoleExpression.admits(handler, "ROLE_DOCTOR")).as(name(handler)).isEqualTo(RoleExpression.YES);
            }
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(REFUSED);
    }

    @Test
    @DisplayName("no expression is too clever to read: every handler answers YES, NO or NONE for the role")
    void everyExpressionIsReadable() {
        Set<String> custom = new TreeSet<>();
        handlers().forEach((key, handler) -> {
            if (RoleExpression.CUSTOM.equals(RoleExpression.admits(handler, RECEPTIONIST))) {
                custom.add(key);
            }
        });
        assertThat(custom).isEmpty();
    }

    @Test
    @DisplayName("a response the role can receive embeds no clinical field that is neither removed nor reasoned")
    void noClinicalFieldReachesTheRole() {
        Set<String> found = new TreeSet<>();
        Set<String> used = new HashSet<>();
        for (HandlerMethod handler : handlers().values()) {
            String admits = RoleExpression.admits(handler, RECEPTIONIST);
            boolean ownRecord = handler.getBeanType().getSimpleName().equals("PatientPortalController")
                || handler.getBeanType().getSimpleName().equals("MeController");
            if (ownRecord || !(RoleExpression.YES.equals(admits) || RoleExpression.NONE.equals(admits))) {
                continue;
            }
            walk(handler.getMethod().getGenericReturnType(), new HashSet<>(), name(handler), found, used);
        }
        assertThat(found).as("remove it in ReceptionistResponseShaping, refuse the role, or say why the desk needs it").isEmpty();
        Set<String> stale = new TreeSet<>(THE_DESK_NEEDS_IT.keySet());
        stale.removeAll(used);
        assertThat(stale).as("listed, and the role no longer receives it").isEmpty();
    }

    private static void walk(Type type, Set<Class<?>> seen, String handler, Set<String> found, Set<String> used) {
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                walk(bound, seen, handler, found, used);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            walk(parameterized.getRawType(), seen, handler, found, used);
            for (Type argument : parameterized.getActualTypeArguments()) {
                walk(argument, seen, handler, found, used);
            }
        } else if (type instanceof Class<?> raw && raw.getName().startsWith("com.example.hms.") && !raw.isEnum()
                && seen.add(raw)) {
            for (Field field : raw.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                judge(raw, field, handler, found, used);
                walk(field.getGenericType(), seen, handler, found, used);
            }
        }
    }

    private static void judge(Class<?> owner, Field field, String handler, Set<String> found, Set<String> used) {
        if (!CLINICAL_FIELD.matcher(field.getName()).matches()) {
            return;
        }
        Set<String> removed = ReceptionistResponseShaping.REMOVED.get(owner);
        if (removed != null && removed.contains(field.getName())) {
            return;
        }
        String typeName = owner.getName().substring(owner.getName().lastIndexOf('.') + 1).replace('$', '.');
        String key = typeName + "#" + field.getName();
        if (THE_DESK_NEEDS_IT.containsKey(key)) {
            used.add(key);
        } else {
            found.add(key + " via " + handler);
        }
    }
}
