package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1385 (round 32, D1350 class): {@code GET /reception/waitlist?status=BOGUS}
 * and {@code /reception/queue?status=waiting} answered 200 with an empty list -
 * a {@code String} query parameter filtering on a closed list matched nothing
 * for an unknown value, so the desk read "nobody waiting" instead of "this
 * filter is wrong".
 *
 * <p>Every {@code @RequestParam String} whose name says it picks from a closed
 * list (a status, a type, a priority, an urgency, a role, a sort direction,
 * a category, ...) is read through
 * {@link com.example.hms.controller.support.QueryParameterValues#oneOf} in its
 * handler (refused by name, any case), or is listed below with where it is
 * checked or why it is not a closed list. A listed parameter that now goes
 * through {@code oneOf} is stale and fails. A parameter refused through
 * {@code oneOf} has a label ({@code field.label.param.<name>} or
 * {@code field.label.<name>}) in every language.
 */
@DisplayName("D1385: a String filter parameter on a closed list is refused by name, never matched against nothing")
class QueryParameterVocabularyGuardTest {

    private static final Path CONTROLLERS = Paths.get("src/main/java/com/example/hms/controller");

    private static final Set<String> VOCABULARY_NAMES = Set.of(
        "status", "type", "priority", "urgency", "role", "direction", "category", "action", "assignee",
        "assignedTo", "env", "environment", "kind", "scope", "view", "severity", "modality", "instrument", "sex",
        "gender");
    private static final List<String> VOCABULARY_ENDINGS = List.of("Status", "Type", "Category", "Priority");

    private static final String D1384 = "D1384 (#1043, round 32): checked by EnumQueryParam.refusal, fieldErrors.<param>, in ";
    private static final String NO_FIELD = "refused, but without a field (D1350 class, standing debt): ";

    /** "Controller#parameter" of a closed-list parameter checked elsewhere (where), or not a closed list (why). */
    private static final Map<String, String> CHECKED_ELSEWHERE = Map.ofEntries(
        Map.entry("AdmissionController#status", D1384 + "AdmissionServiceImpl.admissionStatusParam"),
        Map.entry("AdmissionController#admissionType", D1384 + "AdmissionServiceImpl.getOrderSetsByHospital"),
        Map.entry("NurseTaskController#status", D1384 + "NurseTaskServiceImpl.getHandoffSummaries"),
        Map.entry("NurseTaskController#assignee", D1384 + "NurseTaskController.resolveAssignee"),
        Map.entry("AppointmentController#action", "AppointmentServiceImpl: FieldBusinessException(action, appointment.invalidAction)"),
        Map.entry("AuditEventLogController#status", NO_FIELD + "audit.status.invalid in AuditEventLogController"),
        Map.entry("AuditEventLogController#entityType", "an open set: the entity names audit emitters wrote, paired with resourceId"),
        Map.entry("SuperAdminAuditSearchController#entityType", "an open set: the entity names audit emitters wrote"),
        Map.entry("GeneralReferralController#status", NO_FIELD + "GeneralReferralServiceImpl ReferralStatus.valueOf, raw text"),
        Map.entry("HospitalController#facilityType", NO_FIELD + "HospitalServiceImpl FacilityType, MSG_PROVIDER_TYPE_INVALID"),
        Map.entry("InstrumentOutboxController#status", NO_FIELD + "InstrumentOutboxServiceImpl instrumentOutbox.status.unknown"),
        Map.entry("TransfusionController#status", NO_FIELD + "TransfusionServiceImpl transfusion.unit/request.statusUnknown"),
        Map.entry("ProviderDirectoryController#type", NO_FIELD + "ProviderDirectoryServiceImpl MSG_TYPE_INVALID"),
        Map.entry("MeController#status", "OPEN (standing debt, D1385 class): DoctorWorklistServiceImpl composes the statuses"
            + " from encounters, appointments and consults; an unknown one lists nothing"),
        Map.entry("MeController#urgency", "OPEN (standing debt, D1385 class): DoctorWorklistServiceImpl urgencies come from"
            + " several enums; an unknown one lists nothing"),
        Map.entry("UserController#role", "OPEN (standing debt, D1385 class): roles are rows of the roles table, not a"
            + " code list; an unknown role answers an empty page"),
        Map.entry("FeatureFlagController#env", "OPEN (standing debt, D1385 class): the environments are the"
            + " configured app.feature-flags.environments keys; an unknown one reads the base flags"),
        Map.entry("ProResponseController#instrument", "OPEN (standing debt, D1385 class): the instruments are catalogue rows"
            + " (ProInstrumentService); an unknown code answers an empty history"),
        Map.entry("PatientController#assignedTo", "OPEN (standing debt, D1385 class): only 'me' filters; any other value"
            + " is ignored and the whole list comes back"),
        Map.entry("FileUploadController#category", "echoed back in the upload response, never stored or filtered on"),
        Map.entry("LabTestDefinitionController#category", "a free-text label kept as typed (QA D836), matched any case"));

    private static final Pattern STRING_PARAM = Pattern.compile(
        "@RequestParam(\\((?<args>[^)]*)\\))?(?:\\s*+@\\w+(?:\\([^)]*\\))?)*\\s++(?:final\\s++)?String\\s++(?<ident>\\w++)");
    private static final Pattern NAME_ARG = Pattern.compile("(?:name|value)\\s*=\\s*\"(\\w+)\"|^\\s*\"(\\w+)\"");

    record Param(String controller, String name, String ident, String body) {
        String key() {
            return controller + "#" + name;
        }
    }

    @Test
    @DisplayName("every closed-list String parameter goes through QueryParameterValues.oneOf, or is listed with its check")
    void everyClosedListParameterIsRefusedByName() throws IOException {
        Set<String> unchecked = new TreeSet<>();
        Set<String> viaOneOf = new TreeSet<>();
        for (Param p : params()) {
            if (!isVocabulary(p.name())) {
                continue;
            }
            if (readThroughOneOf(p)) {
                viaOneOf.add(p.key());
            } else {
                unchecked.add(p.key());
            }
        }
        assertThat(viaOneOf).as("parameters read through oneOf")
            .contains("ReceptionController#status", "MaternalHistoryController#riskCategory", "BirthPlanController#direction");
        Set<String> stale = new TreeSet<>(CHECKED_ELSEWHERE.keySet());
        stale.removeAll(unchecked);
        unchecked.removeAll(CHECKED_ELSEWHERE.keySet());
        assertThat(stale).as("listed parameters that are now read through oneOf or are gone (remove them)").isEmpty();
        assertThat(unchecked)
            .as("a String parameter picking from a closed list matches nothing for an unknown value (QA D1385):"
                + " read it with QueryParameterValues.oneOf(name, value, CODES), or list it with where it is checked")
            .isEmpty();
    }

    @Test
    @DisplayName("every parameter refused through oneOf has a label in EN, FR and ES")
    void everyRefusedParameterHasALabel() throws IOException {
        Pattern call = Pattern.compile("QueryParameterValues\\.oneOf\\(\\s*\"(\\w+)\"");
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = call.matcher(Files.readString(f));
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        assertThat(names).isNotEmpty();
        Map<String, List<String>> missing = new TreeMap<>();
        for (String suffix : List.of("_en", "_fr", "_es")) {
            Properties labels = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/i18n/messages-common" + suffix + ".properties")) {
                labels.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String name : names) {
                String label = labels.getProperty("field.label.param." + name, labels.getProperty("field.label." + name));
                if (label == null || label.isBlank()) {
                    missing.computeIfAbsent(name, k -> new ArrayList<>()).add(suffix);
                }
            }
        }
        assertThat(missing).as("a refused filter parameter with no label is refused naming nothing").isEmpty();
    }

    static boolean isVocabulary(String name) {
        return VOCABULARY_NAMES.contains(name) || VOCABULARY_ENDINGS.stream().anyMatch(name::endsWith);
    }

    private static boolean readThroughOneOf(Param p) {
        return Pattern.compile("QueryParameterValues\\.oneOf\\(\\s*\"" + Pattern.quote(p.name()) + "\",\\s*"
            + Pattern.quote(p.ident()) + "\\b").matcher(p.body()).find();
    }

    /** Every {@code @RequestParam String} of every controller, with its handler's body. */
    static List<Param> params() throws IOException {
        List<Param> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(f);
                String controller = f.getFileName().toString().replace(".java", "");
                Matcher m = STRING_PARAM.matcher(source);
                while (m.find()) {
                    String ident = m.group("ident");
                    out.add(new Param(controller, nameOf(m.group("args"), ident), ident, bodyAfter(source, m.end())));
                }
            }
        }
        assertThat(out).as("String query parameters found").hasSizeGreaterThan(100);
        return out;
    }

    private static String nameOf(String args, String ident) {
        if (args != null) {
            Matcher n = NAME_ARG.matcher(args);
            if (n.find()) {
                return n.group(1) != null ? n.group(1) : n.group(2);
            }
        }
        return ident;
    }

    /** The body of the method whose parameter list contains {@code from}: the next balanced braces after the list closes. */
    static String bodyAfter(String source, int from) {
        int depth = 1;
        int i = from;
        for (; i < source.length() && depth > 0; i++) {
            char c = source.charAt(i);
            if (c == '"') {
                i = skipString(source, i);
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        int open = source.indexOf('{', i);
        if (open < 0) {
            return "";
        }
        int braces = 0;
        for (int j = open; j < source.length(); j++) {
            char c = source.charAt(j);
            if (c == '"') {
                j = skipString(source, j);
            } else if (c == '{') {
                braces++;
            } else if (c == '}' && --braces == 0) {
                return source.substring(open, j + 1);
            }
        }
        return source.substring(open);
    }

    private static int skipString(String source, int quote) {
        if (source.startsWith("\"\"\"", quote)) {
            int end = source.indexOf("\"\"\"", quote + 3);
            return end < 0 ? source.length() : end + 2;
        }
        for (int k = quote + 1; k < source.length(); k++) {
            char c = source.charAt(k);
            if (c == '\\') {
                k++;
            } else if (c == '"') {
                return k;
            }
        }
        return source.length();
    }
}
