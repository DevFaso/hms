package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2730 (r65, High): a caregiver whose grant was « Dossier de santé
 * uniquement » read « Sérologie syphilis (TPHA) : Positif » and the medicine of
 * an HIV-tagged visit. Every proxy read went through the patient's own read,
 * and none asked the sensitivity tag.
 *
 * <p>The class is closed by eleven rules over the source. None of them trusts a
 * name or a path alone, so a new proxy read fails here until it goes through
 * {@code ProxyRecordFilter}:
 * <ol>
 *   <li><b>who may touch a grant</b>: every method that references the grant
 *       table ({@code PatientProxy}, its repository) or resolves a grantor
 *       ({@code resolveProxyRecordsPatientId}) is on an allow-list, each entry
 *       with its reason;</li>
 *   <li><b>the endpoints</b>: every handler mapped under {@code proxy-access/{…}}
 *       - found by its mapping annotation however it is written - and every
 *       handler that calls a grant check, calls nothing but the portal
 *       service's grant-checked reads or a {@code Reader.PROXY} read;</li>
 *   <li><b>the portal service</b>: every method that checks a grant, and every
 *       helper it reaches, calls a collaborator only through a
 *       {@code *ForProxy} read or the filter itself; anything else is listed
 *       with its reason;</li>
 *   <li><b>the reads</b>: every {@code *ForProxy} method applies
 *       {@code proxyRecordFilter}, and the imaging service filters a
 *       {@code Reader.PROXY} read;</li>
 *   <li><b>the fields</b>: every field of the proxy's « Dossier de santé »
 *       ({@code HealthSummaryDTO}, and the profile inside it) is either filled
 *       by a filtered read or listed with why it needs none.</li>
 * </ol>
 *
 * <p>Policy 2026-11-20 (provisional) lets a proxy read a tagged row in three
 * cases: a tuberculosis row for a grant that names that scope, a child's
 * record for a verified parent, and a minor's result a clinician released.
 * Four more rules keep those exceptions where they were decided:
 * <ol start="6">
 *   <li><b>who is reading</b>: only the grant check opens a proxy read
 *       context, and only the filter consults it;</li>
 *   <li><b>the releases</b>: only listed code touches a release
 *       ({@code ProxySensitiveRelease}, its repository), each with its reason;</li>
 *   <li><b>the categories</b>: every {@code SensitivityCategory} has a rule for
 *       a minor in {@code ProxySensitivityPolicy} and a label in the portal;</li>
 *   <li><b>the surrounding records</b>: the filter keeps judging a panel by its
 *       components, a prescription by its medicine and an invoice by the tests
 *       it bills, and reaches an exception through the policy only.</li>
 * </ol>
 *
 * <p>The independent review of #1242 found two more ways out, each now a rule:
 * <ol start="10">
 *   <li><b>the caller's tenant scope</b>: a proxy holds no assignment where the
 *       grantor was treated, so a read that goes through a finder the tenant
 *       filter narrows to the CALLER's hospitals loads nothing and judges
 *       nothing. Neither the filter nor a {@code *ForProxy} read calls one on a
 *       tenant-scoped entity;</li>
 *   <li><b>the release itself</b>: the release service keeps every check of a
 *       release (a minor, a clinician with care, a verified parent's grant
 *       that is not the clinician's own, an HIV result only).</li>
 * </ol>
 */
@DisplayName("D2730: every proxy read withholds sensitive records")
class ProxyReadSensitivityGuardTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/hms");
    private static final String PORTAL_SERVICE = "service/impl/PatientPortalServiceImpl.java";
    private static final String GRANT_CHECK = "verifyProxyAccess";
    /** Policy 2026-11-20: the clinician's release of a minor's result. */
    private static final String RELEASE_SERVICE = "service/recordaccess/MinorResultReleaseServiceImpl.java";
    private static final String GRANTOR_RESOLVER = "resolveProxyRecordsPatientId";

    /**
     * A class member's declaration: at member indentation, any modifiers (or
     * none: a package-private method counts), a type, then the name and its "(".
     */
    private static final Pattern MEMBER = Pattern.compile("\\n {4}(?! )([^\\n(;={}]*+)\\(");

    /** What starts a statement, not a declaration. */
    private static final Set<String> STATEMENT_WORDS = Set.of("return", "new", "else", "throw", "case");
    /** What a parenthesis follows in a statement. */
    private static final Set<String> CONTROL_WORDS =
        Set.of("if", "for", "while", "switch", "catch", "synchronized", "try");
    private static final Pattern IDENTIFIER = Pattern.compile("\\w+");

    /** The member's name, when the text before a "(" is a declaration: a type (and modifiers), then a name. */
    private static String declaredName(String beforeParenthesis) {
        String[] words = beforeParenthesis.strip().split("\\s+");
        String name = words[words.length - 1];
        if (words.length < 2 || !IDENTIFIER.matcher(name).matches() || CONTROL_WORDS.contains(name)
                || STATEMENT_WORDS.contains(words[0]) || words[0].startsWith("@") || words[0].startsWith("}")) {
            return null;
        }
        return name;
    }

    /** An injected collaborator: {@code private final Type name;}. */
    private static final Pattern FIELD = Pattern.compile(
        "\\n    private final [\\w<>\\[\\],.? ]+?\\s(\\w+)\\s*;");

    /** A call or a method reference on something: {@code receiver.method(} or {@code receiver::method}. */
    private static final Pattern CALL = Pattern.compile("\\b(\\w+)\\s*(?:\\.|::)\\s*(\\w+)\\b(\\s*\\()?");

    /** An unqualified call, or {@code this::name}: a helper of the same class. */
    private static final Pattern LOCAL_CALL = Pattern.compile("(?:(?<![\\w.:])|this::|this\\.)(\\w+)\\s*(?:\\(|\\b)");

    /** The filter actually used: a predicate over rows, not a mention. */
    private static final Pattern FILTER_USE = Pattern.compile(
        "proxyRecordFilter\\s*(?:::\\s*admits|\\.\\s*admits\\s*\\(|\\.\\s*labResults\\s*\\(|\\.\\s*sensitiveAmong\\s*\\()");

    /** What names a grant or resolves a grantor. */
    private static final Pattern GRANT_TOKEN = Pattern.compile(
        "\\bPatientProxy(?:Repository)?\\b|\\bpatientProxyRepository\\b|\\b" + GRANTOR_RESOLVER + "\\b");

    // ── Rule 1 ──────────────────────────────────────────────────────────────

    /**
     * Everything that may touch a family-access grant. "file" for a
     * declaration, "file#method" for a use. Adding an entry means writing why
     * that code cannot hand a grantor's sensitive row to a proxy.
     */
    private static final Map<String, String> GRANT_USERS = Map.ofEntries(
        Map.entry("model/PatientProxy.java", "the grant entity itself"),
        Map.entry("repository/PatientProxyRepository.java", "the grant repository itself"),
        Map.entry("service/PatientPortalService.java",
            "declares the grant-checked reads; their bodies are held to rule 3"),
        Map.entry(PORTAL_SERVICE, "imports and injects the repository; each use is listed by method below"),
        Map.entry(PORTAL_SERVICE + "#getMyProxies", "the grantor lists the grants they gave: grant rows only"),
        Map.entry(PORTAL_SERVICE + "#grantProxy", "the grantor creates a grant: writes a grant row, reads no record"),
        Map.entry(PORTAL_SERVICE + "#revokeProxy", "the grantor revokes a grant: writes a grant row"),
        Map.entry(PORTAL_SERVICE + "#getMyProxyAccess",
            "the proxy lists the grants they hold: grant rows only (names and scopes)"),
        Map.entry(PORTAL_SERVICE + "#toProxyResponseDTO", "maps a grant row: names, scope, dates"),
        Map.entry(PORTAL_SERVICE + "#isProxyExpired", "reads a grant's expiry"),
        Map.entry(PORTAL_SERVICE + "#" + GRANT_CHECK,
            "the grant check itself; every caller is a proxy read held to rule 3"),
        Map.entry(PORTAL_SERVICE + "#" + GRANTOR_RESOLVER,
            "returns the grantor's id after the grant check and reads nothing; its callers are held to rule 2"),
        Map.entry("service/PatientServiceImpl.java", "imports and injects the repository for the use below"),
        Map.entry("service/PatientServiceImpl.java#deletePatient",
            "deletes a deleted patient's grants: no read reaches a proxy"),
        Map.entry("service/maternity/NewbornRegistrationService.java",
            "imports and injects the repository for the use below"),
        Map.entry("service/maternity/NewbornRegistrationService.java#grantMotherProxy",
            "gives the mother a grant on her newborn: writes a grant row, reads no record"),
        Map.entry(RELEASE_SERVICE,
            "policy 2026-11-20: the clinician's release of a minor's result; imports and injects the repository"),
        Map.entry(RELEASE_SERVICE + "#overviewOf",
            "the clinician's screen: the parents' grants of a patient in their care (names, relationship, verified)"),
        Map.entry(RELEASE_SERVICE + "#requireGuardianGrant",
            "loads the grant a release names and checks it is this patient's: a grant row, no record"),
        Map.entry(RELEASE_SERVICE + "#isLive", "reads a grant's status and expiry"),
        Map.entry(RELEASE_SERVICE + "#isParentOrGuardian",
            "reads a grant's declared relationship"),
        Map.entry(RELEASE_SERVICE + "#verifyGuardian",
            "a clinician verifies a parent's relationship: writes a grant row, hands the proxy nothing"),
        Map.entry(RELEASE_SERVICE + "#release",
            "a clinician releases one result to one grant: writes a release row; the proxy still reads through the filter"),
        Map.entry(RELEASE_SERVICE + "#stampVerified",
            "stamps a grant verified: writes a grant row"),
        Map.entry(RELEASE_SERVICE + "#refuseOwnGrant",
            "reads who holds a grant, to refuse the clinician their own: hands the proxy nothing"),
        Map.entry("controller/PatientImagingPortalController.java#getProxyImaging",
            "resolves the grantor, then reads with Reader.PROXY (rule 2 checks it)"),
        Map.entry("controller/PatientImagingPortalController.java#getProxyImagingImage",
            "resolves the grantor, then reads with Reader.PROXY (rule 2 checks it)"));

    @Test
    @DisplayName("rule 1: only listed code touches a grant or resolves a grantor, each with its reason")
    void onlyListedCodeTouchesAGrant() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path file : sources("")) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher token = GRANT_TOKEN.matcher(code);
            if (!token.find()) {
                continue;
            }
            List<Member> members = membersOf(code);
            token.reset();
            while (token.find()) {
                int at = token.start();
                String owner = members.stream().filter(m -> m.holds(at)).map(Member::name).findFirst().orElse(null);
                found.add(owner == null ? rel(file) : rel(file) + "#" + owner);
            }
        }
        List<String> problems = new ArrayList<>();
        for (String use : found) {
            if (!GRANT_USERS.containsKey(use)) {
                problems.add(use + " touches a family-access grant (PatientProxy, its repository or "
                    + GRANTOR_RESOLVER + ") and is not on the allow-list: a proxy read must go through"
                    + " PatientPortalServiceImpl's grant-checked reads; list it in GRANT_USERS with the reason"
                    + " it cannot hand a sensitive row to a proxy");
            }
        }
        for (String listed : GRANT_USERS.keySet()) {
            if (!found.contains(listed)) {
                problems.add(listed + " is on the grant allow-list and no longer touches a grant: remove the entry");
            }
        }
        assertThat(GRANT_USERS.values()).as("every entry carries its reason").allMatch(reason -> !reason.isBlank());
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    // ── Rule 2 ──────────────────────────────────────────────────────────────

    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\b");
    private static final Pattern STRING_CONSTANT = Pattern.compile(
        "\\bString\\s+(\\w+)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\")\\s*;");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** What a mapping annotation may hold besides its paths. */
    private static final Pattern MAPPING_NOISE = Pattern.compile(
        "\\b(?:value|path|produces|consumes|method|params|headers|name)\\s*="
            + "|\\b(?:[\\w.]+\\.)?(?:MediaType|RequestMethod)\\.\\w+");
    private static final Pattern PROXY_PATH = Pattern.compile("proxy-access/\\{");

    /** Mappings whose path is built from another class's constant, with what that path is. */
    private static final Map<String, String> UNREADABLE_MAPPINGS = Map.of(
        "controller/ProfileImageController.java",
        "/uploads/<FileUploadService.PROFILE_IMAGES_PATH>/{fileName}: an upload directory name, no proxy path");

    @Test
    @DisplayName("rule 2: every proxy endpoint, however it is mapped, reads through a proxy read")
    void everyProxyEndpointReadsThroughAProxyRead() throws IOException {
        Set<String> grantChecked = grantCheckedMethods().keySet();
        List<String> problems = new ArrayList<>();
        Set<String> endpoints = new TreeSet<>();
        for (Path file : sources("controller")) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            int classAt = classDeclaration(code);
            if (classAt < 0) {
                continue;
            }
            Map<String, String> constants = new LinkedHashMap<>();
            Matcher constant = STRING_CONSTANT.matcher(code);
            while (constant.find()) {
                constants.put(constant.group(1), constant.group(2));
            }
            Set<String> collaborators = fieldsOf(code);
            List<String> prefixes = new ArrayList<>(List.of(""));
            List<Member> members = membersOf(code);
            Matcher mapping = MAPPING.matcher(code);
            while (mapping.find()) {
                List<String> paths = pathsOf(code, mapping.end(), constants, rel(file), problems);
                if (mapping.start() < classAt) {
                    prefixes = paths;
                    continue;
                }
                int from = mapping.end();
                Member handler = members.stream().filter(m -> m.declaredAt() >= from).findFirst().orElse(null);
                if (handler == null) {
                    continue;
                }
                boolean byPath = prefixes.stream().anyMatch(prefix -> paths.stream()
                    .anyMatch(path -> PROXY_PATH.matcher(prefix + path).find()));
                boolean byCall = callsOn(handler.body(), collaborators).stream()
                    .anyMatch(call -> grantChecked.contains(call.method()));
                if (byPath || byCall) {
                    endpoints.add(rel(file) + "#" + handler.name());
                    checkEndpoint(rel(file) + "#" + handler.name(), handler.body(), collaborators, grantChecked,
                        problems);
                }
            }
        }
        assertThat(endpoints).as("the proxy endpoints were found").contains(
            "controller/PatientPortalController.java#getProxyAppointments",
            "controller/PatientPortalController.java#getProxyMedications",
            "controller/PatientPortalController.java#getProxyMedicationsPage",
            "controller/PatientPortalController.java#countProxyLabResults",
            "controller/PatientPortalController.java#getProxyLabResults",
            "controller/PatientPortalController.java#getProxyBilling",
            "controller/PatientPortalController.java#getProxyRecords",
            "controller/PatientImagingPortalController.java#getProxyImaging",
            "controller/PatientImagingPortalController.java#getProxyImagingImage");
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    /**
     * A proxy endpoint may call the portal service's grant-checked reads, and
     * - once it has resolved the grantor - a service read that is told the
     * reader is a proxy. Any other call on a collaborator is a read (or a
     * write) made with the grantor's id and no filter.
     */
    private static void checkEndpoint(String key, String body, Set<String> collaborators, Set<String> grantChecked,
                                      List<String> problems) {
        List<Call> calls = callsOn(body, collaborators);
        boolean checksAGrant = false;
        for (Call call : calls) {
            if (grantChecked.contains(call.method())) {
                checksAGrant = true;
            } else if (!call.arguments().contains("Reader.PROXY")) {
                problems.add(key + " is a proxy endpoint and calls " + call.receiver() + "." + call.method()
                    + ", which is neither a grant-checked read of the portal service nor a Reader.PROXY read:"
                    + " a sensitive (HIV, behavioural, substance-use, reproductive) row reaches the proxy");
            }
        }
        if (!checksAGrant) {
            problems.add(key + " is mapped under proxy-access/{id} and checks no grant: call a grant-checked read of"
                + " the portal service");
        }
    }

    // ── Rule 3 ──────────────────────────────────────────────────────────────

    /**
     * What a grant-checked method of the portal service may call on a
     * collaborator besides a {@code *ForProxy} read and the filter:
     * "collaborator.method" to the reason it hands no sensitive row over.
     */
    private static final Map<String, String> NEUTRAL_CALLS = Map.ofEntries(
        Map.entry("isolatedReads.attempt",
            "runs the read it is given in its own transaction and reads nothing itself; the read is checked here"),
        Map.entry("hospitalRepository.findById",
            "the profile header: the name of a hospital the patient is registered at"),
        Map.entry("registrationRepository.findByPatientId",
            "the profile header: the patient's hospitals and record numbers, identity not clinical content"),
        Map.entry("log.info", "logging"),
        Map.entry("log.warn", "logging"),
        Map.entry("log.debug", "logging"));

    /** Grant checks that read nothing themselves, with why. */
    private static final Map<String, String> RESOLVERS = Map.of(
        GRANTOR_RESOLVER, "returns the grantor's id only; its callers pass Reader.PROXY (rule 2)");

    @Test
    @DisplayName("rule 3: a grant-checked method reaches a collaborator only through a *ForProxy read or the filter")
    void everyGrantCheckedReadGoesThroughTheFilter() throws IOException {
        String code = portalService();
        Map<String, String> methods = methodsOf(code);
        Set<String> collaborators = fieldsOf(code);
        Map<String, String> grantChecked = grantCheckedMethods();
        List<String> problems = new ArrayList<>();
        Set<String> usedNeutral = new TreeSet<>();
        grantChecked.forEach((name, body) -> {
            if (RESOLVERS.containsKey(name)) {
                return;
            }
            Map<String, String> reached = closure(name, body, methods);
            boolean readsThroughAFilter = false;
            for (Map.Entry<String, String> helper : reached.entrySet()) {
                if (helper.getKey().endsWith("ForProxy")) {
                    // A proxy read of this class: rule 4 holds it to the filter.
                    readsThroughAFilter = true;
                    continue;
                }
                for (Call call : callsOn(helper.getValue(), collaborators)) {
                    String target = call.receiver() + "." + call.method();
                    if (call.method().endsWith("ForProxy") || call.receiver().equals("proxyRecordFilter")) {
                        readsThroughAFilter = true;
                    } else if (NEUTRAL_CALLS.containsKey(target)) {
                        usedNeutral.add(target);
                    } else {
                        problems.add(PORTAL_SERVICE + "#" + name + " checks a proxy grant and"
                            + (helper.getKey().equals(name) ? "" : ", through " + helper.getKey() + ",")
                            + " calls " + target + ": not a *ForProxy read, so a sensitive (HIV, behavioural,"
                            + " substance-use, reproductive) row reaches the proxy. Read through a *ForProxy"
                            + " method that applies proxyRecordFilter, or list the call in NEUTRAL_CALLS with why"
                            + " it hands over nothing sensitive");
                    }
                }
            }
            if (!readsThroughAFilter) {
                problems.add(PORTAL_SERVICE + "#" + name + " checks a proxy grant and reads through no *ForProxy"
                    + " method: list it in RESOLVERS with its reason if it returns no record");
            }
        });
        for (String listed : NEUTRAL_CALLS.keySet()) {
            if (!listed.startsWith("log.") && !usedNeutral.contains(listed)) {
                problems.add(listed + " is listed in NEUTRAL_CALLS and no proxy read calls it: remove the entry");
            }
        }
        assertThat(grantChecked).as("the proxy reads were found").containsKeys("getProxyRecords",
            "getProxyLabResults", "countProxyLabResults", "getProxyMedications", "getProxyMedicationsPage",
            "getProxyAppointments", "getProxyBilling", GRANTOR_RESOLVER);
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    // ── Rule 4 ──────────────────────────────────────────────────────────────

    /** Reader-based proxy paths outside the *ForProxy naming: "file#method" to the token its body holds. */
    private static final Map<String, String> READER_PATHS = Map.of(
        "service/imaging/PatientImagingPortalService.java#studiesOf", "shownTo(reader",
        "service/imaging/PatientImagingPortalService.java#openImage", "shownTo(reader");

    @Test
    @DisplayName("rule 4: every *ForProxy read, and every Reader.PROXY path, applies the filter")
    void everyProxyReadAppliesTheFilter() throws IOException {
        List<String> problems = new ArrayList<>();
        int proxyReads = 0;
        for (Path file : sources("")) {
            String rel = rel(file);
            if (rel.startsWith("repository/") || rel.startsWith("controller/")) {
                continue;
            }
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            for (Member member : membersOf(code)) {
                if (!member.name().endsWith("ForProxy")) {
                    continue;
                }
                proxyReads++;
                if (!FILTER_USE.matcher(member.body()).find()) {
                    problems.add(rel + "#" + member.name() + " is a proxy read and does not filter its rows with"
                        + " proxyRecordFilter (admits, labResults() or sensitiveAmong)");
                }
            }
        }
        for (Map.Entry<String, String> path : READER_PATHS.entrySet()) {
            String[] parts = path.getKey().split("#");
            String code = SourceScan.stripComments(Files.readString(ROOT.resolve(parts[0]), StandardCharsets.UTF_8));
            String body = SourceScan.methodBody(code, parts[1]);
            if (body == null || !body.contains(path.getValue())) {
                problems.add(path.getKey() + " no longer holds `" + path.getValue() + "`: a Reader.PROXY read"
                    + " must drop a study of a sensitive visit");
            }
        }
        String imaging = SourceScan.stripComments(Files.readString(
            ROOT.resolve("service/imaging/PatientImagingPortalService.java"), StandardCharsets.UTF_8));
        assertThat(SourceScan.methodBody(imaging, "shownTo")).as("the imaging filter applies the proxy filter")
            .containsPattern(FILTER_USE);
        // Labs x3, medications x3, immunizations, invoices, appointments, vitals, diagnoses.
        assertThat(proxyReads).as("the *ForProxy reads were found").isGreaterThanOrEqualTo(11);
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    // ── Rule 5 ──────────────────────────────────────────────────────────────

    /**
     * The fields of the proxy's « Dossier de santé » that no filtered read
     * fills, each with why. A field that is neither here nor filled by a
     * filtered read fails the guard, so a new card cannot ship unclassified.
     */
    private static final Map<String, String> SUMMARY_UNFILTERED = Map.of(
        "profile",
        "who the patient is and how to reach them, built by proxyProfileOf and classified field by field below",
        "allergies",
        "the patient's allergy list: what must not be given, which a caregiver needs to give a medicine safely;"
            + " it names substances, never a visit, a result or a diagnosis",
        "chronicConditions",
        "always empty for a proxy: free text that names diagnoses with no tag in the data model");

    /** What an unfiltered field's builder call must read, so the reason above stays true. */
    private static final Map<String, String> SUMMARY_UNFILTERED_SOURCE = Map.of(
        "profile", "proxyProfileOf(",
        "allergies", "patient.getAllergies()",
        "chronicConditions", "Collections.emptyList()");

    /** The profile fields a proxy never receives: proxyProfileOf clears each. */
    private static final Map<String, String> PROFILE_WITHHELD = Map.of(
        "chronicConditions", "free text that names diagnoses (« VIH », « dépression ») with no tag");

    /** Every other profile field, by why it is not sensitive clinical content. */
    private static final Map<String, Set<String>> PROFILE_SHOWN = Map.of(
        "identity: who the patient is; the national id is masked",
        Set.of("id", "nationalUniqueIdMasked", "firstName", "lastName", "middleName", "dateOfBirth", "gender",
            "ethnicity", "username", "profileImageUrl"),
        "contact: how to reach the patient, and who to call for them",
        Set.of("phoneNumberPrimary", "phoneNumberSecondary", "email", "addressLine1", "addressLine2", "city",
            "state", "zipCode", "country", "emergencyContactName", "emergencyContactPhone",
            "emergencyContactRelationship"),
        "registration: where the patient is registered and under which record number; no department, no visit",
        Set.of("hospitalId", "hospitalName", "mrn", "hospitalRecords"),
        "safety and logistics: blood group, what must not be given, where medicines are collected",
        Set.of("bloodType", "allergies", "preferredPharmacy"));

    private static final Pattern DTO_FIELD = Pattern.compile(
        "\\n    private (?!static)[\\w<>\\[\\],.? ]+?\\s(\\w+)\\s*(?:=[^;]*)?;");

    @Test
    @DisplayName("rule 5: every field of the proxy's health record is filtered, or listed with why it needs none")
    void everyFieldOfTheProxyRecordIsFilteredOrListed() throws IOException {
        String code = portalService();
        Map<String, String> methods = methodsOf(code);
        Set<String> collaborators = fieldsOf(code);
        Map<String, String> built = builderCalls(methods.get("getProxyRecords"), "HealthSummaryDTO.builder()");
        List<String> problems = new ArrayList<>();
        Set<String> summaryFields = dtoFields("payload/dto/portal/HealthSummaryDTO.java");
        for (String field : summaryFields) {
            String source = built.get(field);
            if (SUMMARY_UNFILTERED.containsKey(field)) {
                if (source == null || !source.contains(SUMMARY_UNFILTERED_SOURCE.get(field))) {
                    problems.add("HealthSummaryDTO." + field + " is listed as needing no filter because it reads `"
                        + SUMMARY_UNFILTERED_SOURCE.get(field) + "`, and getProxyRecords now fills it with `" + source
                        + "`: filter it, or correct the entry and its reason");
                }
            } else if (source == null) {
                problems.add("HealthSummaryDTO." + field + " is not filled by getProxyRecords and not classified:"
                    + " fill it through a *ForProxy read, or list it in SUMMARY_UNFILTERED with why");
            } else if (!readsOnlyThroughAProxyRead(source, methods, collaborators)) {
                problems.add("HealthSummaryDTO." + field + " reaches the proxy through `" + source.strip()
                    + "`, which is not a *ForProxy read: a sensitive row is shown. Read it through a method that"
                    + " applies proxyRecordFilter, or list it in SUMMARY_UNFILTERED with why it needs none");
            }
        }
        for (String listed : SUMMARY_UNFILTERED.keySet()) {
            if (!summaryFields.contains(listed)) {
                problems.add(listed + " is listed in SUMMARY_UNFILTERED and is no field of HealthSummaryDTO");
            }
        }

        Set<String> classified = new LinkedHashSet<>(PROFILE_WITHHELD.keySet());
        PROFILE_SHOWN.values().forEach(classified::addAll);
        Set<String> profileFields = dtoFields("payload/dto/portal/PatientProfileDTO.java");
        for (String field : profileFields) {
            if (!classified.contains(field)) {
                problems.add("PatientProfileDTO." + field + " reaches a proxy inside the health record and is not"
                    + " classified: list it in PROFILE_SHOWN under its reason, or in PROFILE_WITHHELD and clear it"
                    + " in proxyProfileOf");
            }
        }
        String proxyProfileOf = methods.get("proxyProfileOf");
        assertThat(proxyProfileOf).as("proxyProfileOf exists").isNotNull();
        for (String withheld : PROFILE_WITHHELD.keySet()) {
            String setter = "set" + Character.toUpperCase(withheld.charAt(0)) + withheld.substring(1) + "(null)";
            if (!proxyProfileOf.contains(setter)) {
                problems.add("proxyProfileOf no longer clears PatientProfileDTO." + withheld + " (`" + setter
                    + "`): " + PROFILE_WITHHELD.get(withheld));
            }
        }
        assertThat(summaryFields).as("the record's fields were found").contains("recentLabResults",
            "currentMedications", "recentVitals", "immunizations", "activeDiagnoses", "labResultTotal",
            "medicationTotal");
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    /** True when every collaborator call the expression reaches is a *ForProxy read or the filter, and one is. */
    private static boolean readsOnlyThroughAProxyRead(String expression, Map<String, String> methods,
                                                      Set<String> collaborators) {
        boolean filtered = false;
        for (Map.Entry<String, String> helper : closure("", expression, methods).entrySet()) {
            if (helper.getKey().endsWith("ForProxy")) {
                filtered = true;
                continue;
            }
            for (Call call : callsOn(helper.getValue(), collaborators)) {
                if (call.method().endsWith("ForProxy") || call.receiver().equals("proxyRecordFilter")) {
                    filtered = true;
                } else if (!NEUTRAL_CALLS.containsKey(call.receiver() + "." + call.method())) {
                    return false;
                }
            }
        }
        return filtered;
    }

    // ── Rule 6 ──────────────────────────────────────────────────────────────

    private static final String FILTER = "service/recordaccess/ProxyRecordFilter.java";
    private static final String POLICY = "service/recordaccess/ProxySensitivityPolicy.java";

    /** A proxy read context built or entered: what lets the filter apply an exception. */
    private static final Pattern CONTEXT_OPENED = Pattern.compile(
        "\\bnew\\s+(?:[\\w.]+\\.)?ProxyReadContext\\s*\\(|\\bproxyReadScope\\s*\\.\\s*enter\\s*\\(");
    /** The context consulted. */
    private static final Pattern CONTEXT_READ = Pattern.compile("\\bproxyReadScope\\s*\\.\\s*current\\s*\\(");

    @Test
    @DisplayName("rule 6: only the grant check opens a proxy read context, and only the filter consults it")
    void onlyTheGrantCheckSaysWhoIsReading() throws IOException {
        Set<String> opened = new TreeSet<>();
        Set<String> read = new TreeSet<>();
        for (Path file : sources("")) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            collect(code, rel(file), CONTEXT_OPENED, opened);
            collect(code, rel(file), CONTEXT_READ, read);
        }
        assertThat(opened).as("a proxy read context is what lets the filter show a tagged row (a tuberculosis scope,"
                + " a verified parent, a released result): only the grant check, which has just verified the grant,"
                + " may build or enter one" + SourceScan.MANDATE)
            .containsExactly(PORTAL_SERVICE + "#" + GRANT_CHECK);
        assertThat(read).as("only the filter consults the proxy read context" + SourceScan.MANDATE)
            .containsExactly(FILTER + "#readerOf");
        // Whatever a field is called: the holder itself is named by these three files only.
        Set<String> holders = new TreeSet<>();
        for (Path file : sources("")) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (Pattern.compile("\\bProxyReadScope\\b").matcher(code).find()) {
                holders.add(rel(file));
            }
        }
        assertThat(holders).as("only the grant check and the filter hold the proxy read scope" + SourceScan.MANDATE)
            .containsExactlyInAnyOrder(PORTAL_SERVICE, FILTER, "service/recordaccess/ProxyReadScope.java");
    }

    private static void collect(String code, String file, Pattern pattern, Set<String> into) {
        Matcher match = pattern.matcher(code);
        if (!match.find()) {
            return;
        }
        List<Member> members = membersOf(code);
        match.reset();
        while (match.find()) {
            int at = match.start();
            String owner = members.stream().filter(m -> m.holds(at)).map(Member::name).findFirst().orElse(null);
            into.add(owner == null ? file : file + "#" + owner);
        }
    }

    // ── Rule 7 ──────────────────────────────────────────────────────────────

    private static final Pattern RELEASE_TOKEN = Pattern.compile(
        "\\bProxySensitiveRelease(?:Repository)?\\b|\\bfindReleasedLabOrderIds\\b"
            + "|\\bexistsByProxyGrantIdAndLabOrderId\\b");

    /** Everything that may touch a release of a minor's result, each with why it shows a proxy nothing more. */
    private static final Map<String, String> RELEASE_USERS = Map.ofEntries(
        Map.entry("model/ProxySensitiveRelease.java", "the release entity itself"),
        Map.entry("repository/ProxySensitiveReleaseRepository.java", "the release repository itself"),
        Map.entry(RELEASE_SERVICE,
            "the clinician's release service; imports and injects the repository"),
        Map.entry(RELEASE_SERVICE + "#release",
            "writes a release after the checks (a minor, a verified parent or guardian, a clinician with care, a reason)"),
        Map.entry(RELEASE_SERVICE + "#overviewOf",
            "lists a patient's releases to the clinician, scoped by patient and hospital"),
        Map.entry(FILTER, "imports and injects the repository for the use below"),
        Map.entry(FILTER + "#ProxyRecordFilter", "the constructor: receives the repository"),
        Map.entry(FILTER + "#releasedToReader",
            "the one read a release widens: the orders released to the grant of this read, honoured by the policy"));

    @Test
    @DisplayName("rule 7: only listed code touches a release of a minor's result, each with its reason")
    void onlyListedCodeTouchesARelease() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path file : sources("")) {
            collect(SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)), rel(file),
                RELEASE_TOKEN, found);
        }
        List<String> problems = new ArrayList<>();
        for (String use : found) {
            if (!RELEASE_USERS.containsKey(use)) {
                problems.add(use + " touches a release of a minor's result (ProxySensitiveRelease or its repository)"
                    + " and is not on the allow-list: a release is written by MinorResultReleaseServiceImpl and"
                    + " honoured by ProxyRecordFilter only; list it in RELEASE_USERS with why it shows a proxy"
                    + " nothing more");
            }
        }
        for (String listed : RELEASE_USERS.keySet()) {
            if (!found.contains(listed)) {
                problems.add(listed + " is on the release allow-list and no longer touches a release: remove the entry");
            }
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    // ── Rule 8 ──────────────────────────────────────────────────────────────

    private static final Path PORTAL = Path.of("..", "hospital-portal", "src");
    private static final Pattern ENUM_CONSTANT = Pattern.compile("(?m)^\\s{4}([A-Z][A-Z0-9_]*)\\s*\\(");

    @Test
    @DisplayName("rule 8: every sensitivity category has a rule for a minor, and a label in the portal")
    void everyCategoryIsClassifiedAndLabelled() throws IOException {
        String enumSource = SourceScan.stripComments(Files.readString(
            ROOT.resolve("enums/SensitivityCategory.java"), StandardCharsets.UTF_8));
        Set<String> categories = new TreeSet<>();
        int enumBody = enumSource.indexOf('{');
        Matcher constant = ENUM_CONSTANT.matcher(enumSource.substring(enumBody, enumSource.indexOf(';', enumBody)));
        while (constant.find()) {
            categories.add(constant.group(1));
        }
        assertThat(categories).as("the categories were found").contains("HIV", "STI", "VIRAL_HEPATITIS", "PREGNANCY",
            "REPRODUCTIVE_HEALTH", "SEXUAL_DOMESTIC_VIOLENCE", "BEHAVIOURAL_HEALTH", "SUBSTANCE_USE", "GENETIC",
            "SICKLE_CELL", "TUBERCULOSIS");

        String policy = SourceScan.stripComments(Files.readString(ROOT.resolve(POLICY), StandardCharsets.UTF_8));
        String portalList = Files.readString(
            PORTAL.resolve("app/shared/sensitivity/sensitivity-category.ts"), StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        for (String category : categories) {
            if (!Pattern.compile("MINOR_RULES\\.put\\(\\s*SensitivityCategory\\." + category + "\\s*,").matcher(policy)
                    .find()) {
                problems.add("SensitivityCategory." + category + " has no rule for a minor in ProxySensitivityPolicy"
                    + ".MINOR_RULES: decide what a verified parent reads of it (RELEASE_ONLY, PARENT_VISIBLE or"
                    + " BY_ADOLESCENT_AGE)");
            }
            if (!portalList.contains("'" + category + "'")) {
                problems.add("SensitivityCategory." + category + " is missing from the portal's list"
                    + " (shared/sensitivity/sensitivity-category.ts): no editor could set it");
            }
            for (String locale : List.of("fr", "en", "es")) {
                String bundle = Files.readString(PORTAL.resolve("assets/i18n/" + locale + ".json"),
                    StandardCharsets.UTF_8);
                if (!bundle.contains("\"CATEGORY_" + category + "\"")) {
                    problems.add("SensitivityCategory." + category + " has no label SENSITIVITY_TAG.CATEGORY_"
                        + category + " in " + locale + ".json");
                }
            }
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    // ── Rule 9 ──────────────────────────────────────────────────────────────

    /**
     * What surrounds a hidden item and would name it (Loi 030-2008 art. 14:
     * the financial and administrative information too): "filter method" to
     * the call its body must keep, and why.
     */
    private static final Map<String, List<String>> SURROUNDING_RECORDS = Map.of(
        "testCategory", List.of("componentCategory(",
            "a combined panel is withheld whole as soon as one component is a tagged test"),
        "medicineCategory", List.of("medicationCatalogItemRepository",
            "a prescription of a tagged medicine (an antiretroviral) is withheld whatever visit wrote it"),
        "billsNoHiddenOrder", List.of("admits(order, cache)",
            "an invoice is withheld when its visit ordered a test the proxy may not read"),
        "billsNoHiddenTest", List.of("admitsPricedFrom(",
            "an invoice is withheld when a line is priced from a tagged test"),
        "admitsPricedFrom", List.of("testCategory(",
            "the price-list item of a line is judged by the test its price is linked to"),
        "releasedToReader", List.of("ProxySensitivityPolicy.honoursReleases(",
            "a release counts only for a verified parent of a patient under 18"));

    /** What the review of #1242 added to the same methods: "filter method" to the call it must also keep. */
    private static final Map<String, List<String>> SURROUNDING_RECORDS_REVIEWED = Map.of(
        "billsNoHiddenTest", List.of("findUnscopedByHospitalIdAndIdIn(",
            "the items of an invoice are read at the invoice's hospital, outside the caller's tenant scope"),
        "medicineCategory", List.of("unlinkedMedicineCategory(",
            "an order with no catalogue link is still compared with the tagged medicines"),
        "unlinkedMedicineCategory", List.of("namesCatalogItem(",
            "an unlinked order is matched by its typed name (and its code) against every tagged medicine"),
        "testCategory", List.of("orderedAt",
            "a platform panel is judged by the tagged tests of the hospital that ordered it"),
        "familyHidingOf", List.of("ProxySensitivityPolicy.hiddenFromVerifiedParent(",
            "the clinician's list holds only what a verified parent is actually refused"));

    /** The entity overloads of {@code admits} that must reach the checks above. */
    private static final Map<String, String> SURROUNDING_CALLERS = Map.of(
        "ProxySensitivityPolicy.releasable(", "admits(LabOrder) honours a release of an HIV result only",
        "billsNoHiddenOrder(", "admits(BillingInvoice) judges the tests its visit ordered",
        "billsNoHiddenTest(", "admits(BillingInvoice) judges the tests its lines are priced from",
        "medicineCategory(", "admits(Prescription) judges the medicine",
        "releasedToReader(", "admits(LabOrder) honours a release");

    @Test
    @DisplayName("rule 9: the filter judges a panel, a medicine and an invoice by what they reveal, and excepts through the policy only")
    void theFilterJudgesTheSurroundingRecords() throws IOException {
        String code = SourceScan.stripComments(Files.readString(ROOT.resolve(FILTER), StandardCharsets.UTF_8));
        Map<String, String> methods = methodsOf(code);
        List<String> problems = new ArrayList<>();
        SURROUNDING_RECORDS.forEach((method, rule) -> {
            String body = methods.get(method);
            if (body == null || !body.contains(rule.get(0))) {
                problems.add("ProxyRecordFilter#" + method + " no longer holds `" + rule.get(0) + "`: " + rule.get(1));
            }
        });
        SURROUNDING_RECORDS_REVIEWED.forEach((method, rule) -> {
            String body = methods.get(method);
            if (body == null || !body.contains(rule.get(0))) {
                problems.add("ProxyRecordFilter#" + method + " no longer holds `" + rule.get(0) + "`: " + rule.get(1));
            }
        });
        String admits = methods.get("admits");
        assertThat(admits).as("the filter's admits overloads were found").isNotNull();
        SURROUNDING_CALLERS.forEach((call, why) -> {
            if (!admits.contains(call)) {
                problems.add("no admits overload of ProxyRecordFilter calls `" + call + "` any more: " + why);
            }
        });
        // An exception for a tagged row is the policy's to make: one call, in the rule for a known patient.
        Matcher policyCall = Pattern.compile("ProxySensitivityPolicy\\.admits\\(").matcher(code);
        int calls = 0;
        while (policyCall.find()) {
            calls++;
        }
        assertThat(calls).as("ProxyRecordFilter asks ProxySensitivityPolicy.admits in one place").isEqualTo(1);
        assertThat(methods.get("readerOf")).as("an exception applies only to a row of the patient the grant names")
            .contains("grantorPatientId()");
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    // ── Rule 10 ─────────────────────────────────────────────────────────────

    private static final Pattern REPOSITORY_FIELD = Pattern.compile(
        "private\\s+(?:final\\s+)?(?:[\\w.]+\\.)?(\\w+Repository)\\s+(\\w+)\\s*;");
    private static final Pattern REPOSITORY_ENTITY = Pattern.compile("extends\\s+\\w*Repository<\\s*(\\w+)\\s*,");
    /** A class declaration up to its body: the name, then whatever it extends and implements. */
    private static final Pattern CLASS_HEADER = Pattern.compile("class\\s+\\w+([^{]*+)\\{");
    private static final Pattern TENANT_SCOPED = Pattern.compile("\\bTenantScoped\\b");
    /** The inherited finders {@code TenantAwareJpaRepository} narrows to the caller's hospitals. */
    private static final String SCOPED_FINDERS =
        "findById|findAllById|findAll|findOne|existsById|getReferenceById|getById|count|exists";

    /**
     * Scoped finders a proxy read calls on a tenant-scoped entity on purpose:
     * "file#method -> field.finder" to why the caller's scope is the right one.
     */
    private static final Map<String, String> SCOPED_ON_PURPOSE = Map.of();

    @Test
    @DisplayName("rule 10: no proxy read judges a tenant-scoped row through a finder narrowed to the caller's hospitals")
    void noProxyReadGoesThroughTheCallersTenantScope() throws IOException {
        Map<String, Boolean> tenantScopedByRepository = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Set<String> scopedFieldsOfTheFilter = new TreeSet<>();
        Set<String> used = new TreeSet<>();
        for (Path file : sources("")) {
            String rel = rel(file);
            if (rel.startsWith("repository/") || rel.startsWith("controller/")) {
                continue;
            }
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            boolean filter = rel.equals(FILTER);
            if (!filter && !code.contains("ForProxy")) {
                continue;
            }
            Map<String, String> scopedFields = new LinkedHashMap<>();
            Matcher field = REPOSITORY_FIELD.matcher(code);
            while (field.find()) {
                if (tenantScopedByRepository.computeIfAbsent(field.group(1),
                        ProxyReadSensitivityGuardTest::repositoryOfATenantScopedEntity)) {
                    scopedFields.put(field.group(2), field.group(1));
                }
            }
            if (filter) {
                scopedFieldsOfTheFilter.addAll(scopedFields.keySet());
            }
            for (Member member : membersOf(code)) {
                // The filter is a proxy read from end to end; elsewhere, the *ForProxy reads.
                if (!filter && !member.name().endsWith("ForProxy")) {
                    continue;
                }
                for (String name : scopedFields.keySet()) {
                    Matcher call = Pattern.compile("\\b" + name + "\\s*\\.\\s*(" + SCOPED_FINDERS + ")\\s*\\(")
                        .matcher(member.body());
                    while (call.find()) {
                        String key = rel + "#" + member.name() + " -> " + name + "." + call.group(1);
                        if (SCOPED_ON_PURPOSE.containsKey(key)) {
                            used.add(key);
                        } else {
                            problems.add(key + ": " + scopedFields.get(name) + " holds a tenant-scoped entity, and"
                                + " this finder is narrowed to the CALLER's hospitals. A proxy holds no assignment"
                                + " where the grantor was treated, so it loads nothing and the row is shown unjudged"
                                + " (QA D1212, review of #1242). Read by an explicit @Query that names the ROW's"
                                + " hospital, and withhold what cannot be loaded");
                        }
                    }
                }
            }
        }
        assertThat(scopedFieldsOfTheFilter).as("the scan recognises the filter's tenant-scoped repository")
            .contains("priceListItemRepository");
        for (String listed : SCOPED_ON_PURPOSE.keySet()) {
            if (!used.contains(listed)) {
                problems.add(listed + " is listed in SCOPED_ON_PURPOSE and no longer made: remove the entry");
            }
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    /** Whether the entity a repository type holds implements TenantScoped (the tenant filter narrows its finders). */
    private static boolean repositoryOfATenantScopedEntity(String repositoryType) {
        try {
            Path repository = fileNamed("repository", repositoryType);
            if (repository == null) {
                return false;
            }
            Matcher entity = REPOSITORY_ENTITY.matcher(
                SourceScan.stripComments(Files.readString(repository, StandardCharsets.UTF_8)));
            if (!entity.find()) {
                return false;
            }
            Path model = fileNamed("model", entity.group(1));
            if (model == null) {
                return false;
            }
            Matcher header = CLASS_HEADER.matcher(
                SourceScan.stripComments(Files.readString(model, StandardCharsets.UTF_8)));
            if (!header.find()) {
                return false;
            }
            int implemented = header.group(1).indexOf("implements");
            return implemented >= 0 && TENANT_SCOPED.matcher(header.group(1).substring(implemented)).find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path fileNamed(String dir, String type) throws IOException {
        for (Path file : sources(dir)) {
            if (file.getFileName().toString().equals(type + ".java")) {
                return file;
            }
        }
        return null;
    }

    // ── Rule 11 ─────────────────────────────────────────────────────────────

    /** What each write of the release service must keep: "method" to the checks, each with why. */
    private static final Map<String, Map<String, String>> RELEASE_CHECKS = Map.of(
        "release", Map.of(
            "requireActingHospital(", "a release is a clinical act at one hospital",
            "requirePatientInCare(", "the clinician has care of the patient (chart gate and treatment relationship)",
            "requireMinor(", "only a patient under 18: an adult's result is the adult's",
            "requireGuardianGrant(", "the grant is this patient's, live, a parent's or a guardian's",
            "refuseOwnGrant(", "nobody releases a result to themselves",
            "relationshipVerified", "an unverified grant needs the clinician's attestation",
            "releasable()", "an HIV result only; never one that violence is on",
            "isReleased", "only a result the laboratory released"),
        "verifyGuardian", Map.of(
            "requireActingHospital(", "a verification is made at one hospital",
            "requirePatientInCare(", "the clinician has care of the patient",
            "requireMinor(", "only for a patient under 18",
            "requireGuardianGrant(", "the grant is this patient's, live, a parent's or a guardian's",
            "refuseOwnGrant(", "nobody verifies their own relationship"));

    @Test
    @DisplayName("rule 11: the release service keeps every check of a release and of a verification")
    void theReleaseServiceKeepsItsChecks() throws IOException {
        Map<String, String> methods = methodsOf(SourceScan.stripComments(
            Files.readString(ROOT.resolve(RELEASE_SERVICE), StandardCharsets.UTF_8)));
        List<String> problems = new ArrayList<>();
        RELEASE_CHECKS.forEach((method, checks) -> {
            String body = methods.get(method);
            assertThat(body).as("MinorResultReleaseServiceImpl#" + method + " exists").isNotNull();
            checks.forEach((token, why) -> {
                if (!body.contains(token)) {
                    problems.add("MinorResultReleaseServiceImpl#" + method + " no longer holds `" + token + "`: " + why);
                }
            });
        });
        String policy = SourceScan.stripComments(Files.readString(ROOT.resolve(POLICY), StandardCharsets.UTF_8));
        String releasable = methodsOf(policy).get("releasable");
        assertThat(releasable).as("ProxySensitivityPolicy.releasable exists").isNotNull();
        if (!releasable.contains("MinorRule.NEVER") || !releasable.contains("MinorRule.RELEASE_ONLY")) {
            problems.add("ProxySensitivityPolicy.releasable no longer refuses a NEVER category and requires a"
                + " RELEASE_ONLY one: only an HIV result is released, never one that violence is on");
        }
        if (!Pattern.compile("MINOR_RULES\\.put\\(\\s*SensitivityCategory\\.SEXUAL_DOMESTIC_VIOLENCE\\s*,\\s*MinorRule\\.NEVER")
                .matcher(policy).find()) {
            problems.add("SEXUAL_DOMESTIC_VIOLENCE is no longer NEVER in ProxySensitivityPolicy.MINOR_RULES: a"
                + " violence record goes to authorities only (Code pénal art. 525-2), never to a parent");
        }
        Matcher releaseOnly = Pattern.compile("MINOR_RULES\\.put\\(\\s*SensitivityCategory\\.(\\w+)\\s*,\\s*MinorRule\\.RELEASE_ONLY")
            .matcher(policy);
        Set<String> releasableCategories = new TreeSet<>();
        while (releaseOnly.find()) {
            releasableCategories.add(releaseOnly.group(1));
        }
        assertThat(releasableCategories).as("owner decision 2026-11-21: the release covers HIV only (Loi 030-2008"
            + " art. 17-18); another releasable category is a new decision, not a code change").containsExactly("HIV");
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    // ── Reading the source ──────────────────────────────────────────────────

    private record Member(String name, int declaredAt, int bodyStart, int bodyEnd, String body) {
        boolean holds(int index) {
            return index >= declaredAt && index <= bodyEnd;
        }
    }

    private record Call(String receiver, String method, String arguments) {
    }

    private static String portalService() throws IOException {
        return SourceScan.stripComments(Files.readString(ROOT.resolve(PORTAL_SERVICE), StandardCharsets.UTF_8));
    }

    /** The portal service's methods that check a grant, by name (overloads joined). */
    private static Map<String, String> grantCheckedMethods() throws IOException {
        Map<String, String> checked = new LinkedHashMap<>();
        methodsOf(portalService()).forEach((name, body) -> {
            if (!name.equals(GRANT_CHECK) && Pattern.compile("\\b" + GRANT_CHECK + "\\s*\\(").matcher(body).find()) {
                checked.put(name, body);
            }
        });
        return checked;
    }

    private static List<Member> membersOf(String code) {
        List<Member> members = new ArrayList<>();
        Matcher member = MEMBER.matcher(code);
        while (member.find()) {
            String name = declaredName(member.group(1));
            if (name == null) {
                continue;
            }
            int open = member.end() - 1;
            int close = SourceScan.closingParen(code, open);
            int brace = SourceScan.indexOfOutsideLiterals(code, '{', close);
            int semicolon = SourceScan.indexOfOutsideLiterals(code, ';', close);
            if (brace < 0 || (semicolon >= 0 && semicolon < brace)) {
                continue;
            }
            int end = SourceScan.closingBrace(code, brace);
            members.add(new Member(name, member.start(), brace, end, code.substring(brace, end + 1)));
        }
        return members;
    }

    private static Map<String, String> methodsOf(String code) {
        Map<String, String> methods = new LinkedHashMap<>();
        membersOf(code).forEach(member -> methods.merge(member.name(), member.body(), String::concat));
        return methods;
    }

    private static Set<String> fieldsOf(String code) {
        Set<String> fields = new LinkedHashSet<>();
        Matcher field = FIELD.matcher(code);
        while (field.find()) {
            fields.add(field.group(1));
        }
        return fields;
    }

    /** Every call, and every method reference, the text makes on one of the collaborators. */
    private static List<Call> callsOn(String text, Set<String> collaborators) {
        List<Call> calls = new ArrayList<>();
        Matcher call = CALL.matcher(text);
        while (call.find()) {
            if (!collaborators.contains(call.group(1))) {
                continue;
            }
            String arguments = "";
            if (call.group(3) != null) {
                int open = call.end() - 1;
                arguments = text.substring(open, SourceScan.closingParen(text, open) + 1);
            }
            calls.add(new Call(call.group(1), call.group(2), arguments));
        }
        return calls;
    }

    /** {@code start} and every helper of the same class it reaches, by name; the grant check itself left out. */
    private static Map<String, String> closure(String startName, String startBody, Map<String, String> methods) {
        Map<String, String> reached = new LinkedHashMap<>();
        reached.put(startName, startBody);
        List<String> queue = new ArrayList<>(List.of(startBody));
        while (!queue.isEmpty()) {
            Matcher local = LOCAL_CALL.matcher(queue.remove(0));
            while (local.find()) {
                String name = local.group(1);
                if (name.equals(GRANT_CHECK) || reached.containsKey(name) || !methods.containsKey(name)) {
                    continue;
                }
                reached.put(name, methods.get(name));
                if (!name.endsWith("ForProxy")) {
                    // A proxy read of the same class is rule 4's: its own reads are the filtered ones.
                    queue.add(methods.get(name));
                }
            }
        }
        return reached;
    }

    /** The {@code .field(expression)} calls chained on a builder, by field. */
    private static Map<String, String> builderCalls(String body, String builder) {
        Map<String, String> calls = new LinkedHashMap<>();
        assertThat(body).as("getProxyRecords exists").isNotNull();
        int at = body.indexOf(builder);
        assertThat(at).as("getProxyRecords builds a " + builder).isGreaterThanOrEqualTo(0);
        at += builder.length();
        Pattern link = Pattern.compile("\\G\\s*\\.\\s*(\\w+)\\s*\\(");
        Matcher next = link.matcher(body);
        while (next.find(at)) {
            int open = next.end() - 1;
            int close = SourceScan.closingParen(body, open);
            if (next.group(1).equals("build")) {
                break;
            }
            calls.put(next.group(1), body.substring(open + 1, close));
            at = close + 1;
        }
        return calls;
    }

    private static Set<String> dtoFields(String relativePath) throws IOException {
        String code = SourceScan.stripComments(Files.readString(ROOT.resolve(relativePath), StandardCharsets.UTF_8));
        int classAt = classDeclaration(code);
        int open = code.indexOf('{', classAt);
        // The top-level class only: a nested class's fields are its own.
        String top = code.substring(open, SourceScan.closingBrace(code, open) + 1);
        Set<String> fields = new LinkedHashSet<>();
        Matcher field = DTO_FIELD.matcher(top);
        while (field.find()) {
            fields.add(field.group(1));
        }
        return fields;
    }

    private static int classDeclaration(String code) {
        Matcher declaration = Pattern.compile("\\n(?:public |final |abstract )*(?:class|interface|record)\\s+\\w+")
            .matcher(code);
        return declaration.find() ? declaration.start() : -1;
    }

    /**
     * The paths a mapping annotation names: its string literals, positional,
     * {@code value =}, {@code path =} or an array, with a same-file String
     * constant read as its literal. A path the guard cannot read is a
     * problem: it could be a proxy endpoint.
     */
    private static List<String> pathsOf(String code, int afterName, Map<String, String> constants, String file,
                                        List<String> problems) {
        int open = afterName;
        while (open < code.length() && Character.isWhitespace(code.charAt(open))) {
            open++;
        }
        if (open >= code.length() || code.charAt(open) != '(') {
            return List.of("");
        }
        String arguments = code.substring(open + 1, SourceScan.closingParen(code, open));
        for (Map.Entry<String, String> constant : constants.entrySet()) {
            arguments = arguments.replaceAll("\\b" + Pattern.quote(constant.getKey()) + "\\b",
                Matcher.quoteReplacement(constant.getValue()));
        }
        // "a" + "b" is one path.
        arguments = arguments.replaceAll("\"\\s*\\+\\s*\"", "");
        List<String> paths = new ArrayList<>();
        Matcher literal = LITERAL.matcher(arguments);
        while (literal.find()) {
            paths.add(literal.group(1));
        }
        String rest = MAPPING_NOISE.matcher(LITERAL.matcher(arguments).replaceAll("")).replaceAll("");
        if (Pattern.compile("[A-Za-z_]").matcher(rest).find() && !UNREADABLE_MAPPINGS.containsKey(file)) {
            problems.add(file + " maps an endpoint with `" + arguments.strip() + "`, which this guard cannot read as"
                + " a path: write the path as a literal or a String constant of the same class, so a proxy"
                + " endpoint cannot hide behind it");
        }
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static List<Path> sources(String dir) throws IOException {
        try (Stream<Path> files = Files.walk(ROOT.resolve(dir))) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String rel(Path file) {
        return ROOT.relativize(file).toString().replace('\\', '/');
    }
}
