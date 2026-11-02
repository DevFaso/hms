package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import jakarta.validation.Valid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1258 (round 26; the server half of D1245, NOT_FIXED again): a blank or
 * absent {@code patientId} on {@code POST /reception/waitlist} and
 * {@code POST /birth-plans} answered 404 "Patient introuvable avec ID : null"
 * with an empty {@code fieldErrors}. Round 25 enumerated the ids the server
 * could not READ (a blank string, an unparsable UUID); it missed the ids that
 * read fine as null and went straight into a lookup. Three ways that happens,
 * one rule each, over every request body of every controller:
 *
 * <ul>
 *   <li>a body whose class declares constraints is bound without
 *       {@code @Valid}, so none of them runs (the waitlist and check-in
 *       bodies);</li>
 *   <li>a service looks up {@code request.getXxxId()} although the field is
 *       not {@code @NotNull} and nothing before the lookup refuses a null with
 *       the field named;</li>
 *   <li>a service refuses a null {@code request.getXxxId()} with an exception
 *       that names no field (a {@code BusinessException} message only).</li>
 * </ul>
 *
 * Each refusal must be a 400 with {@code fieldErrors.<field>} before any
 * lookup: {@code @NotNull} on the field (bean validation names it), or a
 * {@link com.example.hms.exception.FieldBusinessException} naming it.
 */
