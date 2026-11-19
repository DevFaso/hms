package com.example.hms.architecture;

import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.service.support.PatientSafeView;
import com.example.hms.testsupport.ControllerMappingRegistry;
import com.example.hms.testsupport.RoleExpression;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1960 (round 53): {@code GET /imaging/orders/patient/{patientId}} admits ROLE_PATIENT and sent the
 * patient the care team's working text: the workflow notes, the sedation notes, the pre-authorisation,
 * why a duplicate warning was overridden (#1171), the contrast-allergy details, why the order was
 * cancelled. The same held for the patient's reads of imaging reports, procedure orders, ultrasound
 * orders and reports, referrals, consultations, consents, and an encounter read by id.
 *
 * <p>Two checks keep the class closed:
 * <ul>
 *   <li>every type a patient-reachable endpoint returns (a handler whose {@code @PreAuthorize} admits
 *       ROLE_PATIENT, or one under {@code /me/patient/}), walked through its fields, that declares a
 *       field named like staff-only text, is listed here: either with the site and the token that hands
 *       the patient a projection ({@link PatientSafeView} or the record's own patient copy), or with why
 *       that text is the patient's;</li>
 *   <li>every {@link PatientSafeView} view, given a staff DTO with every field filled, returns no
 *       staff-only field.</li>
 * </ul>
 * A new patient-reachable read, or a new staff-only field on one, fails here until it is looked at.
 */
@DisplayName("D1960: a patient-reachable endpoint never returns the care team's staff-only fields")
class PatientReachableStaffFieldGuardTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/hms");

    /** A field name that reads like the care team's working text. */
    private static final Pattern STAFF_ONLY_ENDING = Pattern.compile(
        "(?i)(?:notes?|reason|allergyDetails|statusHistory|signatureValue)$");

    /** ... or that names an override, an authorisation, the workflow, an escalation or a clarification. */
    private static final Pattern STAFF_ONLY_PART = Pattern.compile(
        "(?i)override|authori[sz]ation|workflow|internal|escalat|clarification");

    static boolean isStaffOnly(String fieldName) {
        return STAFF_ONLY_ENDING.matcher(fieldName).find() || STAFF_ONLY_PART.matcher(fieldName).find();
    }

    private record Site(String file, String method, String token) {
    }

    private record Rule(List<Site> sites, String reason) {
        static Rule projected(String reason, Site... sites) {
            return new Rule(List.of(sites), reason);
        }

        static Rule patients(String reason) {
            return new Rule(List.of(), reason);
        }
    }

    private static Site site(String fileAndMethod, String token) {
        String[] parts = fileAndMethod.split("#");
        return new Site(parts[0], parts.length > 1 ? parts[1] : null, token);
    }

    private static final String BILLING_OPEN = "not an order: a financial record; whether its free text is staff-only"
        + " is open (tasklist, D1960 follow-up)";

    /** Simple class name (nested with $) to how the patient's copy withholds staff-only text, or why it is theirs. */
    private static final Map<String, Rule> REGISTRY = Map.ofEntries(
        // ── Projected for the patient (D1960) ──
        Map.entry("ImagingOrderResponseDTO", Rule.projected("D1960: the patient's imaging order",
            site("service/impl/ImagingOrderServiceImpl.java#getOrdersByPatient", "PatientSafeView::imagingOrder"))),
        Map.entry("ImagingReportResponseDTO", Rule.projected("D1960: the patient's released imaging report",
            site("service/impl/ImagingReportServiceImpl.java#getReport", "PatientSafeView.imagingReport("),
            site("service/impl/ImagingReportServiceImpl.java#getLatestReportForOrder", "PatientSafeView.imagingReport("),
            // D2342 (r63): the portal's « Imagerie » page, the patient's and their proxy's.
            site("service/imaging/PatientImagingPortalService.java#toStudy", "PatientSafeView::imagingReport"))),
        Map.entry("ImagingReportStatusDTO", Rule.projected("D1960: the status trail is left out of the patient's report",
            site("service/support/PatientSafeView.java#imagingReport", ".build()"))),
        Map.entry("ImagingReportMeasurementDTO", Rule.projected("D1960: measurements are left out of the patient's report",
            site("service/support/PatientSafeView.java#imagingReport", ".build()"))),
        Map.entry("ProcedureOrderResponseDTO", Rule.projected("D1960: the patient's procedure order",
            site("service/impl/ProcedureOrderServiceImpl.java#getProcedureOrder", "PatientSafeView.procedureOrder("),
            site("service/impl/ProcedureOrderServiceImpl.java#getProcedureOrdersForPatient",
                "PatientSafeView::procedureOrder"))),
        Map.entry("UltrasoundOrderResponseDTO", Rule.projected("D1960: the patient's ultrasound order",
            site("service/impl/UltrasoundServiceImpl.java#toOrderResponseDTO", "PatientSafeView.ultrasoundOrder("))),
        Map.entry("UltrasoundReportResponseDTO", Rule.projected("D1960: the patient's released ultrasound report",
            site("service/impl/UltrasoundServiceImpl.java#asReadBy", "PatientSafeView.ultrasoundReport("),
            site("service/support/PatientSafeView.java#ultrasoundOrder", "ultrasoundReport("))),
        Map.entry("GeneralReferralResponseDTO", Rule.projected("D1960: the patient's referral",
            site("service/impl/GeneralReferralServiceImpl.java#getReferralsForPortalPatient", "PatientSafeView::referral"))),
        Map.entry("ConsultationResponseDTO", Rule.projected("D1960 + D1910: the patient's consultation",
            site("service/impl/ConsultationServiceImpl.java#asReadByPatient", "PatientSafeView.consultation("))),
        Map.entry("PatientResponseDTO", Rule.projected("D1960: the chart embedded in the patient's consents",
            site("service/impl/PatientPortalServiceImpl.java#getMyConsents", "PatientSafeView::consent"))),
        Map.entry("PatientResponseDTO$VitalSnapshot", Rule.patients(
            "the patient's own last vitals, as /me/patient/vitals sends them")),
        Map.entry("PrescriptionResponseDTO", Rule.projected("G7/D1101/D1960: the patient's copy of a prescription",
            site("service/impl/PatientPortalServiceImpl.java#getMyPrescriptions", "withoutClarificationExchange"),
            site("controller/PrescriptionController.java#getById", "withoutClarificationExchange()"))),
        Map.entry("EncounterResponseDTO", Rule.projected("D1910/D1960: the patient's visit, signed note only",
            site("service/EncounterServiceImpl.java#getEncountersForPortalPatient", "toPatientPortalEncounterResponseDTO"),
            site("service/EncounterServiceImpl.java#getEncounterById", "toPatientPortalEncounterResponseDTO("))),
        Map.entry("EncounterNoteResponseDTO", Rule.projected("D1910/D1960: only the signed note reaches the patient",
            site("mapper/EncounterMapper.java#toPatientPortalEncounterResponseDTO", "getLatestSignedEncounterNote()"))),
        Map.entry("InvoiceItemResponseDTO", Rule.projected(
            "#1197 M1: the patient's line shows the price charged, never the cashier's override or the grid price",
            site("service/BillingInvoiceServiceImpl.java#getPortalInvoiceDetail", "InvoiceItemMapper::forPatient"))),
        Map.entry("AppointmentResponseDTO", Rule.projected("D1429: the overbooking reason comes off on the way out",
            site("controller/support/PatientResponseShaping.java#shape", "setOverbookReason(null)"))),
        // ── The patient's by design ──
        Map.entry("AppointmentSummaryDTO", Rule.patients("the booking's own notes, as the patient or desk wrote them")),
        Map.entry("BirthPlanResponseDTO", Rule.patients("the patient's own birth plan")),
        Map.entry("BirthPlanResponseDTO$DeliveryPreferencesDTO", Rule.patients("the patient's own birth plan")),
        Map.entry("BirthPlanResponseDTO$DeliveryRoomEnvironmentDTO", Rule.patients("the patient's own birth plan")),
        Map.entry("BirthPlanResponseDTO$PainManagementDTO", Rule.patients("the patient's own birth plan")),
        Map.entry("BirthPlanResponseDTO$PostpartumPreferencesDTO", Rule.patients("the patient's own birth plan")),
        Map.entry("DischargeSummaryResponseDTO", Rule.patients("published to the patient, written for them (#1138)")),
        Map.entry("MedicationReconciliationDTO", Rule.patients("the discharge medication list, written for the patient")),
        Map.entry("PatientEducationQuestionResponseDTO", Rule.patients("the provider's answer to the patient's question")),
        Map.entry("PatientEducationItemDTO", Rule.patients("the patient's own clarification request")),
        Map.entry("PatientLabResultResponseDTO", Rule.patients(
            "the laboratory's comment on a released result; withheld with the value until release (B3)")),
        Map.entry("FamilyHistoryResponseDTO", Rule.patients("the patient's own history")),
        Map.entry("SocialHistoryResponseDTO", Rule.patients("the patient's own history")),
        Map.entry("PatientSurgicalHistoryResponseDTO", Rule.patients("the patient's own history")),
        Map.entry("ImmunizationResponseDTO", Rule.patients("the patient's own immunisation record")),
        Map.entry("PatientVitalSignResponseDTO", Rule.patients("the patient's own measurements")),
        Map.entry("PatientMedicationResponseDTO", Rule.patients("why a refill is blocked, told to the patient")),
        Map.entry("MedicationRefillResponseDTO", Rule.patients("the answer to the patient's refill request")),
        Map.entry("PatientDocumentResponseDTO", Rule.patients("the patient's own note on their upload")),
        Map.entry("ProxyResponseDTO", Rule.patients("the patient's own note on a proxy grant")),
        Map.entry("ProInstrumentViewDTO", Rule.patients("the instrument's licence notice")),
        Map.entry("RecordSharingOptOutDTO", Rule.patients("the patient's own opt-out reason")),
        Map.entry("RoiRequestResponseDTO", Rule.patients("the decision on the patient's request, explained to them")),
        Map.entry("HighRiskPregnancyCarePlanResponseDTO", Rule.patients(
            "a care plan shared with the patient; whether its coordination notes are staff-only is open"
                + " (tasklist, D1960 follow-up)")),
        Map.entry("HighRiskPregnancyCarePlanResponseDTO$BloodPressureLogDTO", Rule.patients("the patient's own readings")),
        Map.entry("HighRiskPregnancyCarePlanResponseDTO$CareTeamMemberDTO", Rule.patients(
            "the shared care plan (tasklist, D1960 follow-up)")),
        Map.entry("HighRiskPregnancyCarePlanResponseDTO$MedicationLogDTO", Rule.patients("the patient's own log")),
        Map.entry("HighRiskPregnancyCarePlanResponseDTO$SupportResourceDTO", Rule.patients("resources for the patient")),
        Map.entry("BillingInvoiceResponseDTO", Rule.patients(BILLING_OPEN)),
        Map.entry("InvoicePayerShareDTO", Rule.patients(
            "billing-money plan PR 7: the insurer's own reason for refusing its share, told to the patient, who now owes"
                + " the amount (plan AC: \"Refusé par l'assureur : motif\")")),
        Map.entry("InvoiceAdjustmentResponseDTO", Rule.patients(BILLING_OPEN)),
        Map.entry("PaymentTransactionResponseDTO", Rule.patients(BILLING_OPEN)),
        Map.entry("PharmacyClaimResponseDTO", Rule.patients(BILLING_OPEN)),
        Map.entry("PharmacyPaymentResponseDTO", Rule.patients(BILLING_OPEN)));

    /** Per view input type: staff-only-named fields the patient's copy keeps on purpose. */
    private static final Map<Class<?>, Set<String>> KEPT_ON_PURPOSE = Map.of(
        com.example.hms.payload.dto.GeneralReferralResponseDTO.class, Set.of("referralReason"),
        // D1910: the consultant's opinion once the consultation is completed (asReadByPatient withholds it before).
        com.example.hms.payload.dto.consultation.ConsultationResponseDTO.class, Set.of("consultantNote"),
        // The prescriber's directions and why a signed order was withdrawn are told to the patient (D660);
        // the signature value is the prescriber's attestation on the patient's own prescription.
        PrescriptionResponseDTO.class, Set.of("notes", "withdrawalReason", "signatureValue"),
        com.example.hms.payload.dto.PatientConsentResponseDTO.class, Set.of("notes"));

    @Test
    @DisplayName("every patient-reachable type with a staff-only field is projected for the patient or theirs by design")
    void everyPatientReachableTypeIsListed() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        ControllerMappingRegistry.mapping().getHandlerMethods().forEach((info, handler) -> {
            boolean admitsPatient = RoleExpression.YES.equals(RoleExpression.admits(handler, "ROLE_PATIENT"));
            boolean portal = info.getPatternValues().stream()
                .anyMatch(p -> p.startsWith("/me/patient/") || "/me/patient".equals(p));
            if (!admitsPatient && !portal) {
                return;
            }
            walk(handler.getMethod().getGenericReturnType(), new HashSet<>(), found);
        });
        for (String type : found) {
            Rule rule = REGISTRY.get(type);
            if (rule == null) {
                problems.add(type + " reaches a patient and has a field named like staff-only text: hand the patient"
                    + " a PatientSafeView projection, or list why the text is the patient's");
                continue;
            }
            for (Site s : rule.sites()) {
                String code = SourceScan.stripComments(Files.readString(ROOT.resolve(s.file()), StandardCharsets.UTF_8));
                String body = s.method() == null ? code : SourceScan.methodBody(code, s.method());
                if (body == null || !body.contains(s.token())) {
                    problems.add(type + ": " + s.file() + "#" + s.method() + " no longer holds `" + s.token() + "` ("
                        + rule.reason() + ")");
                }
            }
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
        assertThat(found).as("a listed type no patient-reachable endpoint returns any more comes off the list")
            .containsExactlyInAnyOrderElementsOf(REGISTRY.keySet());
    }

    @Test
    @DisplayName("every patient view, given a staff DTO with every field filled, returns no staff-only field")
    void everyPatientViewWithholdsEveryStaffOnlyField() throws Exception {
        List<String> leaks = new ArrayList<>();
        List<Method> views = Arrays.stream(PatientSafeView.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
            .toList();
        assertThat(views).as("the views under test").hasSizeGreaterThanOrEqualTo(8);
        for (Method view : views) {
            Class<?> type = view.getParameterTypes()[0];
            Object out = view.invoke(null, filled(type, 0));
            assertThat(out).as(view.getName() + " keeps the record").isNotNull();
            collectLeaks(out, view.getName(), KEPT_ON_PURPOSE.getOrDefault(type, Set.of()), leaks, new HashSet<>());
        }
        UnaryOperator<PrescriptionResponseDTO> prescription = PrescriptionResponseDTO::withoutClarificationExchange;
        collectLeaks(prescription.apply((PrescriptionResponseDTO) filled(PrescriptionResponseDTO.class, 0)),
            "withoutClarificationExchange", KEPT_ON_PURPOSE.get(PrescriptionResponseDTO.class), leaks, new HashSet<>());
        assertThat(leaks).as(SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("the attended orders are rebuilt from the patient's fields: what the patient needs to attend survives")
    void theAttendedOrdersKeepWhatThePatientNeeds() throws Exception {
        var order = PatientSafeView.imagingOrder((com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO)
            filled(com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO.class, 0));
        assertThat(order.getStudyType()).isNotNull();
        assertThat(order.getBodyRegion()).isNotNull();
        assertThat(order.getStatus()).isNotNull();
        assertThat(order.getScheduledDate()).isNotNull();
        assertThat(order.getScheduledTime()).isNotNull();
        assertThat(order.getAppointmentLocation()).isNotNull();
        var report = PatientSafeView.imagingReport((com.example.hms.payload.dto.imaging.ImagingReportResponseDTO)
            filled(com.example.hms.payload.dto.imaging.ImagingReportResponseDTO.class, 0));
        assertThat(report.getImpression()).isNotNull();
        assertThat(report.getFindings()).isNotNull();
        assertThat(report.getSignedAt()).isNotNull();
    }

    // ── Walking the return types ──

    private static void walk(Type type, Set<Class<?>> seen, Set<String> found) {
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                walk(bound, seen, found);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            walk(parameterized.getRawType(), seen, found);
            for (Type argument : parameterized.getActualTypeArguments()) {
                walk(argument, seen, found);
            }
        } else if (type instanceof Class<?> raw && raw.getName().startsWith("com.example.hms.") && !raw.isEnum()
                && seen.add(raw)) {
            for (Class<?> c = raw; c != null && c.getName().startsWith("com.example.hms."); c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (isStaffOnly(field.getName())) {
                        found.add(nameOf(raw));
                    }
                    walk(field.getGenericType(), seen, found);
                }
            }
        }
    }

    private static String nameOf(Class<?> type) {
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    // ── Filling a DTO and reading what came out ──

    private static Object filled(Class<?> type, int depth) throws Exception {
        if (type.isRecord() || Arrays.stream(type.getDeclaredConstructors()).noneMatch(k -> k.getParameterCount() == 0)) {
            return null;
        }
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                Object value = valueFor(field, depth);
                if (value != null) {
                    field.setAccessible(true);
                    field.set(instance, value);
                }
            }
        }
        return instance;
    }

    private static Object valueFor(Field field, int depth) throws Exception {
        Class<?> t = field.getType();
        if (t == String.class) {
            return "staff text";
        } else if (t == UUID.class) {
            return UUID.randomUUID();
        } else if (t == Boolean.class || t == boolean.class) {
            return Boolean.TRUE;
        } else if (t == Integer.class || t == int.class) {
            return 1;
        } else if (t == Short.class || t == short.class) {
            return (short) 1;
        } else if (t == Long.class || t == long.class) {
            return 1L;
        } else if (t == Double.class || t == double.class) {
            return 1.0;
        } else if (t == BigDecimal.class) {
            return BigDecimal.ONE;
        } else if (t == LocalDate.class) {
            return LocalDate.now();
        } else if (t == LocalDateTime.class) {
            return LocalDateTime.now();
        } else if (t.isEnum()) {
            return t.getEnumConstants()[0];
        } else if (List.class.isAssignableFrom(t)) {
            Type arg = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
            if (arg instanceof Class<?> element && element.getName().startsWith("com.example.hms.") && depth < 2) {
                Object one = filled(element, depth + 1);
                return one == null ? new ArrayList<>() : new ArrayList<>(List.of(one));
            }
            return new ArrayList<>();
        } else if (Map.class.isAssignableFrom(t)) {
            return new java.util.HashMap<>(Map.of("k", "v"));
        } else if (t.getName().startsWith("com.example.hms.") && depth < 2
                && !Modifier.isAbstract(t.getModifiers())) {
            return filled(t, depth + 1);
        }
        return null;
    }

    private static void collectLeaks(Object dto, String view, Set<String> kept, List<String> leaks, Set<Object> seen)
            throws IllegalAccessException {
        if (dto == null || !seen.add(System.identityHashCode(dto))) {
            return;
        }
        for (Class<?> c = dto.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(dto);
                if (isStaffOnly(field.getName()) && !kept.contains(field.getName())
                        && present(value)) {
                    leaks.add(view + " leaves " + nameOf(c) + "#" + field.getName() + " on the patient's copy");
                }
                if (value != null && value.getClass().getName().startsWith("com.example.hms.")
                        && !value.getClass().isEnum()) {
                    collectLeaks(value, view, kept, leaks, seen);
                }
            }
        }
    }

    private static boolean present(Object value) {
        if (value instanceof Collection<?> many) {
            return !many.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return value != null;
    }
}
