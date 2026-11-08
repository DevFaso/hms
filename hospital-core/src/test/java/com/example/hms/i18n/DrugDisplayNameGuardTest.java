package com.example.hms.i18n;

import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.utility.DrugDisplayNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1235 (round 24): the wrong-drug refusal named the lot's drug by its
 * English INN to a Spanish pharmacist, because the refusal chose between the
 * catalogue's names with its own {@code "fr".equals(language)} test. Every
 * catalogue drug named in text a person reads goes through
 * {@link DrugDisplayNames}. This reads the main tree for:
 * <ul>
 *   <li>a file other than {@code DrugDisplayNames} that reads the reader's
 *       language and a catalogue name (its own naming rule);</li>
 *   <li>a refusal or resolved message ({@code new …Exception(…)},
 *       {@code MessageUtil.resolve}, {@code messageSource.getMessage},
 *       {@code NotificationText.of}, a dispense check's {@code .fail(…)}) or
 *       a message argument array ({@code Object[] …Args = {…}}) handed a raw
 *       catalogue name ({@code getNameFr()}, {@code getGenericName()},
 *       {@code getBrandName()}).</li>
 * </ul>
 */
@DisplayName("A catalogue drug is named in the reader's language (QA D1235)")
class DrugDisplayNameGuardTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final String CHOKE_POINT = "com/example/hms/utility/DrugDisplayNames.java";

    private static final Pattern CATALOGUE_NAME = Pattern.compile("\\.get(?:NameFr|GenericName|BrandName)\\(\\)");
    private static final Pattern MESSAGE_CALL = Pattern.compile(
        "(?:new\\s+(?:[a-z]\\w*+\\.)*+\\w*Exception|MessageUtil\\.resolve\\w*|messageSource\\.getMessage"
            + "|getLocalizedMessage|NotificationText\\.of|\\.fail)\\s*+\\(");
    private static final Pattern ARGS_ARRAY = Pattern.compile("Object\\[\\]\\s++(\\w*Args)\\s*+=\\s*+\\{");

    /** Message arrays that keep the stored name on purpose, with why. */
    private static final Map<String, String> ALLOWED_ARRAYS = Map.of(
        "com/example/hms/service/pharmacy/InventoryServiceImpl.java#auditArgs",
        "the audit row is written once in English for every reader (QA D894) and keeps the catalogue's stored name");

    @Test
    @DisplayName("only DrugDisplayNames chooses a catalogue name by the reader's language")
    void onlyTheChokePointReadsTheLanguage() throws IOException {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> file : mainSources().entrySet()) {
            String src = file.getValue();
            if (!CHOKE_POINT.equals(file.getKey()) && src.contains("getLanguage()")
                && CATALOGUE_NAME.matcher(src).find()) {
                found.add(file.getKey());
            }
        }
        assertThat(found).as("Name the drug with DrugDisplayNames.forReader(...)").isEmpty();
    }

    @Test
    @DisplayName("no refusal or message is handed a raw catalogue name")
    void noRawCatalogueNameInAMessage() throws IOException {
        List<String> found = new ArrayList<>();
        List<String> allowedSeen = new ArrayList<>();
        for (Map.Entry<String, String> file : mainSources().entrySet()) {
            found.addAll(rawNames(file.getKey(), file.getValue(), allowedSeen));
        }
        assertThat(found).as("Name the drug with DrugDisplayNames.forReader(...)").isEmpty();
        assertThat(allowedSeen).as("an allowed array was removed: delete its entry")
            .containsExactlyInAnyOrderElementsOf(ALLOWED_ARRAYS.keySet());
    }

    @Test
    @DisplayName("the scan catches the D1235 shapes")
    void scanCatchesTheOldShapes() {
        List<String> ignored = new ArrayList<>();
        assertThat(rawNames("A.java",
            "b.fail(DispenseCheck.DRUG, MessageUtil.resolve(\"pharmacy.check.wrongDrug\", item.getGenericName(), p));",
            ignored)).hasSize(1);
        assertThat(rawNames("A.java", "Object[] alertArgs = {item.getMedicationCatalogItem().getNameFr(), x};",
            ignored)).hasSize(1);
        assertThat(rawNames("A.java",
            "MessageUtil.resolve(\"pharmacy.check.wrongDrug\", DrugDisplayNames.forReader(item), p);", ignored))
            .isEmpty();
    }

    @Test
    @DisplayName("QA D1235: the Spanish reader reads the name the catalogue shows, never the English INN")
    void theNamingRule() {
        MedicationCatalogItem amoxicillin = new MedicationCatalogItem();
        amoxicillin.setNameFr("Amoxicilline");
        amoxicillin.setGenericName("Amoxicillin");
        assertThat(DrugDisplayNames.forReader(amoxicillin, Locale.FRENCH)).isEqualTo("Amoxicilline");
        assertThat(DrugDisplayNames.forReader(amoxicillin, Locale.ENGLISH)).isEqualTo("Amoxicillin");
        assertThat(DrugDisplayNames.forReader(amoxicillin, Locale.forLanguageTag("es"))).isEqualTo("Amoxicilline");
        MedicationCatalogItem genericOnly = new MedicationCatalogItem();
        genericOnly.setGenericName("Artemether/Lumefantrine");
        assertThat(DrugDisplayNames.forReader(genericOnly, Locale.forLanguageTag("es")))
            .isEqualTo("Artemether/Lumefantrine");
        assertThat(DrugDisplayNames.forReader(new MedicationCatalogItem(), Locale.FRENCH)).isNull();
        assertThat(DrugDisplayNames.forReader(null, Locale.FRENCH)).isNull();
    }

    private static List<String> rawNames(String file, String source, List<String> allowedSeen) {
        List<String> out = new ArrayList<>();
        Matcher call = MESSAGE_CALL.matcher(source);
        while (call.find()) {
            for (String arg : DisplayDateFormatGuardTest.SourceArguments.split(source, call.end())) {
                if (CATALOGUE_NAME.matcher(arg).find() && !arg.contains("DrugDisplayNames.")
                    && !MESSAGE_CALL.matcher(arg).find()) {
                    out.add(file + " " + arg.replaceAll("\\s+", " "));
                }
            }
        }
        Matcher array = ARGS_ARRAY.matcher(source);
        while (array.find()) {
            int end = source.indexOf("};", array.end());
            String body = end < 0 ? "" : source.substring(array.end(), end);
            if (CATALOGUE_NAME.matcher(body).find()) {
                String id = file + "#" + array.group(1);
                if (ALLOWED_ARRAYS.containsKey(id)) {
                    allowedSeen.add(id);
                } else {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                out.put(MAIN_JAVA.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