@DisplayName("D1258: a missing required id of a request is refused with its field named, before any lookup")
class RequiredRequestIdGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /**
     * {@code name(variable.getXxx())}; a lookup when the name contains
     * {@code ById} or ends with {@code OrThrow} and the getter ends with
     * {@code Id} ({@link #isLookup}). Linear: the names are tested in code,
     * not by a backtracking alternation (Sonar S8786).
     */
    private static final Pattern LOOKUP = Pattern.compile(
        "\\b(\\w++)\\(\\s*+(\\w++)\\.get(\\w++)\\(\\)\\s*+\\)");
    /** A type name, maybe package-qualified (lower-case segments first). */
    private static final String QUALIFIED_TYPE = "(?:[a-z]\\w*+\\.)*+[A-Z]\\w*+";
    /** An id the service branches on: {@code if (x.getYId() != null ...}, the head of an id-or-alternative chain. */
    private static final Pattern OPTIONAL_ID_BRANCH = Pattern.compile(
        "\\bif\\s*+\\(\\s*+(\\w++)\\.get(\\w++)\\(\\)\\s*+!=\\s*+null\\b");
    /** {@code throw new X(} or {@code throw new com.example...X(}: the exception type, maybe qualified. */
    private static final Pattern THROW_NEW = Pattern.compile("throw\\s++new\\s++(" + QUALIFIED_TYPE + ")\\(");
    /** {@code UUID copy = request.getXxx();}: a local copy of a request getter. */
    private static final Pattern ID_COPY = Pattern.compile(
        "\\bUUID\\s++(\\w++)\\s*+=\\s*+(\\w++)\\.get(\\w++)\\(\\)\\s*+;");
    private static final Pattern FIELDLESS_NULL_REFUSAL = Pattern.compile(
        "if\\s*+\\(\\s*+(\\w+)\\.get(\\w+Id)\\(\\)\\s*+==\\s*+null\\s*+\\)\\s*+\\{?\\s*+throw\\s++new\\s++("
            + QUALIFIED_TYPE + ")\\(");

    /**
     * Lookups the scan reads that are not a request id reaching a lookup unchecked, each with its reason.
     */
    private static final Map<String, String> NOT_A_REQUEST_LOOKUP = Map.of(
        "PatientServiceImpl#createPatient:hospitalId",
        "internal: the legacy backfill and createPatientByStaff call it with the hospital the controller resolved",
        "PharmacySaleServiceImpl#sellingLot:stockLotId",
        "the only caller reads 'getStockLotId() == null ? null : sellingLot(...)' - the lot is optional",
        "StockTransactionServiceImpl#requireReturnOfTransfer:referenceId",
        "called from requireReturnWithinDispense only after its 'getReferenceId() == null' branch returned");

    /**
     * Bodies bound without {@code @Valid} although they declare constraints, each with its reason.
     */
    private static final Map<String, String> UNVALIDATED_ALLOWED = Map.of(
        "StaffController#create",
        "standing debt: StaffRequestDTO puts @NotBlank / @Size on enum fields, which bean validation cannot apply"
            + " (UnexpectedTypeException, a 500); its ids are names the service resolves itself",
        "StaffController#update", "same body as StaffController#create");

    @Test
    @DisplayName("a request body that declares constraints is bound with @Valid")
    void everyConstrainedRequestBodyIsValidated() {
        List<String> unvalidated = new ArrayList<>();
        for (Handler handler : handlers()) {
            if (!handler.validated() && declaresConstraints(handler.body(), new LinkedHashSet<>())
                    && !UNVALIDATED_ALLOWED.containsKey(handler.where())) {
                unvalidated.add(handler.where() + " binds " + handler.body().getSimpleName() + " without @Valid");
            }
        }
        assertThat(unvalidated)
            .as("its @NotNull / @Size never run, so a missing id reaches the service (QA D1258)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("a service looks up a request id only when a null was refused with the field named")
    void everyRequestIdIsRequiredBeforeItsLookup() throws IOException {
        Map<String, Class<?>> bodies = requestBodyTypes();
        List<String> unchecked = new ArrayList<>();
        Set<String> excused = new LinkedHashSet<>();
        int lookups = 0;
        for (Path file : serviceFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, String> fieldChecks = fieldNamedNullChecks(source);
            Matcher m = LOOKUP.matcher(source);
            while (m.find()) {
                if (!isLookup(m)) {
                    continue;
                }
                String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                Class<?> body = parameterType(method, m.group(2), bodies);
                if (body == null) {
                    continue;
                }
                lookups++;
                String field = Character.toLowerCase(m.group(3).charAt(0)) + m.group(3).substring(1);
                String site = file.getFileName().toString().replace(".java", "") + "#" + methodName(method) + ":" + field;
                if (NOT_A_REQUEST_LOOKUP.containsKey(site)) {
                    excused.add(site);
                    continue;
                }
                String before = method.substring(0, method.indexOf(m.group()) < 0 ? method.length() : method.indexOf(m.group()));
                if (!isNotNull(body, field) && !refusedBefore(before, m.group(3), fieldChecks)
                        && !guardedInStatement(source, m.start(), m.group(3))) {
                    unchecked.add(site + " (" + body.getSimpleName() + "." + field + " at line "
                        + SourceScan.lineOf(source, m.start()) + ")");
                }
            }
        }
        assertThat(lookups).as("request-id lookups read").isGreaterThan(40);
        assertThat(excused).as("an excused lookup that disappeared: delete its line")
            .containsExactlyInAnyOrderElementsOf(NOT_A_REQUEST_LOOKUP.keySet());
        assertThat(unchecked)
            .as("a null id reaches the lookup and answers 404 '... ID : null' with no fieldErrors; make the field"
                + " @NotNull or refuse a null first with a FieldBusinessException naming it (QA D1258)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("a missing request id is refused with an exception that names the field")
    void everyMissingRequestIdRefusalNamesItsField() throws IOException {
        Map<String, Class<?>> bodies = requestBodyTypes();
        List<String> fieldless = new ArrayList<>();
        int refusals = 0;
        for (Path file : serviceFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = FIELDLESS_NULL_REFUSAL.matcher(source);
            while (m.find()) {
                String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                if (parameterType(method, m.group(1), bodies) == null) {
                    continue;
                }
                refusals++;
                if (!m.group(3).endsWith("FieldBusinessException")) {
                    fieldless.add(file.getFileName() + ":" + SourceScan.lineOf(source, m.start()) + " "
                        + m.group(1) + ".get" + m.group(2) + "() == null -> " + m.group(3));
                }
            }
        }
        assertThat(refusals).as("null-id refusals read").isGreaterThan(10);
        assertThat(fieldless)
            .as("the 400 names no field, so the form cannot mark it (QA D1258)" + SourceScan.MANDATE)
            .isEmpty();
    }

    /**
     * QA D1282 (round 27; the D1258 class, NOT_FIXED again): {@code POST /chat/send}
     * without a recipient and {@code POST /billing-invoices} without a patient
     * answered 400 with no {@code fieldErrors}. Round 26 read the refusals
     * written {@code if (x.getYId() == null) throw}; it missed the ones at the
     * end of an id-or-alternative chain -
     * {@code if (x.getYId() != null) {...} else if (x.getYEmail() != null) {...} else { throw }} -
     * where the id is required unless the alternative is given. That final
     * refusal must name the id's field too.
     */
    @Test
    @DisplayName("an id-or-alternative chain ends in a refusal that names the id's field")
    void everyIdOrAlternativeChainRefusesWithTheFieldNamed() throws IOException {
        Map<String, Class<?>> bodies = requestBodyTypes();
        List<String> fieldless = new ArrayList<>();
        int chains = 0;
        for (Path file : serviceFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = OPTIONAL_ID_BRANCH.matcher(source);
            while (m.find()) {
                if (!m.group(2).endsWith("Id")) {
                    continue;
                }
                String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                if (parameterType(method, m.group(1), bodies) == null) {
                    continue;
                }
                String refusal = finalElseRefusal(source, m.start());
                if (refusal == null) {
                    continue;
                }
                chains++;
                if (!refusal.endsWith("FieldBusinessException")) {
                    fieldless.add(file.getFileName() + ":" + SourceScan.lineOf(source, m.start()) + " "
                        + m.group(1) + ".get" + m.group(2) + "() missing -> " + refusal);
                }
            }
        }
        assertThat(chains).as("id-or-alternative chains read").isPositive();
        assertThat(fieldless)
            .as("the 400 names no field, so the form cannot mark it (QA D1282)" + SourceScan.MANDATE)
            .isEmpty();
    }

    /**
     * QA D1282 (sweep): the same refusal through a local copy of the id -
     * {@code UUID recipientId = request.getRecipientUserId(); if (recipientId == null) throw ...}
     * (the handoff recipient answered 400 with no field). The copy is followed
     * to its null test in the same method.
     */
    @Test
    @DisplayName("a missing request id refused through a local copy names the field")
    void everyMissingRequestIdRefusedThroughACopyNamesItsField() throws IOException {
        Map<String, Class<?>> bodies = requestBodyTypes();
        List<String> fieldless = new ArrayList<>();
        int copies = 0;
        for (Path file : serviceFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = ID_COPY.matcher(source);
            while (m.find()) {
                if (!m.group(3).endsWith("Id")) {
                    continue;
                }
                String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                if (parameterType(method, m.group(2), bodies) == null) {
                    continue;
                }
                Matcher refusal = Pattern.compile("if\\s*+\\(\\s*+" + Pattern.quote(m.group(1))
                    + "\\s*+==\\s*+null\\s*+\\)\\s*+\\{?\\s*+throw\\s++new\\s++(" + QUALIFIED_TYPE + ")\\(").matcher(method);
                if (refusal.find()) {
                    copies++;
                    if (!refusal.group(1).endsWith("FieldBusinessException")) {
                        fieldless.add(file.getFileName() + ":" + SourceScan.lineOf(source, m.start()) + " "
                            + m.group(1) + " = " + m.group(2) + ".get" + m.group(3) + "() missing -> " + refusal.group(1));
                    }
                }
            }
        }
        assertThat(copies).as("null-id refusals through a copy read").isPositive();
        assertThat(fieldless)
            .as("the 400 names no field, so the form cannot mark it (QA D1282)" + SourceScan.MANDATE)
            .isEmpty();
    }

    /**
     * The exception type thrown first in the final {@code else} block of the
     * if-chain starting at {@code ifAt}, or null when the chain has no final
     * {@code else}, or that block does not start with a {@code throw}.
     */
    private static String finalElseRefusal(String source, int ifAt) {
        int at = ifAt;
        while (true) {
            int open = source.indexOf('(', at);
            int close = SourceScan.closingParen(source, open);
            int brace = skipBlanks(source, close + 1);
            if (brace >= source.length() || source.charAt(brace) != '{') {
                return null;
            }
            int next = skipBlanks(source, SourceScan.closingBrace(source, brace) + 1);
            if (!source.startsWith("else", next)) {
                return null;
            }
            int afterElse = skipBlanks(source, next + 4);
            if (source.startsWith("if", afterElse)) {
                at = afterElse;
                continue;
            }
            if (afterElse >= source.length() || source.charAt(afterElse) != '{') {
                return null;
            }
            Matcher thrown = THROW_NEW.matcher(source);
            thrown.region(skipBlanks(source, afterElse + 1), source.length());
            return thrown.lookingAt() ? thrown.group(1) : null;
        }
    }

    /**
     * QA D1299 (round 28; the D1258 / D1282 class, NOT_FIXED again): the
     * earlier rules read only {@code if (x.getYId() == null) throw}. They could
     * not see a refusal of any OTHER missing request value - a blank reason
     * ({@code PUT /appointments/{id}/status} with {@code {}}), a blank message
     * tested through local booleans ({@code boolean hasContent = dto.getContent()...;
     * if (!hasContent && !hasAttachments) throw}), an id tested inside a
     * compound condition ({@code registration.patient.required} as an
     * IllegalArgumentException). The rule now reads the BEHAVIOUR: any
     * {@code if} whose condition tests a request-body getter (directly or
     * through a local copy) for absence and whose body throws, must throw a
     * {@link com.example.hms.exception.FieldBusinessException} (or go through
     * {@link com.example.hms.exception.RequiredFields}, which throws one).
     */
    @Test
    @DisplayName("every refusal of a missing or blank request value names its field")
    void everyMissingRequestValueRefusalNamesItsField() throws IOException {
        Map<String, Class<?>> bodies = requestBodyTypes();
        List<String> fieldless = new ArrayList<>();
        Set<String> excused = new LinkedHashSet<>();
        int refusals = 0;
        for (Path file : serviceFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = IF_OPEN.matcher(source);
            while (m.find()) {
                int open = m.end() - 1;
                int close = SourceScan.closingParen(source, open);
                String thrown = thrownRightAfter(source, close + 1);
                if (thrown == null || NOT_A_REFUSAL_OF_THE_BODY.contains(thrown.substring(thrown.lastIndexOf('.') + 1))) {
                    continue;
                }
                String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                String getter = absentBodyValue(source.substring(open + 1, close), method, bodies);
                if (getter == null) {
                    continue;
                }
                refusals++;
                String site = file.getFileName().toString().replace(".java", "") + "#" + methodName(method) + ":" + getter;
                if (thrown.endsWith("FieldBusinessException")) {
                    continue;
                }
                if (CONTEXT_NOT_FIELD.containsKey(site)) {
                    excused.add(site);
                    continue;
                }
                fieldless.add(site + " (line " + SourceScan.lineOf(source, m.start()) + ", " + thrown + ")");
            }
        }
        assertThat(refusals).as("missing-value refusals read").isGreaterThan(40);
        fieldless.addAll(helperRefusalsOfBodyValues(bodies));
        assertThat(fieldless)
            .as("an empty body is refused with no fieldErrors, so the form cannot mark the field; throw a"
                + " FieldBusinessException naming it, or collect every check in RequiredFields (QA D1299)"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(excused).as("an excused site that disappeared: delete its entry")
            .containsExactlyInAnyOrderElementsOf(CONTEXT_NOT_FIELD.keySet());
    }

    /**
     * QA D1301 (round 28): a payment reference already used was refused with
     * a toast and no {@code fieldErrors}, so the Référence field was never
     * marked. The class is a refusal of a VALUE the user typed because another
     * record holds it (a duplicate, a name taken). Every such refusal in the
     * services - read by its message key, literal or through a
     * {@code static final String} constant ({@code DUPLICATE_KEY}) - throws a
     * {@link com.example.hms.exception.FieldBusinessException}.
     */
    @Test
    @DisplayName("a value refused because another record holds it names its field")
    void everyDuplicateValueRefusalNamesItsField() throws IOException {
        List<String> fieldless = new ArrayList<>();
        Set<String> excused = new LinkedHashSet<>();
        int refusals = 0;
        for (Path file : serviceFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, String> constants = new HashMap<>();
            Matcher constant = Pattern.compile("static\\s++final\\s++String\\s++(\\w++)\\s*+=\\s*+\"([^\"]++)\"").matcher(source);
            while (constant.find()) {
                constants.put(constant.group(1), constant.group(2));
            }
            // QA D1338 (round 29): a 409 whose message starts "field:" carries fieldErrors for that field.
            Matcher thrown = Pattern.compile("throw\\s++new\\s++(" + QUALIFIED_TYPE + ")\\(\\s*+"
                + "(\"[a-z]\\w*+:\"\\s*+\\+\\s*+)?"
                + "(?:(?:com\\.example\\.hms\\.utility\\.)?MessageUtil\\.resolve(?:OrRaw)?\\(\\s*+)?"
                + "(?:\"([\\w.]++)\"|([A-Z][A-Z0-9_]++)\\b)").matcher(source);
            while (thrown.find()) {
                String key = thrown.group(3) != null ? thrown.group(3) : constants.get(thrown.group(4));
                if (key == null || !DUPLICATE_KEY.matcher(key).find()) {
                    continue;
                }
                refusals++;
                if (thrown.group(1).endsWith("FieldBusinessException")
                        || (thrown.group(2) != null && thrown.group(1).endsWith("ConflictException"))) {
                    continue;
                }
                if (NOT_A_TYPED_VALUE.containsKey(key)) {
                    excused.add(key);
                    continue;
                }
                fieldless.add(file.getFileName() + ":" + SourceScan.lineOf(source, thrown.start()) + " " + key
                    + " -> " + thrown.group(1));
            }
        }
        assertThat(refusals).as("duplicate-value refusals read").isGreaterThan(10);
        assertThat(fieldless)
            .as("a duplicate value is refused with no fieldErrors, so the form cannot mark the field (QA D1301)"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(excused).as("an excused key that disappeared: delete its entry")
            .containsExactlyInAnyOrderElementsOf(NOT_A_TYPED_VALUE.keySet());
    }

    /**
     * QA D1300 (round 28): {@code POST /billing-invoices {}} named the dates
     * (bean validation) and the patient only on the next press (the service);
     * {@code /invoice-items {}} never named the invoice with the other four;
     * {@code /payments {}} named the amount, the method next. A service check
     * of a field whose body ALSO declares bean constraints runs only after
     * those passed. Every field a service refuses absent, on such a body,
     * carries its own constraint (in a group when it is create-only, a
     * class-level {@code @OneOf} when one of two will do), or is listed here
     * as required only conditionally.
     */
    @Test
    @DisplayName("a field a service refuses absent is constrained on its body, so one refusal names it with the rest")
    void everyServiceRequiredFieldIsAlsoABeanConstraint() throws IOException {
        Map<String, Class<?>> bodies = requestBodyTypes();
        List<String> oneAtATime = new ArrayList<>();
        Set<String> excused = new LinkedHashSet<>();
        for (Path file : serviceFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = IF_OPEN.matcher(source);
            while (m.find()) {
                int open = m.end() - 1;
                int close = SourceScan.closingParen(source, open);
                String thrown = thrownRightAfter(source, close + 1);
                String condition = source.substring(open + 1, close);
                String method = methodAround(source, m.start());
                if (method == null) {
                    continue;
                }
                String chained = finalElseRefusal(source, m.start());
                boolean fieldRefusal = (thrown != null && thrown.endsWith("FieldBusinessException"))
                    || (chained != null && chained.endsWith("FieldBusinessException")
                        && condition.contains("!= null"));
                if (!fieldRefusal) {
                    continue;
                }
                Matcher getter = GETTER.matcher(condition);
                while (getter.find()) {
                    Class<?> body = parameterType(method, getter.group(1), bodies);
                    boolean tested = chained != null || testedAbsent(condition, getter);
                    if (body == null || !tested || !declaresConstraints(body, new LinkedHashSet<>())) {
                        continue;
                    }
                    String field = Character.toLowerCase(getter.group(2).charAt(0)) + getter.group(2).substring(1);
                    if (constrainedPresent(body, field)) {
                        continue;
                    }
                    String site = body.getSimpleName() + "." + field;
                    if (CONDITIONALLY_REQUIRED.containsKey(site)) {
                        excused.add(site);
                    } else {
                        oneAtATime.add(site + " (" + file.getFileName() + ":" + SourceScan.lineOf(source, m.start()) + ")");
                    }
                    break;
                }
            }
        }
        assertThat(oneAtATime)
            .as("checked only after bean validation passed, so an empty body names it one press later; give it"
                + " @NotNull (groups = OnCreate.class when create-only) or a class-level @OneOf (QA D1300)"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(excused).as("a conditional field that is no longer read: delete its entry")
            .containsExactlyInAnyOrderElementsOf(CONDITIONALLY_REQUIRED.keySet());
    }

    /**
     * Fields required only in some cases (a status, another field's value),
     * each with when; and the KNOWN GAPS this round could not move onto the
     * body (the same DTO serves a path where the field is optional, or the
     * controller fills it after binding), each in tasklist standing debt.
     * Shrinks only.
     */
    private static final Map<String, String> CONDITIONALLY_REQUIRED = Map.ofEntries(
        Map.entry("StaffPaymentRequestDTO.reference", "only for mobile money, insurance or transfer"),
        Map.entry("DeathRecordRequestDTO.maternalDeathTiming", "only when the death is a maternal death"),
        Map.entry("DeathRecordRequestDTO.perinatalType", "only when the death is a perinatal death"),
        Map.entry("ObgynReferralCreateRequestDTO.attachments", "only for an urgent referral"),
        Map.entry("TransferOrderRequestDTO.isolationOverrideReason", "only when the destination breaks isolation"),
        Map.entry("PatientBloodGroupRequestDTO.correctionReason", "only when a recorded group is corrected"),
        Map.entry("LabTestDefinitionApprovalRequestDTO.rejectionReason", "only to reject"),
        Map.entry("MedicalCertificateRequestDTO.days", "only for a sick-leave certificate"),
        Map.entry("DispenseRequestDTO.verifiedBy", "only when the pharmacist verification blocks administration"),
        Map.entry("StockTransactionRequestDTO.reason", "only for a movement that must be accounted for"),
        Map.entry("CheckInRequestDTO.copayMethod", "only when a copay is taken"),
        Map.entry("StaffRequestDTO.specialization", "only for a doctor or radiologist"),
        Map.entry("StaffRequestDTO.startDate", "only for a nurse or radiologist"),
        Map.entry("StaffRequestDTO.licenseNumber", "only for a radiologist"),
        Map.entry("ImagingReportStatusUpdateRequestDTO.statusReason", "only for a status that needs a reason"),
        Map.entry("CdsAcknowledgementRequestDTO.reason", "only when the card is overridden"),
        Map.entry("SmartPhraseRequestDTO.hospitalId", "only for a hospital-scoped phrase"),
        Map.entry("SmartPhraseRequestDTO.ownerUserId", "only for a personal phrase"),
        Map.entry("InvoiceAdjustmentRequestDTO.invoiceItemId", "KNOWN GAP D1300: only for a line adjustment"),
        Map.entry("AppointmentRequestDTO.patientId",
            "only for a staff booking (a patient books for themselves): the body's isPatientNamedForStaffBooking"
                + " rule names it with the rest (QA D1318)"),
        Map.entry("BirthPlanRequestDTO.patientId", "KNOWN GAP D1300: a staff caller only; the patient's own omits it"),
        Map.entry("BirthPlanRequestDTO.hospitalId", "KNOWN GAP D1300: a staff caller only; the patient's own omits it"),
        Map.entry("DepartmentRequestDTO.hospitalId", "KNOWN GAP D1300: filled from the caller's scope when absent"),
        Map.entry("HospitalRequestDTO.state", "KNOWN GAP D1300: the address rule depends on the country"),
        Map.entry("HospitalRequestDTO.zipCode", "KNOWN GAP D1300: the address rule depends on the country"),
        Map.entry("ImagingReportUpsertRequestDTO.imagingOrderId", "KNOWN GAP D1300: create only; the upsert body edits"),
        Map.entry("LabSpecimenRequestDTO.labOrderId", "the controller sets it from the path after binding: never absent"),
        Map.entry("CheckInRequestDTO.appointmentId", "KNOWN GAP D1300: the DTO documents a walk-in with patientId instead"));

    /** The field carries @NotNull / @NotBlank / @NotEmpty (any group), or a class-level @OneOf lists it. */
    private static boolean constrainedPresent(Class<?> body, String field) {
        for (Class<?> c = body; c != null && c != Object.class; c = c.getSuperclass()) {
            for (com.example.hms.payload.dto.OneOf oneOf : c.getAnnotationsByType(com.example.hms.payload.dto.OneOf.class)) {
                if (List.of(oneOf.fields()).contains(field)) {
                    return true;
                }
            }
        }
        for (Field f : fields(body)) {
            if (f.getName().equals(field)) {
                return f.isAnnotationPresent(jakarta.validation.constraints.NotNull.class)
                    || f.isAnnotationPresent(jakarta.validation.constraints.NotBlank.class)
                    || f.isAnnotationPresent(jakarta.validation.constraints.NotEmpty.class);
            }
        }
        return false;
    }

    private static final Pattern DUPLICATE_KEY = Pattern.compile(
        "[dD]uplicate|[tT]aken|[nN]umberExists|[cC]odeExists|[aA]lreadyUsed|[nN]otUnique");

    /** Duplicate-shaped keys that do not refuse a value the user typed, each with why. Shrinks only. */
    private static final Map<String, String> NOT_A_TYPED_VALUE = Map.ofEntries(
        Map.entry("lab.order.duplicate",
            "the same test for the same patient at the same minute: the order as a whole, not a field"),
        Map.entry("slot.justTaken",
            "the slot was booked by someone else meanwhile: a state of the slot, not a typed value"),
        Map.entry("transfer.r7.destinationTaken", "a pending transfer holds the destination bed: a state of the bed"),
        Map.entry("immunization.r25.duplicateDose",
            "the same dose (vaccine, dose number, date) is already recorded: the record as a whole"),
        Map.entry("platform.releaseWindow.duplicate",
            "a 409 conflict on the platform release calendar (super-admin), kept as a conflict"),
        Map.entry("provider.licence.duplicate",
            "a 409 conflict on provider verification (a licence held by another facility); standing debt D1301"),
        Map.entry("provider.business.duplicate",
            "a 409 conflict on provider verification (RCCM/IFU held by another facility); standing debt D1301"),
        Map.entry("users.username.taken",
            "AuthController#changeOwnUsername wraps it in its own MessageResponse; standing debt D1301"));

    private static final Pattern IF_OPEN = Pattern.compile("\\bif\\s*+\\(");
    /** A getter of a variable: {@code request.getReason()}. */
    private static final Pattern GETTER = Pattern.compile("\\b(\\w++)\\.get(\\w++)\\(\\)");
    /** Exceptions that are not a 400 refusal of the body (a missing record, a forbidden caller). */
    private static final Set<String> NOT_A_REFUSAL_OF_THE_BODY = Set.of(
        "ResourceNotFoundException", "AccessDeniedException", "UnauthorizedException", "UnauthorizedAccessException",
        "ClientSafeAccessDeniedException", "ConflictException", "HospitalScopeRefusedException",
        "NullPointerException", "UnsupportedOperationException", "CdsCriticalBlockException");

    /**
     * Refusals the scan reads that are not a request field the form shows,
     * each with why ({@code Service#method:Getter}). Shrinks only.
     */
    private static final Map<String, String> CONTEXT_NOT_FIELD = Map.of();

    private static final Pattern METHOD_HEADER = Pattern.compile("(?m)^    [a-zA-Z][^\\n(;=]*+\\(");
    private static final Pattern LOCAL_INIT = Pattern.compile("\\b[A-Z]\\w*+\\s++(\\w++)\\s*+=([^;]++);");

    /**
     * QA D1299 (sweep): the same refusal one call away - a helper that
     * refuses a blank PARAMETER with no field ({@code requireStatusReason(status,
     * reason)}: {@code String trimmed = reason.trim(); if (trimmed.isEmpty()) throw ...}),
     * called with a request-body getter ({@code request.getStatusReason()}).
     */
    private static List<String> helperRefusalsOfBodyValues(Map<String, Class<?>> bodies) throws IOException {
        // Per file: a helper is called from its own class (private and static helpers alike).
        Map<Path, Map<String, Set<Integer>>> helpersByFile = new HashMap<>();
        Map<Path, String> sources = new LinkedHashMap<>();
        for (Path file : serviceFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            sources.put(file, source);
            Matcher header = METHOD_HEADER.matcher(source);
            while (header.find()) {
                int open = header.end() - 1;
                int close = SourceScan.closingParen(source, open);
                int brace = skipBlanks(source, close + 1);
                if (source.startsWith("throws", brace)) {
                    brace = source.indexOf('{', brace);
                }
                if (brace < 0 || brace >= source.length() || source.charAt(brace) != '{') {
                    continue;
                }
                String name = calledName(source, open);
                List<String> params = parameterNames(source.substring(open + 1, close));
                Set<Integer> refused = fieldlessParameterRefusals(
                    source.substring(brace, SourceScan.closingBrace(source, brace) + 1), params);
                if (name != null && !refused.isEmpty()) {
                    helpersByFile.computeIfAbsent(file, k -> new HashMap<>())
                        .computeIfAbsent(name, k -> new LinkedHashSet<>()).addAll(refused);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<Path, String> entry : sources.entrySet()) {
            String source = entry.getValue();
            for (Map.Entry<String, Set<Integer>> helper
                    : helpersByFile.getOrDefault(entry.getKey(), Map.of()).entrySet()) {
                Matcher call = Pattern.compile("[.\\s(!]" + Pattern.quote(helper.getKey()) + "\\(").matcher(source);
                while (call.find()) {
                    int open = call.end() - 1;
                    int lineStart = source.lastIndexOf('\n', open) + 1;
                    if (METHOD_HEADER.matcher(source.substring(lineStart, open + 1)).lookingAt()) {
                        continue;
                    }
                    List<String> args = splitTopLevel(source.substring(open + 1, SourceScan.closingParen(source, open)));
                    String method = methodAround(source, call.start());
                    if (method == null) {
                        continue;
                    }
                    for (int index : helper.getValue()) {
                        Matcher getter = index < args.size()
                            ? Pattern.compile("^(\\w++)\\.get(\\w++)\\(\\)$").matcher(args.get(index).trim()) : null;
                        if (getter != null && getter.find() && parameterType(method, getter.group(1), bodies) != null) {
                            out.add(entry.getKey().getFileName().toString().replace(".java", "") + "#" + methodName(method)
                                + " -> " + helper.getKey() + "(" + getter.group(2) + ") (line "
                                + SourceScan.lineOf(source, call.start()) + ", a helper refusing it with no field)");
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The method whose body holds {@code index}, or null (a field initialiser). */
    private static String methodAround(String source, int index) {
        Matcher header = METHOD_HEADER.matcher(source);
        String best = null;
        while (header.find() && header.start() < index) {
            int open = SourceScan.indexOfOutsideLiterals(source, '{', header.end());
            int semi = SourceScan.indexOfOutsideLiterals(source, ';', header.end());
            if (open < 0 || (semi >= 0 && semi < open)) {
                continue;
            }
            int close = SourceScan.closingBrace(source, open);
            if (close > index) {
                best = source.substring(header.start(), close);
            }
        }
        return best;
    }

    /** Indices of the parameters the body refuses, absent, with an exception naming no field. */
    private static Set<Integer> fieldlessParameterRefusals(String body, List<String> params) {
        Set<Integer> out = new LinkedHashSet<>();
        Matcher m = IF_OPEN.matcher(body);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingParen(body, open);
            String thrown = thrownRightAfter(body, close + 1);
            if (thrown == null || thrown.endsWith("FieldBusinessException")
                    || NOT_A_REFUSAL_OF_THE_BODY.contains(thrown.substring(thrown.lastIndexOf('.') + 1))) {
                continue;
            }
            String condition = body.substring(open + 1, close);
            for (int i = 0; i < params.size(); i++) {
                if (testsAbsent(condition, params.get(i)) || derivedLocalTestedAbsent(body, condition, params.get(i))) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    private static boolean derivedLocalTestedAbsent(String body, String condition, String param) {
        Matcher local = LOCAL_INIT.matcher(body);
        while (local.find()) {
            if (Pattern.compile("\\b" + Pattern.quote(param) + "\\b").matcher(local.group(2)).find()
                    && testsAbsent(condition, local.group(1))) {
                return true;
            }
        }
        return false;
    }

    /** {@code name == null}, {@code name.isBlank()} / {@code isEmpty()}, {@code isBlank(name)}, {@code !hasText(name)}. */
    private static boolean testsAbsent(String condition, String name) {
        String n = Pattern.quote(name);
        return Pattern.compile("(?<![.!\\w])" + n + "\\s*+==\\s*+null").matcher(condition).find()
            || Pattern.compile("(?<![.!\\w])" + n + "(?:\\.trim\\(\\))?\\.(?:isBlank|isEmpty)\\(\\)").matcher(condition).find()
            || Pattern.compile("(?<!!)\\s*+(?:\\w++\\.)?(?:isBlank|isEmpty|isNull)\\(\\s*+" + n + "\\s*+\\)").matcher(condition).find()
            || Pattern.compile("!\\s*+(?:\\w++\\.)?(?:hasText|hasLength)\\(\\s*+" + n + "\\s*+\\)").matcher(condition).find();
    }

    private static List<String> parameterNames(String params) {
        List<String> out = new ArrayList<>();
        for (String param : splitTopLevel(params)) {
            String[] words = param.trim().split("\\s++");
            if (words.length >= 2) {
                out.add(words[words.length - 1]);
            }
        }
        return out;
    }

    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        if (text.isBlank()) {
            return out;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '<' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == '>' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    /** The identifier right before offset {@code open} (a call's or a declaration's name), or null. */
    private static String calledName(String source, int open) {
        int start = open;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
            start--;
        }
        return start == open ? null : source.substring(start, open);
    }

    /** The exception type a {@code throw} right after {@code from} (maybe inside a {@code {}) names, or null. */
    private static String thrownRightAfter(String source, int from) {
        int at = skipBlanks(source, from);
        if (at < source.length() && source.charAt(at) == '{') {
            at = skipBlanks(source, at + 1);
        }
        Matcher thrown = THROW_NEW.matcher(source);
        thrown.region(at, source.length());
        return thrown.lookingAt() ? thrown.group(1) : null;
    }

    /**
     * The request-body getter the condition tests for absence (directly, or
     * through a local the method initialised from one), or null.
     */
    private static String absentBodyValue(String condition, String method, Map<String, Class<?>> bodies) {
        Matcher getter = GETTER.matcher(condition);
        while (getter.find()) {
            if (parameterType(method, getter.group(1), bodies) != null && testedAbsent(condition, getter)) {
                return getter.group(2);
            }
        }
        // A presence flag computed from the body and tested negated (the hasContent flag of the chat).
        Matcher negated = Pattern.compile("!\\s*+([a-z]\\w*+)\\b(?!\\s*+[.(])").matcher(condition);
        while (negated.find()) {
            Matcher local = Pattern.compile("\\bboolean\\s++" + Pattern.quote(negated.group(1))
                + "\\s*+=([^;]++);").matcher(method);
            if (!local.find()) {
                continue;
            }
            Matcher inner = GETTER.matcher(local.group(1));
            while (inner.find()) {
                if (parameterType(method, inner.group(1), bodies) != null) {
                    return inner.group(2);
                }
            }
        }
        return null;
    }

    /**
     * The getter's value is tested for ABSENCE: {@code x.getY() == null},
     * {@code x.getY().isBlank()} / {@code .isEmpty()} / {@code .trim().isEmpty()},
     * {@code isBlank(x.getY())}, {@code !hasText(x.getY())}, {@code isNull(x.getY())}
     * - not a presence test ({@code != null}, {@code hasText(...)}), not a
     * comparison of a value that was given.
     */
    private static boolean testedAbsent(String condition, Matcher getter) {
        String after = condition.substring(getter.end());
        if (Pattern.compile("^\\s*+==\\s*+null").matcher(after).find()) {
            return true;
        }
        if (Pattern.compile("^(?:\\.trim\\(\\))?\\.(?:isBlank|isEmpty)\\(\\)").matcher(after).find()) {
            // !x.getY().isBlank() is a presence test.
            int bang = getter.start() - 1;
            return bang < 0 || condition.charAt(bang) != '!';
        }
        String before = condition.substring(0, getter.start());
        Matcher wrapper = Pattern.compile("(!?+)\\s*+(?:\\w++\\.)?+(isBlank|isEmpty|hasText|hasLength|isNull)\\(\\s*+$")
            .matcher(before);
        if (!wrapper.find()) {
            return false;
        }
        boolean negated = !wrapper.group(1).isEmpty();
        boolean presence = wrapper.group(2).startsWith("has");
        return presence == negated;
    }

    private static int skipBlanks(String source, int from) {
        int i = from;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return i;
    }

    // ── helpers ──

    private record Handler(String where, Class<?> body, boolean validated) {
    }

    private static List<Handler> handlers() {
        List<Handler> out = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method m : controller.getDeclaredMethods()) {
                for (Parameter p : m.getParameters()) {
                    if (p.isAnnotationPresent(RequestBody.class) && p.getType().getName().startsWith("com.example.hms.")) {
                        boolean validated = p.isAnnotationPresent(Valid.class) || p.isAnnotationPresent(Validated.class);
                        out.add(new Handler(controller.getSimpleName() + "#" + m.getName(), p.getType(), validated));
                    }
                }
            }
        }
        return out;
    }

    /** Simple name to class of every request body and every payload class nested in one. */
    private static Map<String, Class<?>> requestBodyTypes() {
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Handler handler : handlers()) {
            collectNested(handler.body(), seen);
        }
        Map<String, Class<?>> out = new HashMap<>();
        seen.forEach(type -> out.put(type.getSimpleName(), type));
        return out;
    }

    private static void collectNested(Class<?> type, Set<Class<?>> seen) {
        if (!type.getName().startsWith("com.example.hms.") || type.isEnum() || !seen.add(type)) {
            return;
        }
        for (Field field : fields(type)) {
            collectNested(field.getType(), seen);
            if (field.getGenericType() instanceof ParameterizedType parameterized) {
                for (Type argument : parameterized.getActualTypeArguments()) {
                    if (argument instanceof Class<?> element) {
                        collectNested(element, seen);
                    }
                }
            }
        }
    }

    private static boolean declaresConstraints(Class<?> type, Set<Class<?>> seen) {
        if (!type.getName().startsWith("com.example.hms.") || type.isEnum() || !seen.add(type)) {
            return false;
        }
        if (hasConstraint(type.getAnnotations())) {
            return true;
        }
        for (Field field : fields(type)) {
            if (hasConstraint(field.getAnnotations())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasConstraint(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a.annotationType().isAnnotationPresent(jakarta.validation.Constraint.class)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNotNull(Class<?> type, String field) {
        for (Field f : fields(type)) {
            if (f.getName().equals(field)) {
                return f.isAnnotationPresent(jakarta.validation.constraints.NotNull.class);
            }
        }
        return false;
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** The request-body type of the parameter (or local) named {@code variable}, or null. */
    private static Class<?> parameterType(String method, String variable, Map<String, Class<?>> bodies) {
        int open = method.indexOf('(');
        int close = SourceScan.closingParen(method, open);
        String params = method.substring(open + 1, close);
        Matcher declared = Pattern.compile("([\\w.]++)\\s++" + Pattern.quote(variable) + "\\b").matcher(params);
        if (!declared.find()) {
            return null;
        }
        String type = declared.group(1);
        return bodies.get(type.substring(type.lastIndexOf('.') + 1));
    }

    /** {@link #LOOKUP} matched a lookup of a request id (see its comment). */
    static boolean isLookup(Matcher m) {
        String name = m.group(1);
        return (name.contains("ById") || name.endsWith("OrThrow")) && m.group(3).endsWith("Id")
            && m.group(3).length() > 2;
    }

    private static String methodName(String method) {
        Matcher name = Pattern.compile("(\\w++)\\s*+\\(").matcher(method);
        return name.find() ? name.group(1) : "?";
    }

    /** Method name to the getters it refuses null with a FieldBusinessException, joined. */
    private static Map<String, String> fieldNamedNullChecks(String source) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher check = Pattern.compile(
            "if\\s*+\\(\\s*+\\w+\\.get(\\w+Id)\\(\\)\\s*+==\\s*+null\\s*+\\)\\s*+\\{?\\s*+throw\\s++new\\s++(?:[a-z]\\w*+\\.)*+FieldBusinessException\\(")
            .matcher(source);
        while (check.find()) {
            String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, check.start());
            out.merge(methodName(method), check.group(1), (a, b) -> a + "," + b);
        }
        return out;
    }

    /**
     * A null of the getter was dealt with before the lookup: refused with the
     * field named (directly or in a helper called first), or tested at all - an
     * optional id branched on ({@code if (x.getYId() != null) { lookup }}); a
     * test that throws a field-less refusal is the third rule's business.
     */
    private static boolean refusedBefore(String before, String getter, Map<String, String> fieldChecks) {
        if (Pattern.compile("get" + getter + "\\(\\)\\s*+[!=]=\\s*+null|(?:isNull|nonNull|ofNullable)\\(\\s*+\\w+\\.get"
                + getter + "\\(\\)").matcher(before).find()) {
            return true;
        }
        for (Map.Entry<String, String> helper : fieldChecks.entrySet()) {
            if (List.of(helper.getValue().split(",")).contains(getter)
                    && Pattern.compile("\\b" + Pattern.quote(helper.getKey()) + "\\(").matcher(before).find()) {
                return true;
            }
        }
        return false;
    }

    /** {@code x.getYId() == null ? ... : lookup(x.getYId())} or {@code != null ? lookup(...)} in one statement. */
    private static boolean guardedInStatement(String source, int at, String getter) {
        String statement = source.substring(SourceScan.statementStart(source, at), at);
        return Pattern.compile("get" + getter + "\\(\\)\\s*+[!=]=\\s*+null\\s*+\\?").matcher(statement).find();
    }

    private static List<Path> serviceFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN.resolve("service"))) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static List<Class<?>> controllers() {
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("controller"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(f).toString().replace('\\', '/').replace(".java", "");
                try {
                    out.add(Class.forName("com.example.hms." + rel.replace('/', '.')));
                } catch (ClassNotFoundException e) {
                    // a file whose top-level type is not public-named after it: not a controller
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
