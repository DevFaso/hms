package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;
import com.example.hms.service.impl.UltrasoundServiceImpl;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 *   <li>a template endpoint that returns a pre-filled clinical request;</li>
 *   <li>an entity field initialised to {@code true} or to a "normal"-type enum
 *       constant (a stated finding on every row built without it);</li>
 *   <li>a write that turns a missing Boolean into {@code true}
 *       ({@code dto.getX() == null || dto.getX()}).</li>
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
            "private\\s+(?:Boolean|boolean)\\s+(\\w+)\\s*=\\s*(?:true|Boolean\\.TRUE)\\s*;");
    private static final Pattern ENUM_FIELD = Pattern.compile(
            "private\\s+(\\w+)\\s+(\\w+)\\s*=\\s*\\1\\.([A-Z_]+)\\s*;");
    private static final Pattern NULL_AS_TRUE = Pattern.compile(
            "(\\w+)\\.(get\\w+|is\\w+)\\(\\)\\s*==\\s*null\\s*\\|\\|\\s*\\1\\.\\2\\(\\)(?=\\s*[),;?:])");
    private static final Pattern TEMPLATE_METHOD = Pattern.compile(
            "public\\s+(\\w*RequestDTO)\\s+(\\w*[Tt]emplate\\w*)\\s*\\(");

    /** Template endpoints returning a clinical request; each is checked by {@link #ultrasoundTemplatesStateNoFinding}. */
    private static final Set<String> KNOWN_TEMPLATES = Set.of(
            "UltrasoundServiceImpl.java#getNuchalTranslucencyTemplate",
            "UltrasoundServiceImpl.java#getAnatomyScanTemplate");

    /** The only property an ultrasound template may fill: an administrative default. */
    private static final Set<String> TEMPLATE_MAY_FILL = Set.of("scanDate");

    @Test
    void ultrasoundTemplatesStateNoFinding() throws Exception {
        // The template methods read no collaborator: build the service with nulls.
        java.lang.reflect.Constructor<?> ctor = UltrasoundServiceImpl.class.getDeclaredConstructors()[0];
        UltrasoundServiceImpl service = (UltrasoundServiceImpl) ctor.newInstance(
                new Object[ctor.getParameterCount()]);
        for (UltrasoundReportRequestDTO template : List.of(
                service.getNuchalTranslucencyTemplate(), service.getAnatomyScanTemplate())) {
            List<String> filled = new ArrayList<>();
            for (PropertyDescriptor p : Introspector.getBeanInfo(UltrasoundReportRequestDTO.class).getPropertyDescriptors()) {
                if (p.getReadMethod() == null || "class".equals(p.getName()) || TEMPLATE_MAY_FILL.contains(p.getName())) {
                    continue;
                }
                if (p.getReadMethod().invoke(template) != null) {
                    filled.add(p.getName());
                }
            }
            assertThat(filled).as("an ultrasound template pre-fills what only the clinician may state (QA D1525)")
                    .isEmpty();
        }
    }

    @Test
    void noOtherTemplateReturnsAPrefilledClinicalRequest() {
        Set<String> found = new TreeSet<>();
        for (Path f : javaFiles(MAIN)) {
            Matcher m = TEMPLATE_METHOD.matcher(read(f));
            while (m.find()) {
                found.add(f.getFileName() + "#" + m.group(2));
            }
        }
        assertThat(found)
                .as("a new template endpoint: add it to KNOWN_TEMPLATES with a test that it states no finding (QA D1525)")
                .isEqualTo(new TreeSet<>(KNOWN_TEMPLATES));
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
