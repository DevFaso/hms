package com.example.hms.service.emar;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.enums.DosageForm;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.service.emar.MedicationLabelMatch.Basis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D3552 (r71, regression), the class guard: a box, a label or the order's own display writes the dosage form in
 * the reader's language, and the bedside "right drug" check must read it as the catalogue's form whatever the
 * language. For EVERY dosage form, its English, French and Spanish words (singular and plural) pass for the
 * matching product, while another strength and another form stay refused. The forms and words are the portal's:
 * its {@code DOSAGE_FORMS} list and the three translations of {@code PHARMACY.FORM_OPTION}, so a form added there
 * without its words here fails this test.
 */
class DosageFormLabelGuardTest {

    private static final Path PORTAL = Path.of("..", "hospital-portal");
    private static final Path CATALOG_TS = PORTAL.resolve("src/app/pharmacy/medication-catalog.ts");
    private static final Pattern DOSAGE_FORMS = Pattern.compile(
        "DOSAGE_FORMS\\s*:\\s*readonly\\s+string\\[\\]\\s*=\\s*\\[([^\\]]*)\\]");
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");
    private static final UUID ITEM_ID = UUID.fromString("7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f");

    @Test
    void everyFormWordInEveryLanguageIsTheCatalogueForm() {
        for (DosageForm form : DosageForm.values()) {
            for (String word : wordsOf(form)) {
                for (String stored : new String[] {form.name(), form.french().get(0)}) {
                    MedicationCatalogItem item = item(stored);
                    assertThat(match(item, "Testamine 250 mg " + word)).as("%s on a %s item", word, stored)
                        .isEqualTo(Basis.DESIGNATION);
                    assertThat(match(item, "TESTAMINE 250MG " + word.toUpperCase(Locale.ROOT)))
                        .as("%s upper case on a %s item", word, stored).isEqualTo(Basis.DESIGNATION);
                    assertThat(match(item, "Testamine 500 mg " + word)).as("another strength, %s", word)
                        .isEqualTo(Basis.NONE);
                    assertThat(match(item, "Testa " + word)).as("a prefix, %s", word).isEqualTo(Basis.NONE);
                    assertThat(match(item, "Autremine 250 mg " + word)).as("another drug, %s", word)
                        .isEqualTo(Basis.NONE);
                }
            }
        }
    }

    @Test
    void anotherFormInAnyLanguageIsStillRefused() {
        for (DosageForm form : DosageForm.values()) {
            MedicationCatalogItem item = item(form.name());
            for (DosageForm other : DosageForm.values()) {
                if (other == form) {
                    continue;
                }
                for (String word : wordsOf(other)) {
                    assertThat(match(item, "Testamine 250 mg " + word)).as("%s on a %s item", word, form)
                        .isEqualTo(Basis.NONE);
                }
            }
        }
    }

    /** The order written in free text with its form word: that word reads in the other two languages too. */
    @Test
    void aFreeTextOrderReadsItsFormWordInEveryLanguage() {
        for (DosageForm form : DosageForm.values()) {
            Prescription rx = new Prescription();
            rx.setMedicationName("Testamine 250 mg " + form.french().get(0));
            for (String word : wordsOf(form)) {
                assertThat(MedicationLabelMatch.match(rx, null, "Testamine 250 mg " + word)).as(word)
                    .isEqualTo(Basis.DESIGNATION);
                assertThat(MedicationLabelMatch.match(rx, null, "Testamine 500 mg " + word)).as(word)
                    .isEqualTo(Basis.NONE);
            }
        }
    }

    /** One word, one form: a word shared by two forms would read one as the other. */
    @Test
    void noWordNamesTwoForms() {
        Map<String, DosageForm> owner = new HashMap<>();
        for (DosageForm form : DosageForm.values()) {
            for (String word : form.words()) {
                DosageForm previous = owner.put(word, form);
                assertThat(previous).as("« %s » names %s and %s", word, previous, form).isNull();
            }
            assertThat(form.english()).as("%s: English singular and plural", form).hasSize(2);
            assertThat(form.french()).as("%s: French singular and plural", form).hasSize(2);
            assertThat(form.spanish()).as("%s: Spanish singular and plural", form).hasSize(2);
        }
    }

    @Test
    void theFormsAreThePortals() throws IOException {
        String catalog = Files.readString(CATALOG_TS, StandardCharsets.UTF_8);
        Matcher list = DOSAGE_FORMS.matcher(catalog);
        assertThat(list.find()).as("DOSAGE_FORMS in %s", CATALOG_TS).isTrue();
        Set<String> portal = new TreeSet<>();
        Matcher quoted = QUOTED.matcher(list.group(1));
        while (quoted.find()) {
            portal.add(quoted.group(1));
        }
        Set<String> server = new TreeSet<>(Arrays.stream(DosageForm.values()).map(Enum::name).toList());
        assertThat(server).isEqualTo(portal);
    }

    /** What the portal shows for each form, in each language, is a word this class reads as that form. */
    @Test
    void everyTranslationOfAFormIsReadAsThatForm() throws IOException {
        ObjectMapper json = new ObjectMapper();
        for (String language : new String[] {"en", "fr", "es"}) {
            Path file = PORTAL.resolve("src/assets/i18n/" + language + ".json");
            JsonNode options = json.readTree(Files.readString(file, StandardCharsets.UTF_8))
                .path("PHARMACY").path("FORM_OPTION");
            assertThat(options.isObject()).as("PHARMACY.FORM_OPTION in %s", file).isTrue();
            for (DosageForm form : DosageForm.values()) {
                String label = options.path(form.name()).asText("");
                assertThat(label).as("%s in %s", form, language).isNotBlank();
                assertThat(DosageForm.of(label)).as("« %s » (%s, %s)", label, form, language).isEqualTo(form);
            }
        }
    }

    private static List<String> wordsOf(DosageForm form) {
        List<String> words = new ArrayList<>(form.english());
        words.addAll(form.french());
        words.addAll(form.spanish());
        words.add(form.name());
        return words;
    }

    private static Basis match(MedicationCatalogItem item, String scan) {
        Prescription rx = new Prescription();
        rx.setMedicationName("Testamine 250 mg");
        rx.setMedicationCatalogItemId(ITEM_ID);
        return MedicationLabelMatch.match(rx, item, scan);
    }

    private static MedicationCatalogItem item(String form) {
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setId(ITEM_ID);
        item.setNameFr("Testamine 250 mg");
        item.setGenericName("Testamine");
        item.setStrength("250");
        item.setStrengthUnit("mg");
        item.setForm(form);
        return item;
    }
}
