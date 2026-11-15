package com.example.hms.architecture;

import com.example.hms.controller.MeController;
import com.example.hms.controller.PatientPortalController;
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
 *       answers nothing clinical either, by rule 2 run on it too;</li>
 *   <li>a request body the role can send holds no clinical field, unless
 *       {@link #THE_DESK_WRITES_IT} says what it does with it: a desk that cannot read a
 *       text must not overwrite it.</li>
 * </ol>
 */
@DisplayName("RCPT-CLIN: no clinical content reaches a receptionist")
class ReceptionistClinicalReadGuardTest {

    private static final String RECEPTIONIST = "ROLE_RECEPTIONIST";

    private static final Pattern CLINICAL_FIELD = Pattern.compile(
        "(?i).*(chronic|condition|risk|flag|allerg|diagnos|vital|medication|prescri|labresult|labresults|imaging|narrative|organism|acuity|esiscore"
            + "|soap|assessment|chiefcomplaint|problem|complaint|clinical|historysummary|careteamnotes|reason|notes?"
            + "|followupinstruction|treatment|symptom|immuniz|procedure|bloodtype|gestation"
            // User decision 2026-11-06: the desk's vocabulary, named one by one.
            + "|bloodgroup|abogroup|rhfactor|antibody|triage|isolation|highrisk"
            // QA D1421: a visit's text under a name that reads as nothing clinical.
            + "|encounterdescription|visitdescription|hospitalcourse|encountersummary).*");

    /** The surfaces refused outright. */
    private static final List<String> REFUSED = List.of(
        "ChartReviewController#getChartReview",
        "PatientStoryboardController#getStoryboard",
        "DigitalSignatureController#getSignaturesByReport",
        "DigitalSignatureController#getSignatureById",
        "DigitalSignatureController#isReportSigned",
        "DigitalSignatureController#verifySignature",
        "RecordAccessController#decide",
        // It writes the discharge diagnoses and the instructions, which the role cannot read.
        "EncounterController#checkOut",
        // QA D1431: ending a triage and declaring a patient ready to leave are clinical acts.
        "EncounterController#completeTriage",
        "EncounterController#markReadyForDischarge");

    /**
     * Type#field the role still receives, and why: what the desk's workflow reads
     * today. Each is reported to the user as a judgment call. Own-record types
     * ({@code /me/**}) are not the desk's reach and are skipped by name below.
     */
    private static final Map<String, String> THE_DESK_NEEDS_IT = Map.ofEntries(
        // User decision 2026-11-06: the isolation precaution stays on the bed board as a type, and the
        // yes/no facts around it place the patient. Organism and reason are removed by the shaping.
        Map.entry("BedBoardDTO.OccupantDTO#isolationPrecautions", "the precaution TYPE on the bed board, user decision 2026-11-06"),
        Map.entry("BedBoardDTO.OccupantDTO#isolationMismatch", "whether the bed fits the precaution: placement"),
        Map.entry("BedBoardDTO.OccupantDTO#requiresIsolationWard", "whether the precaution needs an isolation ward: placement"),
        Map.entry("BedBoardDTO.CensusDTO#patientsOnIsolation", "a count, nobody's record"),
        Map.entry("BedBoardDTO.WardBoardDTO#isolationCapable", "a fact about the ward, nobody's record"),
        Map.entry("IsolationPrecautionResponseDTO#requiresIsolationWard", "whether the precaution needs an isolation ward: placement"),
        Map.entry("EncounterResponseDTO#triageTimestamp", "when, not what: the desk's wait times"),
        Map.entry("PatientTrackerItemDTO#triageTimestamp", "when, not what: the desk's wait times"),
        Map.entry("PatientTrackerBoardDTO#triage", "the column of the tracker the desk reads; its rows are shaped"),
        Map.entry("TransferOrderResponseDTO#destinationIsolationMismatch", "whether the bed fits the precaution: placement"),
        Map.entry("TransferOrderResponseDTO#isolationOverride", "a yes/no on a transfer the desk places; the reason is removed"),
        Map.entry("TransferOrderResponseDTO#isolationPrecautions", "the precaution TYPES on a transfer, as on the bed board"),
        Map.entry("ReceptionQueueItemDTO#appointmentReason", "the visit reason on the queue; for the role the shaping puts deskAppointmentReason here, never triage's complaint"),
        Map.entry("ReceptionQueueItemDTO#deskAppointmentReason", "never serialized: the booked reason or what the desk itself typed"),
        Map.entry("AppointmentResponseDTO#reason", "the visit reason the desk books and reschedules"),
        Map.entry("AppointmentResponseDTO#notes", "booking notes the desk writes"),
        Map.entry("AppointmentResponseDTO#statusReason", "why the appointment was cancelled or moved"),
        Map.entry("AppointmentResponseDTO#overbookReason", "QA D1429: why the desk booked outside the published slots"),
        Map.entry("PatientHospitalRegistrationResponseDTO#deactivationReason", "QA D1430: why the desk deactivated the registration"),
        Map.entry("AppointmentResponseDTO#treatmentId", "the booked service"),
        Map.entry("AppointmentResponseDTO#treatmentName", "the booked service"),
        Map.entry("AppointmentResponseDTO#treatmentDescription", "the booked service"),
        Map.entry("AppointmentSummaryDTO#notes", "booking notes the desk writes"),
        Map.entry("EncounterResponseDTO#chiefComplaint", "for the role the shaping puts deskChiefComplaint here, never triage's complaint"),
        Map.entry("EncounterResponseDTO#deskChiefComplaint", "never serialized: what the desk itself typed at walk-in and check-in"),
        Map.entry("EncounterResponseDTO#appointmentReason", "the booked visit reason"),
        Map.entry("EncounterResponseDTO#appointmentNotes", "booking notes the desk writes"),
        Map.entry("RecallResponseDTO#reason", "the desk phones the patient about the recall"),
        Map.entry("PatientDocumentResponseDTO#notes", "QA D1599: the patient's note on an upload, blanked by PatientDocumentServiceImpl for the desk"),
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
        Map.entry("PaymentTransactionResponseDTO#reversalReason", "why a payment was reversed"),
        Map.entry("PrenatalAppointmentSummaryDTO#gestationalWeek", "user decision 2026-11-06: the desk books prenatal visits by the week"),
        Map.entry("PrenatalAppointmentSummaryDTO#reason", "the visit reason of a booked prenatal appointment"),
        Map.entry("EducationResourceResponseDTO#isHighRiskRelevant", "a library resource, nobody's record"),
        Map.entry("PrenatalScheduleResponseDTO#currentGestationalWeek", "user decision 2026-11-06: the desk books prenatal visits by the week"),
        Map.entry("PrenatalVisitRecommendationDTO#gestationalWeek", "user decision 2026-11-06: the desk books prenatal visits by the week"),
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

    /**
     * Request type#field the role may send, and why it overwrites no clinician's text: a new
     * record, the desk's own administrative note, or a server that refuses or keeps the stored value.
     */
    private static final Map<String, String> THE_DESK_WRITES_IT = Map.ofEntries(
        Map.entry("AppointmentRequestDTO#notes", "booking notes the desk writes"),
        Map.entry("AppointmentRequestDTO#reason", "the visit reason the desk books"),
        Map.entry("AppointmentRequestDTO#statusReason", "why the appointment moved"),
        Map.entry("AppointmentRequestDTO#overbookReason", "QA D1429: why the desk books outside the published slots"),
        Map.entry("PatientHospitalRegistrationRequestDTO#deactivationReason", "QA D1430: why the desk deactivates the registration"),
        Map.entry("PrenatalRescheduleRequestDTO#overbookReason", "QA D1429: why the desk moves the visit outside the published slots"),
        Map.entry("AppointmentStatusChangeRequestDTO#reason", "why the appointment moved"),
        Map.entry("CheckInRequestDTO#chiefComplaint", "typed at check-in, before any clinician; a blank never clears it"),
        Map.entry("CheckInRequestDTO#notes", "the desk's check-in note"),
        Map.entry("CloseReasonRequestDTO#reason", "why the desk closed a recall or a waitlist entry"),
        Map.entry("CloseUnseenVisitRequestDTO#reason", "a visit nobody saw: no clinician's text exists"),
        Map.entry("EligibilityAttestationRequestDTO#eligibilityNotes", "the desk's own eligibility note"),
        Map.entry("EncounterNoteRequestDTO#chiefComplaint", "a NEW walk-in visit, nothing to overwrite (POST /encounters)"),
        Map.entry("EncounterNoteRequestDTO#linkedPrescriptionIds", "a NEW walk-in visit, nothing to overwrite (POST /encounters)"),
        Map.entry("EncounterRequestDTO#chiefComplaint", "a NEW walk-in visit, nothing to overwrite (POST /encounters)"),
        Map.entry("EncounterRequestDTO#note", "a NEW walk-in visit, nothing to overwrite (POST /encounters)"),
        Map.entry("EncounterRequestDTO#notes", "a NEW walk-in visit, nothing to overwrite (POST /encounters)"),
        Map.entry("GuarantorDeactivationRequestDTO#reason", "administrative: who pays"),
        Map.entry("GuarantorRequestDTO#notes", "administrative: who pays"),
        Map.entry("OptOutRequestDTO#reason", "the patient's stated reason, recorded at the desk"),
        Map.entry("PatientEducationProgressRequestDTO#providerNotes", "the desk's own progress row"),
        Map.entry("PatientHospitalRegistrationRequestDTO#readyForDischargeNote",
            "ignored for the desk: PatientHospitalRegistrationServiceImpl keeps the stored note"),
        Map.entry("PatientProfileUpdateRequestDTO#bloodType", "refused for the desk: PatientController#patchPatient"),
        Map.entry("PatientProfileUpdateRequestDTO#careTeamNotes", "refused for the desk: PatientController#patchPatient"),
        Map.entry("PatientProfileUpdateRequestDTO#chronicConditions", "refused for the desk: PatientController#patchPatient"),
        Map.entry("PatientRequestDTO#allergies", "a NEW patient: what the desk is told at registration, imported as rows; a PUT never writes it"),
        Map.entry("PatientRequestDTO#bloodType", "a NEW patient; on a PUT the desk's value is dropped and the stored one kept (PatientServiceImpl#updatePatient)"),
        Map.entry("PatientRequestDTO#careTeamNotes", "a NEW patient; on a PUT the desk's value is dropped and the stored one kept (PatientServiceImpl#updatePatient)"),
        Map.entry("PatientRequestDTO#chronicConditions", "a NEW patient; on a PUT the desk's value is dropped and the stored one kept (PatientServiceImpl#updatePatient)"),
        Map.entry("PatientRequestDTO#medicalHistorySummary", "a NEW patient; on a PUT the desk's value is dropped and the stored one kept (PatientServiceImpl#updatePatient)"),
        Map.entry("PaymentReasonRequestDTO#reason", "why a payment was refused"),
        Map.entry("PrenatalRescheduleRequestDTO#notes", "booking notes the desk writes"),
        Map.entry("PrenatalScheduleRequestDTO#highRisk", "ignored for the desk: PrenatalSchedulingServiceImpl, which plans from what clinicians recorded; a read-only calculation that stores nothing"),
        Map.entry("PrenatalScheduleRequestDTO#notes", "a read-only calculation; stores nothing"),
        Map.entry("RecallRequestDTO#notes", "the desk's own recall notes"),
        Map.entry("RecallRequestDTO#reason", "the desk's own recall"),
        Map.entry("RoiDecisionDTO#note", "the desk cancels a release request it took"),
        Map.entry("SlotBookingRequestDTO#reason", "the visit reason the desk books"),
        Map.entry("StaffLeaveRequestDTO#reason", "staff rota"),
        Map.entry("StaffPaymentRequestDTO#notes", "cashier notes"),
        Map.entry("TreatmentConsentRequestDTO#notes", "the desk records consents"),
        Map.entry("UserRecoveryContactRequestDTO#notes", "the caller's own account"),
        Map.entry("WaitlistEntryRequestDTO#reason", "the visit reason of the waitlist entry"));

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
            boolean ownRecord = PatientPortalController.class.isAssignableFrom(handler.getBeanType())
                || MeController.class.isAssignableFrom(handler.getBeanType());
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

    /** The holders ReceptionistResponseShaping#shape walks into: a shaped type anywhere else goes out unshaped. */
    private static final Set<Class<?>> SHAPE_WALKS_INTO = Set.of(
        com.example.hms.payload.dto.ApiResponseWrapper.class,
        com.example.hms.payload.dto.FlowBoardDTO.class,
        com.example.hms.payload.dto.bed.BedBoardDTO.BedBoardEntryDTO.class,
        com.example.hms.payload.dto.clinical.PatientTrackerBoardDTO.class);

    /**
     * The field rule trusts the REMOVED list by type, and the shaping switches on the body's own type: a
     * response that embeds a shaped type where the shaping does not walk would pass the first and leak.
     */
    @Test
    @DisplayName("a shaped type is only nested where the shaping reaches it")
    void aShapedTypeIsOnlyNestedWhereTheShapingReachesIt() {
        Set<Class<?>> shaped = new HashSet<>(ReceptionistResponseShaping.REMOVED.keySet());
        shaped.add(com.example.hms.payload.dto.ReceptionQueueItemDTO.class);
        Set<String> unreached = new TreeSet<>();
        for (HandlerMethod handler : handlers().values()) {
            String admits = RoleExpression.admits(handler, RECEPTIONIST);
            boolean ownRecord = PatientPortalController.class.isAssignableFrom(handler.getBeanType())
                || MeController.class.isAssignableFrom(handler.getBeanType());
            if (!ownRecord && (RoleExpression.YES.equals(admits) || RoleExpression.NONE.equals(admits))) {
                nested(handler.getMethod().getGenericReturnType(), new HashSet<>(), shaped, unreached);
            }
        }
        assertThat(unreached).as("teach ReceptionistResponseShaping#shape to walk into the holder").isEmpty();
    }

    private static void nested(Type type, Set<Class<?>> seen, Set<Class<?>> shaped, Set<String> unreached) {
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                nested(bound, seen, shaped, unreached);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                nested(argument, seen, shaped, unreached);
            }
        } else if (type instanceof Class<?> raw && raw.getName().startsWith("com.example.hms.") && !raw.isEnum()
                && seen.add(raw)) {
            for (Field field : raw.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Set<Class<?>> held = new HashSet<>();
                collect(field.getGenericType(), held);
                held.retainAll(shaped);
                if (!held.isEmpty() && !SHAPE_WALKS_INTO.contains(raw)) {
                    unreached.add(raw.getSimpleName() + "#" + field.getName());
                }
                nested(field.getGenericType(), seen, shaped, unreached);
            }
        }
    }

    private static void collect(Type type, Set<Class<?>> out) {
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                collect(argument, out);
            }
        } else if (type instanceof Class<?> raw) {
            out.add(raw);
        }
    }

    @Test
    @DisplayName("a body the role can send holds no clinical field that is neither refused, kept nor reasoned")
    void noClinicalFieldIsWrittenByTheRole() {
        Set<String> sent = new TreeSet<>();
        for (HandlerMethod handler : handlers().values()) {
            String admits = RoleExpression.admits(handler, RECEPTIONIST);
            if (!RoleExpression.YES.equals(admits) && !RoleExpression.NONE.equals(admits)) {
                continue;
            }
            for (org.springframework.core.MethodParameter parameter : handler.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class)) {
                    walkBody(parameter.getGenericParameterType(), new HashSet<>(), sent);
                }
            }
        }
        Set<String> unexplained = new TreeSet<>(sent);
        unexplained.removeAll(THE_DESK_WRITES_IT.keySet());
        assertThat(unexplained).as("refuse the role, keep the stored value, or say why the desk writes it").isEmpty();
        Set<String> stale = new TreeSet<>(THE_DESK_WRITES_IT.keySet());
        stale.removeAll(sent);
        assertThat(stale).as("listed, and the role no longer sends it").isEmpty();
    }

    /**
     * A high-risk flag the role can send: the desk's box does not count, and refusing to count it must not
     * leave the decision on a value nobody can correct. Each one names the class that decides from what
     * clinicians recorded instead; that class reads the flag only with the role taken out, and passes the
     * recorded value on to the decision. No other class calls such a flag's getter, by name or by method
     * reference. This holds the inventory and the wiring; {@code PrenatalRecordedRiskCadenceTest} holds the
     * behaviour.
     */
    private static final Map<String, String> THE_RECORD_DECIDES_INSTEAD = Map.of(
        "PrenatalScheduleRequestDTO#highRisk", "service/PrenatalSchedulingServiceImpl.java");
    private static final java.nio.file.Path MAIN = java.nio.file.Paths.get("src/main/java/com/example/hms");

    @Test
    @DisplayName("a high-risk flag the role can send is read in one place, which also reads what clinicians recorded")
    void aRiskFlagTheRoleCanSendDecidesNothingAlone() throws java.io.IOException {
        Set<String> sent = new TreeSet<>();
        for (HandlerMethod handler : handlers().values()) {
            String admits = RoleExpression.admits(handler, RECEPTIONIST);
            if (RoleExpression.YES.equals(admits) || RoleExpression.NONE.equals(admits)) {
                for (org.springframework.core.MethodParameter parameter : handler.getMethodParameters()) {
                    if (parameter.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class)) {
                        walkBody(parameter.getGenericParameterType(), new HashSet<>(), sent);
                    }
                }
            }
        }
        Set<String> flags = new TreeSet<>();
        sent.stream().filter(field -> field.toLowerCase(java.util.Locale.ROOT).contains("highrisk")).forEach(flags::add);
        assertThat(flags).as("a flag the desk sends, and where the record decides instead")
            .isEqualTo(new TreeSet<>(THE_RECORD_DECIDES_INSTEAD.keySet()));

        StringBuilder getters = new StringBuilder();
        for (String flag : flags) {
            String field = flag.substring(flag.indexOf('#') + 1);
            String capital = Character.toUpperCase(field.charAt(0)) + field.substring(1);
            getters.append(getters.isEmpty() ? "" : "|").append("get").append(capital).append("|is").append(capital);
        }
        Pattern readsTheFlag = Pattern.compile("(?:\\.|::)(?:" + getters + ")\\b");
        Set<String> readers = new TreeSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(MAIN)) {
            for (java.nio.file.Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (readsTheFlag.matcher(java.nio.file.Files.readString(file)).find()) {
                    readers.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertThat(readers).as("the classes that read the flag").isEqualTo(new TreeSet<>(THE_RECORD_DECIDES_INSTEAD.values()));

        for (String reader : readers) {
            String source = java.nio.file.Files.readString(MAIN.resolve(reader)).replaceAll("\\s+", " ");
            assertThat(source).as(reader + ": the box is not the desk's")
                .contains("Boolean.TRUE.equals(request.getHighRisk()) && !receptionist");
            assertThat(source).as(reader + ": the record reaches the cadence").contains("currentWeek, boxTicked || recordedHighRisk,");
        }
    }

    /**
     * QA D1421: the vocabulary above reads field NAMES, and an invoice's {@code encounterDescription}
     * held the visit's nursing text under a name that reads as nothing. So the check also stands at the
     * source, on the compiled calls (a variable's name cannot hide it): who reads an encounter's clinical
     * text at all, and that none of the classes behind a billing, front-desk or scheduling answer does.
     */
    private static final Set<String> ENCOUNTER_CLINICAL_READS = Set.of("getNotes", "getChiefComplaint",
        "getFollowUpInstructions", "getDischargeDiagnoses", "getEncounterNotes", "getEsiScore", "getExtraFields");

    private static final List<String> ENCOUNTER_OWNERS = List.of(
        "com/example/hms/model/Encounter", "com/example/hms/payload/dto/EncounterResponseDTO");

    /** A class behind an answer that is not a clinical record, by the name the codebase gives it. */
    private static final Pattern NON_CLINICAL_CLASS = Pattern.compile(
        ".*(Billing|Invoice|Payment|Receipt|Cashier|Copay|Claim|Insurance|Guarantor|Announcement|Registration"
            + "|Appointment|Reception|Waitlist|Recall|Slot|Reminder|Notification|Portal).*");

    /** Class#getter a non-clinical class still calls, and why it reaches nobody it should not. */
    private static final Map<String, String> NON_CLINICAL_CLASS_READS = Map.of(
        "ReceptionServiceImpl#Encounter.getChiefComplaint",
        "the queue's visit reason, which ReceptionistResponseShaping replaces with what the desk itself typed "
            + "for the role; a clinician on the queue reads it, and at check-in it is the desk's own text");

    /** Every class that reads Encounter.notes, and who receives it. A new reader is a decision, not a side effect. */
    private static final Map<String, String> ENCOUNTER_NOTES_READERS = Map.of(
        "AdmissionStayEncounterService", "fills an empty note from the stay's own discharge summary: a write, no answer",
        "ChartReviewServiceImpl", "compares with the prescription anchor; the chart review refuses the role (REFUSED above)",
        "ClinicalDashboardServiceImpl", "the roomed patients of the clinician's own dashboard",
        "DischargeSummaryServiceImpl", "the clinician's discharge summary",
        "EncounterMapper", "EncounterResponseDTO, whose notes ReceptionistResponseShaping removes for the role",
        "EncounterServiceImpl", "the encounter's history row and the after-visit summary's hospital course",
        "PatientServiceImpl", "the doctor's timeline: POST /patients/{id}/doctor-timeline admits ROLE_DOCTOR only",
        "PatientSnapshotServiceImpl", "the clinician's snapshot: GET /me/patients/{id}/snapshot admits clinicians only");

    private static Set<String> clinicalReads() throws java.io.IOException {
        Set<String> reads = new TreeSet<>();
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        for (org.springframework.core.io.Resource resource : resolver.getResources("classpath*:com/example/hms/**/*.class")) {
            String location = resource.getURL().toString();
            if (location.contains("/test/") || location.contains("/model/Encounter")
                    || location.contains("/dto/EncounterResponseDTO")) {
                continue;
            }
            try (java.io.InputStream in = resource.getInputStream()) {
                readsOf(new org.springframework.asm.ClassReader(in), reads);
            }
        }
        return reads;
    }

    private static void readsOf(org.springframework.asm.ClassReader reader, Set<String> reads) {
        String simple = reader.getClassName().substring(reader.getClassName().lastIndexOf('/') + 1).replaceFirst("[$].*", "");
        reader.accept(new org.springframework.asm.ClassVisitor(org.springframework.asm.SpringAsmInfo.ASM_VERSION) {
            @Override
            public org.springframework.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                     String signature, String[] exceptions) {
                return new org.springframework.asm.MethodVisitor(org.springframework.asm.SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if (ENCOUNTER_OWNERS.contains(owner) && ENCOUNTER_CLINICAL_READS.contains(method)) {
                            reads.add(simple + "#" + owner.substring(owner.lastIndexOf('/') + 1) + "." + method);
                        }
                    }
                };
            }
        }, org.springframework.asm.ClassReader.SKIP_DEBUG | org.springframework.asm.ClassReader.SKIP_FRAMES);
    }

    /** What the desk may write on an encounter: a new walk-in visit, and closing a visit nobody saw. */
    private static final Set<String> ENCOUNTER_WRITES_OF_THE_DESK = Set.of(
        "EncounterController#create", "EncounterController#closeUnseen");

    @Test
    @DisplayName("QA D1431: the only encounter writes the role reaches are a new walk-in and closing an unseen visit")
    void theDeskWritesNothingClinicalOnAnEncounter() {
        Set<String> writes = new TreeSet<>();
        ControllerMappingRegistry.mapping().getHandlerMethods().forEach((info, handler) -> {
            boolean read = info.getMethodsCondition().getMethods().stream().allMatch(
                method -> method == org.springframework.web.bind.annotation.RequestMethod.GET);
            if (!read && handler.getBeanType() == com.example.hms.controller.EncounterController.class
                    && RoleExpression.YES.equals(RoleExpression.admits(handler, RECEPTIONIST))) {
                writes.add(name(handler));
            }
        });
        assertThat(writes).isEqualTo(ENCOUNTER_WRITES_OF_THE_DESK);
    }

    @Test
    @DisplayName("QA D1421: no billing, front-desk or scheduling class reads an encounter's clinical text")
    void noNonClinicalClassReadsAnEncountersClinicalText() throws java.io.IOException {
        Set<String> reads = new TreeSet<>();
        for (String read : clinicalReads()) {
            if (NON_CLINICAL_CLASS.matcher(read.substring(0, read.indexOf('#'))).matches()) {
                reads.add(read);
            }
        }
        assertThat(reads).as("an invoice, an appointment or a desk answer never needs this; name the visit by type and date")
            .isEqualTo(NON_CLINICAL_CLASS_READS.keySet());
    }

    @Test
    @DisplayName("QA D1421: every reader of Encounter.notes is named, with who receives the text")
    void everyReaderOfEncounterNotesIsNamed() throws java.io.IOException {
        Set<String> readers = new TreeSet<>();
        for (String read : clinicalReads()) {
            if (read.endsWith("#Encounter.getNotes")) {
                readers.add(read.substring(0, read.indexOf('#')));
            }
        }
        assertThat(readers).as("Encounter.notes holds nursing text and diagnoses: say who this class hands them to")
            .isEqualTo(ENCOUNTER_NOTES_READERS.keySet());
    }

    private static void walkBody(Type type, Set<Class<?>> seen, Set<String> sent) {
        if (type instanceof ParameterizedType parameterized) {
            walkBody(parameterized.getRawType(), seen, sent);
            for (Type argument : parameterized.getActualTypeArguments()) {
                walkBody(argument, seen, sent);
            }
        } else if (type instanceof Class<?> raw && raw.getName().startsWith("com.example.hms.") && !raw.isEnum()
                && seen.add(raw)) {
            for (Field field : raw.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    if (CLINICAL_FIELD.matcher(field.getName()).matches()) {
                        sent.add(raw.getName().substring(raw.getName().lastIndexOf('.') + 1).replace('$', '.')
                            + "#" + field.getName());
                    }
                    walkBody(field.getGenericType(), seen, sent);
                }
            }
        }
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
                // A field the shaping removes leaves nothing nested for the desk to read (2026-11-12: the
                // after-visit summary's sections); ReceptionistResponseShapingTest proves each one empty.
                Set<String> removedHere = ReceptionistResponseShaping.REMOVED.get(raw);
                if (removedHere == null || !removedHere.contains(field.getName())) {
                    walk(field.getGenericType(), seen, handler, found, used);
                }
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
