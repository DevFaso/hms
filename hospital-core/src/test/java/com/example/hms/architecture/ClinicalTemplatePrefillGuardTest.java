package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D1525 (round 38) class guard: a finding is charted only when the
 * clinician states it. The sibling of CR-06 (an alert never records a
 * treatment as performed).
 *
 * <p>The ultrasound templates pre-filled cardiac activity, movement and tone
 * as present and the category as NORMAL; the entity defaulted the three flags
 * to {@code true} and the mapper turned a missing flag into {@code true}. An
 * untouched template was charted as a normal scan nobody described. This guard
 * reads the whole tree for the three shapes of that defect:
 * <ol>
 *   <li>a template endpoint that returns a pre-filled clinical request (none remain);</li>
 *   <li>an entity field initialised to {@code true} or to a "normal"-type enum
 *       constant (a stated finding on every row built without it);</li>
 *   <li>a write that turns a missing Boolean into {@code true}
 *       ({@code dto.getX() == null || dto.getX()});</li>
 *   <li>the negative mirror: an entity field named like a negative finding
 *       (anomaly, bleeding, concern, ...) defaulted to {@code false}, and a write
 *       {@code .x(Boolean.TRUE.equals(dto.getX()))} of one.</li>
 * </ol>
 * Bookkeeping flags (active, enabled, ...) are allowed by name. The other
 * allowances are the sweep's known debts, each with its reason; they may only
 * shrink, and a listed site that disappears fails as stale.
 */
class ClinicalTemplatePrefillGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** Record-keeping flags, not findings: true by default is fine. */
    private static final Set<String> BOOKKEEPING_FLAGS = Set.of(
            "active", "isActive", "enabled", "current", "published", "managedByPlatform",
            "latestVersion", "providerReviewRequired",
            // catalogue attributes of an education resource, not a patient
            "isEvidenceBased", "isCulturallySensitive",
            // a referral's care arrangement, not an observation
            "ongoingMidwiferyCare");

    /**
     * Known debts found by the D1525 sweep, "File.java#field" -> reason. Each
     * one states something about a patient that nobody may have said; they are
     * listed for follow-up, not endorsed. Delete the line when the site is fixed.
     */
    private static final Map<String, String> ENTITY_TRUE_DEBT = Map.of(
            "DeliveryRecord.java#liveBirth",
            "D1525 sweep: a delivery is a live birth unless said otherwise (primitive column; needs a migration and a clinician decision)",
            "PatientConsent.java#consentGiven",
            "D1525 sweep: a consent row is 'given' by default",
            "VisitEducationDocumentation.java#patientEngaged",
            "D1525 sweep: education documentation asserts the patient was engaged",
            "VisitEducationDocumentation.java#patientUnderstood",
            "D1525 sweep: education documentation asserts the patient understood",
            "MaternalHistory.java#adequateHousing",
            "D1525 sweep: the unbooked-labour path stores adequate housing nobody asked about",
            "MaternalHistory.java#foodSecurity",
            "D1525 sweep: the unbooked-labour path stores food security nobody asked about",
            "HighRiskMedicationLog.java#taken",
            "latent only: the request's taken is @NotNull and always copied");

    /** Enum constants that state a normal/benign finding when used as a default. */
    private static final Set<String> FINDING_CONSTANTS = Set.of(
            "NORMAL", "NATURAL", "NEGATIVE", "COMPATIBLE", "UNREMARKABLE", "PRESENT");

    private static final Map<String, String> ENTITY_ENUM_ALLOWED = Map.of(
            "InBasketItem.java#priority", "a message priority, not a patient finding",
            "DeathRecord.java#mannerOfDeath",
            "D1525 sweep debt: manner of death defaults to NATURAL (entity, service and V135 column default; needs a migration)");

    /** "File.java#getter" -> reason, for {@code x.getY() == null || x.getY()}. */
    private static final Map<String, String> NULL_AS_TRUE_DEBT = Map.of(
            "LaborServiceImpl.java#getLiveBirth",
            "D1525 sweep debt: a delivery without the field is recorded as a live birth");

    private static final Set<String> NULL_AS_TRUE_BOOKKEEPING = Set.of("getActive", "getEnabled");

    private static final Pattern TRUE_FIELD = Pattern.compile(
            "private\\s++(?:Boolean|boolean)\\s++(\\w++)\\s*+=\\s*+(?:true|Boolean\\.TRUE)\\s*+;");
    private static final Pattern ENUM_FIELD = Pattern.compile(
            "private\\s++(\\w++)\\s++(\\w++)\\s*+=\\s*+\\1\\.([A-Z_]++)\\s*+;");
    private static final Pattern NULL_AS_TRUE = Pattern.compile(
            "(\\w++)\\.((?:get|is)\\w++)\\(\\)\\s*+==\\s*+null\\s*+\\|\\|\\s*+\\1\\.\\2\\(\\)(?=\\s*+[),;?:])");
    /**
     * Any public method: its return type and name are judged in code
     * ({@link #templateMethodsIn}), so the pattern stays linear (Sonar S8786)
     * and cannot go dead the way a possessive {@code \\w*+RequestDTO} did.
     */
    private static final Pattern PUBLIC_METHOD = Pattern.compile(
            "public\\s++(?:static\\s++)?([A-Z]\\w*+)\\s++(\\w++)\\s*+\\(");

    /**
     * QA D1525: the ultrasound templates were removed (without the findings they
     * pre-filled they carried only today's date, which overwrote a stored scan
     * date). No method may return a pre-filled clinical request again; one that
     * truly suggests only structure must be added here with a test that it
     * states no finding.
     */
    private static final Set<String> KNOWN_TEMPLATES = Set.of();

    /**
     * A negatively phrased finding: defaulted to {@code false}, it states "no
     * anomaly / no bleeding / no concern" that nobody may have said (the D1525
     * sibling of a {@code true} default: UltrasoundReport.anomaliesDetected).
     */
    private static final Pattern NEGATIVE_FINDING_NAME = Pattern.compile(
            "(?i)anomal|abnormal|absent|complication|adverse|distress|concern|critical|bleeding|infection"
                    + "|positive|exposure|violence|suspect");

    private static final Pattern FALSE_FIELD = Pattern.compile(
            "private\\s++(?:Boolean|boolean)\\s++(\\w++)\\s*+=\\s*+(?:false|Boolean\\.FALSE)\\s*+;");

    /** {@code .x(Boolean.TRUE.equals(dto.getX()))}: a missing answer written as "no". */
    private static final Pattern NULL_AS_FALSE_WRITE = Pattern.compile(
            "\\.(\\w++)\\(\\s*+Boolean\\.TRUE\\.equals\\(\\w++\\.get(\\w++)\\(\\)\\)\\s*+\\)");

    /** "File.java#field" -> reason: negatively phrased findings defaulted to false today. Only shrinks. */
    private static final Map<String, String> ENTITY_FALSE_DEBT = Map.of(
            "MaternalHistory.java#fetalAnomalyHistory", "D1525 sweep debt: maternal history answers default to 'No'",
            "MaternalHistory.java#familyPregnancyComplications", "D1525 sweep debt: maternal history answers default to 'No'",
            "MaternalHistory.java#zikaRiskExposure", "D1525 sweep debt: maternal history answers default to 'No'",
            "MaternalHistory.java#domesticViolenceConcerns", "D1525 sweep debt: maternal history answers default to 'No'",
            "MaternalHistory.java#financialConcerns", "D1525 sweep debt: maternal history answers default to 'No'",
            "MtmReview.java#adherenceConcern", "D1525 sweep debt: primitive column, needs a migration",
            "PostpartumObservation.java#excessiveBleeding", "D1525 sweep debt: primitive column, needs a migration and a clinician decision",
            "ProResponse.java#criticalItemPositive", "computed by the scoring service from the patient's answers, not entered",
            "MaternalHistory.java#domesticViolenceScreening", "whether a screening was done (an action), not a finding",
            "PostpartumObservation.java#uterineAtonySuspected", "D1525 sweep debt: primitive column, needs a migration and a clinician decision");

    /** "File.java#getter" -> reason: {@code Boolean.TRUE.equals(dto.getX())} writes of a negative finding. Only shrinks. */
    private static final Map<String, String> NULL_AS_FALSE_DEBT = Map.of(
            "PostpartumCareServiceImpl.java#ExcessiveBleeding", "D1525 sweep debt: primitive column (excessiveBleeding)",
            "PostpartumCareServiceImpl.java#UterineAtonySuspected", "D1525 sweep debt: postpartum observation answers",
            "MtmReviewServiceImpl.java#AdherenceConcern", "D1525 sweep debt: primitive column (adherenceConcern)");

    @Test
    void noMethodReturnsAPrefilledClinicalRequest() {
        Set<String> found = new TreeSet<>();
        for (Path f : javaFiles(MAIN)) {
            for (String method : templateMethodsIn(read(f))) {
                found.add(f.getFileName() + "#" + method);
            }
        }
        assertThat(found)
                .as("a template endpoint returning a pre-filled clinical request (QA D1525)")
                .isEqualTo(new TreeSet<>(KNOWN_TEMPLATES));
    }

    /** The public methods of {@code source} that return a {@code *RequestDTO} and are named like a template. */
    static List<String> templateMethodsIn(String source) {
        List<String> methods = new java.util.ArrayList<>();
        Matcher m = PUBLIC_METHOD.matcher(source);
        while (m.find()) {
            if (m.group(1).endsWith("RequestDTO") && m.group(2).toLowerCase(java.util.Locale.ROOT).contains("template")) {
                methods.add(m.group(2));
            }
        }
        return methods;
    }

    /**
     * Each scan must still catch the shape it exists for (QA D1525 review: a
     * possessive quantifier once made the template scan match nothing).
     */
    @Test
    void everyScanCatchesItsSampleSite() {
        assertThat(templateMethodsIn("""
                    @Override
                    @Transactional(readOnly = true)
                    public UltrasoundReportRequestDTO getNuchalTranslucencyTemplate() {
                    public UltrasoundReportResponseDTO getReportById(UUID id) {
                    public static ImagingReportRequestDTO anatomyTemplate(int weeks) {
                """)).containsExactly("getNuchalTranslucencyTemplate", "anatomyTemplate");
        assertThat(TRUE_FIELD.matcher("    private Boolean fetalCardiacActivity = true;").find()).isTrue();
        assertThat(TRUE_FIELD.matcher("    private boolean liveBirth = Boolean.TRUE;").find()).isTrue();
        assertThat(ENUM_FIELD.matcher(
                "    private UltrasoundFindingCategory findingCategory = UltrasoundFindingCategory.NORMAL;").find()).isTrue();
        assertThat(NULL_AS_TRUE.matcher(".fetalToneNormal(dto.getFetalToneNormal() == null || dto.getFetalToneNormal())")
                .find()).isTrue();
        assertThat(NULL_AS_TRUE.matcher("if (r.getVolumeMl() == null || r.getVolumeMl() <= 0) {").find()).isFalse();
        Matcher f = FALSE_FIELD.matcher("    private Boolean anomaliesDetected = false;");
        assertThat(f.find() && NEGATIVE_FINDING_NAME.matcher(f.group(1)).find()).isTrue();
        Matcher w = NULL_AS_FALSE_WRITE.matcher(".anomaliesDetected(Boolean.TRUE.equals(dto.getAnomaliesDetected()))");
        assertThat(w.find()).isTrue();
        assertThat(w.group(1)).isEqualTo("anomaliesDetected");
        assertThat(w.group(2)).isEqualTo("AnomaliesDetected");
        Matcher set = NULL_AS_FALSE_WRITE.matcher("report.setExcessiveBleeding(Boolean.TRUE.equals(request.getExcessiveBleeding()));");
        assertThat(set.find()).isTrue();
        assertThat(set.group(1)).isEqualTo("setExcessiveBleeding");
    }

    @Test
    void noEntityDefaultsANegativeFindingToFalse() {
        Set<String> found = new TreeSet<>();
        for (Path f : javaFiles(MAIN.resolve("model"))) {
            Matcher m = FALSE_FIELD.matcher(read(f));
            while (m.find()) {
                if (NEGATIVE_FINDING_NAME.matcher(m.group(1)).find() && !m.group(1).startsWith("isHighRisk")) {
                    found.add(f.getFileName() + "#" + m.group(1));
                }
            }
        }
        assertThat(found).as("a negatively phrased finding defaulted to false states 'no ...' nobody said (QA D1525)")
                .isSubsetOf(ENTITY_FALSE_DEBT.keySet());
        assertThat(ENTITY_FALSE_DEBT.keySet()).as("a listed debt was fixed: delete its line").isSubsetOf(found);
    }

    @Test
    void noWriteTurnsAMissingNegativeFindingIntoFalse() {
        Set<String> found = new TreeSet<>();
        for (Path f : javaFiles(MAIN)) {
            Matcher m = NULL_AS_FALSE_WRITE.matcher(read(f));
            while (m.find()) {
                if ((m.group(1).equalsIgnoreCase(m.group(2)) || m.group(1).equalsIgnoreCase("set" + m.group(2))) && NEGATIVE_FINDING_NAME.matcher(m.group(2)).find()) {
                    found.add(f.getFileName() + "#" + m.group(2));
                }
            }
        }
        assertThat(found).as("a missing negative finding written as false (QA D1525): keep null as 'not stated'")
                .isSubsetOf(NULL_AS_FALSE_DEBT.keySet());
        assertThat(NULL_AS_FALSE_DEBT.keySet()).as("a listed debt was fixed: delete its line").isSubsetOf(found);
    }

    @Test
    void noEntityDefaultsAFindingToTrue() {
        Set<String> found = new TreeSet<>();
        for (Path f : javaFiles(MAIN.resolve("model"))) {
            Matcher m = TRUE_FIELD.matcher(read(f));
            while (m.find()) {
                if (!BOOKKEEPING_FLAGS.contains(m.group(1))) {
                    found.add(f.getFileName() + "#" + m.group(1));
                }
            }
        }
        assertThat(found).as("an entity field that states a finding by default (QA D1525): leave it null, "
                + "or list it with a reason if it is bookkeeping").isSubsetOf(ENTITY_TRUE_DEBT.keySet());
        assertThat(ENTITY_TRUE_DEBT.keySet()).as("a listed debt was fixed: delete its line").isSubsetOf(found);
    }

    @Test
    void noEntityDefaultsAFindingToANormalConstant() {
        Set<String> found = new TreeSet<>();
        for (Path f : javaFiles(MAIN.resolve("model"))) {
            Matcher m = ENUM_FIELD.matcher(read(f));
            while (m.find()) {
                if (FINDING_CONSTANTS.contains(m.group(3))) {
                    found.add(f.getFileName() + "#" + m.group(2));
                }
            }
        }
        assertThat(found).as("an entity field defaulted to a normal finding (QA D1525)")
                .isSubsetOf(ENTITY_ENUM_ALLOWED.keySet());
        assertThat(ENTITY_ENUM_ALLOWED.keySet()).as("a listed site was fixed: delete its line").isSubsetOf(found);
    }

    @Test
    void noWriteTurnsAMissingFindingIntoTrue() {
        Set<String> found = new TreeSet<>();
        for (Path f : javaFiles(MAIN)) {
            Matcher m = NULL_AS_TRUE.matcher(read(f));
            while (m.find()) {
                if (!NULL_AS_TRUE_BOOKKEEPING.contains(m.group(2))) {
                    found.add(f.getFileName() + "#" + m.group(2));
                }
            }
        }
        assertThat(found).as("a missing Boolean recorded as true (QA D1525): keep null as 'not stated'")
                .isSubsetOf(NULL_AS_TRUE_DEBT.keySet());
        assertThat(NULL_AS_TRUE_DEBT.keySet()).as("a listed debt was fixed: delete its line").isSubsetOf(found);
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
